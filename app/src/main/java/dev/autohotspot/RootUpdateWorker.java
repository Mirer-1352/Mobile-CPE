package dev.autohotspot;

import android.content.Context;
import android.content.pm.*;
import android.os.*;
import android.system.Os;
import org.json.*;
import java.io.*;
import java.lang.Process;
import java.nio.file.*;
import java.nio.channels.FileLock;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Independent UID 0 worker: its dex, APK and status survive replacing the caller package. */
public final class RootUpdateWorker {
    private static final String PACKAGE = "dev.autohotspot";
    private static final File STATUS_DIR = new File("/data/system/mobile-cpe-updates");
    private static final long MAX_SIZE = 512L * 1024 * 1024;

    private static File statusFile(int user) throws Exception {
        if (user < 0 || user > 21474) throw new IllegalArgumentException("Invalid user");
        if (!STATUS_DIR.isDirectory() && !STATUS_DIR.mkdir()) throw new IOException("Cannot create update status directory");
        Os.chmod(STATUS_DIR.getPath(), 0700);
        return new File(STATUS_DIR, user + ".json");
    }
    public static JSONObject status(int user) throws Exception {
        JSONObject value = statusStore(user).read();
        return value == null ? new JSONObject().put("state", "none") : value;
    }
    private static AtomicJsonFile statusStore(int user) throws Exception {
        return new AtomicJsonFile(statusFile(user), new File(STATUS_DIR, user + ".status.lock"), 65536, 0);
    }
    private static void writeStatus(JSONObject job, String state, String detail) throws Exception {
        JSONObject value = new JSONObject().put("job", job.getString("job")).put("versionCode", job.getLong("versionCode"))
            .put("sha256", job.getString("sha256")).put("state", state).put("detail", detail)
            .put("at", System.currentTimeMillis());
        statusStore(job.getInt("user")).write(value);
    }
    public static JSONObject prepare(Context context, JSONObject request) throws Exception {
        int user = request.getInt("user");
        statusFile(user); // Validate the user and establish the private directory.
        File lock = new File(STATUS_DIR, user + ".prepare.lock");
        try (RandomAccessFile file = new RandomAccessFile(lock, "rw")) {
            Os.chmod(lock.getPath(), 0600);
            try (FileLock ignored = file.getChannel().lock()) { return prepareLocked(context, request); }
        }
    }
    private static JSONObject prepareLocked(Context context, JSONObject request) throws Exception {
        int user = request.getInt("user");
        JSONObject old = status(user);
        context = AndroidPlatform.asUser(context, user);
        String state = old.optString("state");
        if ((state.equals("preparing") || state.equals("installing")) && System.currentTimeMillis() - old.optLong("at") < 180000)
            throw new IllegalStateException("已有 Root 更新任务正在运行");
        File input = new File(request.getString("path")).getCanonicalFile();
        String source = request.getString("source");
        PackageInfo current = context.getPackageManager().getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
        if (!new File(source).getCanonicalPath().equals(new File(current.applicationInfo.sourceDir).getCanonicalPath()))
            throw new SecurityException("更新执行器必须来自当前已安装 APK");
        JSONObject job = new JSONObject().put("job", UUID.randomUUID().toString()).put("user", user)
            .put("versionCode", request.getLong("versionCode")).put("sha256", request.getString("sha256"))
            .put("size", request.getLong("size")).put("reopen", request.optBoolean("reopen"));
        if (job.getLong("versionCode") <= current.getLongVersionCode()) throw new SecurityException("拒绝非升级安装");
        verify(context, input, job);
        File directory = Files.createTempDirectory(Paths.get("/data/local/tmp"), "mobile-cpe-update-").toFile();
        Os.chmod(directory.getPath(), 0700);
        File candidate = new File(directory, "candidate.apk"), runner = new File(directory, "runner.apk");
        try {
            Files.copy(input.toPath(), candidate.toPath()); Os.chmod(candidate.getPath(), 0400);
            Files.copy(Paths.get(source), runner.toPath()); Os.chmod(runner.getPath(), 0400);
            job.put("path", candidate.getPath());
            File jobFile = new File(directory, "job.json");
            try (FileOutputStream out = new FileOutputStream(jobFile)) {
                out.write(job.toString().getBytes("UTF-8")); out.getFD().sync();
            }
            Os.chmod(jobFile.getPath(), 0600);
            ProcessBuilder builder = new ProcessBuilder("/system/bin/app_process", "/system/bin", RootUpdateWorker.class.getName(), jobFile.getPath());
            builder.environment().put("CLASSPATH", runner.getPath());
            builder.redirectInput(new File("/dev/null")); builder.redirectErrorStream(true);
            builder.redirectOutput(new File(directory, "worker.log"));
            Process process = builder.start();
            // The worker calls setsid BEFORE announcing readiness, so it cannot die with the su client.
            for (int i = 0; i < 100; i++) {
                JSONObject result = status(user);
                if (job.getString("job").equals(result.optString("job"))) {
                    if (result.optString("state").equals("failed")) throw new IOException(result.optString("detail"));
                    return new JSONObject().put("ok", true).put("job", job.getString("job"));
                }
                if (!process.isAlive()) throw new IOException("Root 更新进程未能启动");
                Thread.sleep(100);
            }
            process.destroy(); throw new IOException("Root 更新进程就绪超时");
        } catch (Exception error) {
            // Only fixed files in this newly-created private staging directory are removed.
            for (String name : new String[]{"candidate.apk", "runner.apk", "job.json", "worker.log"}) new File(directory, name).delete();
            directory.delete(); throw error;
        }
    }
    public static void main(String[] args) {
        JSONObject job = null;
        boolean succeeded = false;
        try {
            if (android.os.Process.myUid() != 0) throw new SecurityException("Root required");
            Os.setsid();
            try (InputStream in = new FileInputStream(args[0])) { job = new JSONObject(new String(readLimited(in, 65536), "UTF-8")); }
            Context context = AndroidPlatform.asUser(AndroidPlatform.rootContext(), job.getInt("user"));
            writeStatus(job, "preparing", "Root 更新执行器已独立运行");
            Thread.sleep(1800); // Let the caller persist the handoff before package replacement kills it.
            File apk = new File(job.getString("path"));
            verify(context, apk, job); // Rehash the private root-owned copy immediately before install.
            writeStatus(job, "installing", "正在覆盖安装");
            File result = new File(apk.getParentFile(), "install.log");
            Process installer = new ProcessBuilder(RootInstallCommand.build(job.getInt("user"), apk.length()))
                .redirectErrorStream(true).redirectOutput(result).start();
            ExecutorService io = Executors.newSingleThreadExecutor();
            Future<?> feeder = io.submit(() -> {
                try (InputStream in = new FileInputStream(apk); OutputStream out = installer.getOutputStream()) {
                    byte[] buffer = new byte[65536]; int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                } catch (IOException error) { throw new UncheckedIOException(error); }
            });
            try {
                if (!installer.waitFor(120, TimeUnit.SECONDS)) { installer.destroy(); throw new IOException("安装超时，保留原应用并检查实际版本"); }
                String output;
                try (InputStream in = new FileInputStream(result)) { output = new String(readLimited(in, 65536), "UTF-8"); }
                if (installer.exitValue() != 0) throw new IOException("系统拒绝安装：" + output.trim());
                feeder.get(2, TimeUnit.SECONDS);
            } finally { io.shutdownNow(); }
            long code = context.getPackageManager().getPackageInfo(PACKAGE, 0).getLongVersionCode();
            if (code != job.getLong("versionCode")) throw new IOException("安装返回成功但版本核对失败");
            succeeded = true;
            writeStatus(job, "succeeded", "更新完成，正在恢复共享守护");
            String user = Integer.toString(job.getInt("user"));
            Context app = context.createPackageContext(PACKAGE, Context.CONTEXT_IGNORE_SECURITY).createDeviceProtectedStorageContext();
            boolean enabled = app.getSharedPreferences("router", Context.MODE_PRIVATE).getBoolean("enabled", true);
            String recovery = enabled ? run("/system/bin/am", "start-foreground-service", "--user", user, "-n", PACKAGE + "/.HotspotService")
                : "共享守护已暂停";
            if (job.optBoolean("reopen")) {
                try { recovery += "\n" + run("/system/bin/am", "start", "--user", user, "-n", PACKAGE + "/.MainActivity"); }
                catch (Exception error) { recovery += "\n面板恢复：" + error.getMessage(); }
            }
            writeStatus(job, "succeeded", "安装成功；恢复结果：" + recovery.trim());
        } catch (Throwable error) {
            error.printStackTrace();
            if (job != null) try { writeStatus(job, succeeded ? "succeeded" : "failed", (succeeded ? "安装成功，恢复需重试：" : "") + error); } catch (Exception ignored) { }
        } finally {
            if (job != null) {
                File directory = new File(job.optString("path")).getParentFile();
                if (directory != null && directory.getParentFile().equals(new File("/data/local/tmp")) && directory.getName().startsWith("mobile-cpe-update-")) {
                    for (String name : new String[]{"candidate.apk", "runner.apk", "job.json", "install.log", "worker.log"}) new File(directory, name).delete();
                    directory.delete();
                }
            }
            System.exit(0);
        }
    }
    private static void verify(Context context, File file, JSONObject job) throws Exception {
        long size = job.getLong("size");
        if (size <= 0 || size > MAX_SIZE || file.length() != size) throw new SecurityException("APK 大小不匹配");
        String expected = job.getString("sha256");
        if (!expected.matches("[0-9a-f]{64}")) throw new SecurityException("Invalid SHA-256");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int n; while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        StringBuilder hash = new StringBuilder(); for (byte b : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", b));
        if (!expected.equals(hash.toString())) throw new SecurityException("APK SHA-256 不匹配");
        PackageManager pm = context.getPackageManager();
        PackageInfo candidate = pm.getPackageArchiveInfo(file.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo current = pm.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
        if (candidate == null || !PACKAGE.equals(candidate.packageName) || candidate.getLongVersionCode() != job.getLong("versionCode"))
            throw new SecurityException("APK 包名或版本不匹配");
        if (candidate.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT) throw new SecurityException("APK 不支持当前 Android 版本");
        if (signers(current.signingInfo).isEmpty() || !signers(current.signingInfo).equals(signers(candidate.signingInfo)))
            throw new SecurityException("APK 签名不一致");
    }
    private static Set<String> signers(SigningInfo info) {
        Set<String> result = new HashSet<>();
        if (info != null) for (Signature signature : info.getApkContentsSigners()) result.add(signature.toCharsString());
        return result;
    }
    private static byte[] readLimited(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
        while ((n = in.read(buffer)) != -1) { if (out.size() + n > limit) throw new IOException("Response too large"); out.write(buffer, 0, n); }
        return out.toByteArray();
    }
    private static String run(String... command) throws Exception {
        File output = File.createTempFile("recovery-", ".log", STATUS_DIR);
        try {
            Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output).start();
            if (!child.waitFor(15, TimeUnit.SECONDS)) { child.destroy(); throw new IOException("恢复命令超时"); }
            String result;
            try (InputStream in = new FileInputStream(output)) { result = new String(readLimited(in, 16384), "UTF-8"); }
            if (child.exitValue() != 0) throw new IOException("恢复命令失败：" + result.trim());
            return result;
        } finally { output.delete(); }
    }
}

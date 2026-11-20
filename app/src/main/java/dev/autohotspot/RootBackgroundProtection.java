package dev.autohotspot;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.system.Os;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Standard Android battery exemption for our own package. No global power/freezer switches. */
final class RootBackgroundProtection {
    private static final File DIRECTORY = new File("/data/system/mobile-cpe-background");

    static JSONObject sync(Context context, GuardianFiles policies) throws Exception {
        try {
            if (!DIRECTORY.isDirectory() && !DIRECTORY.mkdir()) throw new IOException("Cannot create background protection directory");
            Os.chmod(DIRECTORY.getPath(), 0700);
            AtomicJsonFile store = new AtomicJsonFile(new File(DIRECTORY, "lease.json"), new File(DIRECTORY, "lease.lock"), 16 * 1024, 0);
            return store.access(record -> {
                Set<String> installs = new TreeSet<>();
                boolean wanted = false;
                for (int user : policies.users()) {
                    GuardianPolicy policy = policies.policy(user);
                    if (policy == null) continue;
                    try {
                        long installed = AndroidPlatform.asUser(context, user).getPackageManager()
                            .getPackageInfo(GuardianContract.PACKAGE, PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE)
                            .firstInstallTime;
                        if (installed != policy.installedAt) continue;
                        installs.add(user + ":" + installed);
                        wanted |= policy.enabled;
                    } catch (PackageManager.NameNotFoundException removed) { }
                }
                JSONObject previous = record.read();
                Set<String> previousInstalls = new HashSet<>();
                JSONArray old = previous == null ? null : previous.optJSONArray("installs");
                if (old != null) for (int i = 0; i < old.length(); i++) previousInstalls.add(old.getString(i));
                boolean owned = BackgroundProtectionPolicy.owns(previous != null && previous.optBoolean("owned"), previousInstalls, installs);
                PowerManager power = context.getSystemService(PowerManager.class);
                boolean exempt = power.isIgnoringBatteryOptimizations(GuardianContract.PACKAGE);
                BackgroundProtectionPolicy.Action action = BackgroundProtectionPolicy.action(wanted, exempt, owned);
                JSONObject lease = new JSONObject().put("schema", 1).put("installs", new JSONArray(installs))
                    .put("owned", owned).put("wanted", wanted).put("action", action.name());
                // Persist ownership before ADD: a killed caller must not forget an exemption it created.
                if (action == BackgroundProtectionPolicy.Action.ADD) lease.put("owned", true);
                record.write(lease);
                String error = null;
                if (action != BackgroundProtectionPolicy.Action.NONE) {
                    try { change(action == BackgroundProtectionPolicy.Action.ADD); }
                    catch (Exception failure) { error = failure.toString(); }
                    exempt = power.isIgnoringBatteryOptimizations(GuardianContract.PACKAGE);
                    if (action == BackgroundProtectionPolicy.Action.ADD) owned = exempt;
                    else if (!exempt) owned = false;
                    if (exempt != wanted && error == null) error = "System did not apply battery exemption";
                }
                if (!exempt) owned = false;
                lease.put("owned", owned).put("exempt", exempt).put("error", error == null ? JSONObject.NULL : error);
                record.write(lease);
                return new JSONObject(lease.toString());
            });
        } catch (Exception failure) {
            return new JSONObject().put("error", failure.toString());
        }
    }

    private static void change(boolean add) throws Exception {
        Process process = new ProcessBuilder("/system/bin/cmd", "deviceidle", "whitelist", (add ? "+" : "-") + GuardianContract.PACKAGE)
            .redirectErrorStream(true).start();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) throw new IOException("Battery exemption command timed out");
            byte[] bytes = new byte[4096];
            int size = process.getInputStream().read(bytes);
            if (process.exitValue() != 0) throw new IOException("Battery exemption rejected: " +
                (size <= 0 ? "" : new String(bytes, 0, size, StandardCharsets.UTF_8).trim()));
        } finally { process.destroy(); }
    }
}

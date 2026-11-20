package dev.autohotspot;

import android.content.*;
import android.content.pm.*;
import android.system.Os;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** A per-user HOME lease. Restore only a default we took over; never guess a Binder transaction. */
final class RootHomeController {
    private static final File DIRECTORY = new File("/data/system/mobile-cpe-home-state");
    private static final ComponentName CPE = new ComponentName(GuardianContract.PACKAGE, GuardianContract.PACKAGE + ".MainActivity");

    static JSONObject sync(Context context, GuardianPolicy policy, boolean wanted, String originalHint) throws Exception {
        Context userContext = AndroidPlatform.asUser(context, policy.user);
        PackageManager pm = userContext.getPackageManager();
        if (!DIRECTORY.isDirectory() && !DIRECTORY.mkdir()) throw new IOException("Cannot create HOME lease directory");
        Os.chmod(DIRECTORY.getPath(), 0700);
        AtomicJsonFile store = new AtomicJsonFile(new File(DIRECTORY, policy.user + ".json"),
            new File(DIRECTORY, policy.user + ".lock"), 4096, 0);
        return store.access(record -> {
            JSONObject old = record.read();
            boolean sameInstall = old != null && old.optLong("installedAt") == policy.installedAt;
            ComponentName current = preferred(pm);
            ComponentName original = sameInstall ? ComponentName.unflattenFromString(old.optString("original")) : null;
            if (wanted && current != null && !CPE.equals(current)) original = current;
            if (!usable(pm, original)) original = ComponentName.unflattenFromString(originalHint);
            if (!usable(pm, original)) original = null;
            JSONObject lease = new JSONObject().put("installedAt", policy.installedAt).put("wanted", wanted)
                .put("original", original == null ? "" : original.flattenToString());
            // Save the restoration target before changing the system, including across process death.
            record.write(lease);
            String error = null;
            HomeOverrides.set(policy.user, wanted);
            try {
                if (wanted && !CPE.equals(current)) set(policy.user, CPE);
                else if (!wanted && CPE.equals(current)) {
                    if (original == null) throw new IOException("未找到可恢复的原桌面");
                    set(policy.user, original);
                }
            } catch (Exception failure) { error = failure.toString(); }
            ComponentName nativeHome = preferred(pm);
            ResolveInfo resolved = pm.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY);
            ComponentName effective = resolved == null || resolved.activityInfo == null ? null :
                new ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name);
            boolean active = CPE.equals(effective);
            // A successful framework fallback is sufficient even when the ROM rejects the native role.
            if (active == wanted) error = null;
            else if (error == null) error = wanted ? "等待系统框架接管桌面" : "系统仍将手机CPE设为默认桌面";
            return lease.put("effective", active).put("native", CPE.equals(nativeHome))
                .put("error", error == null ? JSONObject.NULL : error);
        });
    }

    private static Intent homeIntent() { return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME); }

    private static ComponentName preferred(PackageManager pm) throws Exception {
        // This API queries the stored preference directly; our implicit-intent resolver hook cannot mask it.
        return (ComponentName) PackageManager.class.getMethod("getHomeActivities", List.class)
            .invoke(pm, new ArrayList<ResolveInfo>());
    }

    private static boolean usable(PackageManager pm, ComponentName component) {
        if (component == null || CPE.getPackageName().equals(component.getPackageName())) return false;
        for (ResolveInfo info : pm.queryIntentActivities(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)) {
            ActivityInfo activity = info.activityInfo;
            if (activity != null && activity.enabled && activity.exported && activity.applicationInfo.enabled &&
                !activity.name.endsWith(".FallbackHome") && component.equals(new ComponentName(activity.packageName, activity.name))) return true;
        }
        return false;
    }

    private static void set(int user, ComponentName component) throws Exception {
        Process process = new ProcessBuilder("/system/bin/cmd", "package", "set-home-activity", "--user",
            Integer.toString(user), component.flattenToString()).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) throw new IOException("设置默认桌面超时");
            byte[] output = new byte[4096];
            int size = process.getInputStream().read(output);
            if (process.exitValue() != 0) throw new IOException("系统拒绝设置默认桌面：" +
                (size <= 0 ? "" : new String(output, 0, size, StandardCharsets.UTF_8).trim()));
        } finally { process.destroy(); }
    }
}

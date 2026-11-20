package dev.autohotspot;

import android.system.Os;
import java.io.File;
import java.io.IOException;

/** Per-user desired HOME override. The owner user's old flag remains readable during migration. */
final class HomeOverrides {
    private static final File DIRECTORY = new File("/data/system/mobile-cpe-home");
    private static final File LEGACY = new File("/data/system/autohotspot-home");

    private static File flag(int user) {
        if (user < 0 || user > 21474) throw new IllegalArgumentException("Invalid user");
        return new File(DIRECTORY, user + ".enabled");
    }

    static boolean enabled(int user) { return flag(user).exists() || user == 0 && LEGACY.exists(); }

    static void set(int user, boolean enabled) throws Exception {
        File flag = flag(user);
        if (enabled) {
            if (!DIRECTORY.isDirectory() && !DIRECTORY.mkdir()) throw new IOException("Cannot create HOME settings directory");
            if (android.os.Process.myUid() == 0) Os.chown(DIRECTORY.getPath(), 1000, 1000);
            Os.chmod(DIRECTORY.getPath(), 0755);
            if (!flag.exists() && !flag.createNewFile()) throw new IOException("Cannot save HOME override");
            Os.chmod(flag.getPath(), 0644);
        } else if (flag.exists() && !flag.delete()) throw new IOException("Cannot clear HOME override");
        if (user == 0 && LEGACY.exists() && !LEGACY.delete()) throw new IOException("Cannot clear legacy HOME override");
    }
}

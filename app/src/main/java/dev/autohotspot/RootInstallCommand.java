package dev.autohotspot;

/** Android 9+ package shell stdin installation; no shell interpolation or version-specific flags. */
final class RootInstallCommand {
    static String[] build(int user, long size) {
        if (user < 0 || user > 21474 || size <= 0 || size > 512L * 1024 * 1024)
            throw new IllegalArgumentException("Invalid install user or size");
        // Omitting the pathname selects stdin with -S on both legacy and modular package shells.
        // A bare trailing '-' is parsed as an unknown option on some newer implementations.
        return new String[]{"/system/bin/cmd", "package", "install", "-r", "--user", Integer.toString(user), "-S", Long.toString(size)};
    }
}

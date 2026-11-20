package dev.autohotspot;

import android.system.Os;
import org.json.JSONObject;
import java.io.*;
import java.util.*;

/** Per-user policy and diagnostics; the on-disk schema remains stable across APK updates. */
final class GuardianFiles {
    private final File directory;
    GuardianFiles(File directory) { this.directory = directory; }

    private void ensure() throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create guardian directory");
        if (android.os.Process.myUid() == 0) Os.chown(directory.getPath(), 1000, 1000);
        Os.chmod(directory.getPath(), 0700);
    }

    private AtomicJsonFile record(int user, String suffix) {
        if (user < 0 || user > 21474) throw new IllegalArgumentException("Invalid user");
        File file = new File(directory, user + suffix);
        File lock = new File(directory, user + (suffix.equals(".json") ? ".lock" : suffix + ".lock"));
        return new AtomicJsonFile(file, lock, 16384, 1000);
    }

    GuardianPolicy policy(int user) throws Exception {
        JSONObject value = record(user, ".json").read();
        return value == null ? null : GuardianPolicy.parse(value);
    }

    boolean save(GuardianPolicy policy) throws Exception {
        return save(policy, () -> { });
    }

    interface AppliedPolicy { void apply() throws Exception; }

    boolean save(GuardianPolicy policy, AppliedPolicy applied) throws Exception {
        ensure();
        return record(policy.user, ".json").access(file -> {
            JSONObject value = file.read();
            GuardianPolicy old = value == null ? null : GuardianPolicy.parse(value);
            if (old != null && !old.accepts(policy)) return false;
            if (old == null || !old.sameAs(policy)) file.write(policy.json());
            applied.apply();
            return true;
        });
    }

    void status(int user, JSONObject value) throws Exception { ensure(); record(user, ".status").write(value); }

    List<Integer> users() {
        Set<Integer> users = new TreeSet<>();
        File[] entries = directory.listFiles();
        if (entries != null) for (File file : entries) {
            String name = file.getName();
            if (!name.matches("[0-9]+\\.json(?:\\.bak)?")) continue;
            try {
                int user = Integer.parseInt(name.substring(0, name.indexOf('.')));
                if (user >= 0 && user <= 21474) users.add(user);
            } catch (NumberFormatException ignored) { }
        }
        return new ArrayList<>(users);
    }

    void remove(int user) throws Exception {
        record(user, ".json").delete();
        record(user, ".status").delete();
    }
}

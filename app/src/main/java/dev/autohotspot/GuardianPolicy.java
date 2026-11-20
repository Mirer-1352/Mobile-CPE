package dev.autohotspot;

import org.json.JSONObject;

final class GuardianPolicy {
    final int user;
    final long revision, installedAt;
    final boolean enabled, boot, framework;
    GuardianPolicy(int user, long revision, long installedAt, boolean enabled, boolean boot, boolean framework) {
        if (user < 0 || user > 21474 || revision < 0 || installedAt <= 0) throw new IllegalArgumentException("Invalid guardian policy");
        this.user = user; this.revision = revision; this.installedAt = installedAt;
        this.enabled = enabled; this.boot = boot; this.framework = framework;
    }
    static GuardianPolicy parse(JSONObject j) throws Exception {
        if (j.getInt("schema") != 1) throw new IllegalArgumentException("Invalid guardian schema");
        return new GuardianPolicy(j.getInt("user"), j.getLong("revision"), j.getLong("installedAt"),
            j.getBoolean("enabled"), j.getBoolean("boot"), j.getBoolean("framework"));
    }
    JSONObject json() throws Exception {
        return new JSONObject().put("schema", 1).put("user", user).put("revision", revision).put("installedAt", installedAt)
            .put("enabled", enabled).put("boot", boot).put("framework", framework);
    }
    boolean accepts(GuardianPolicy next) {
        if (next.user != user) return false;
        // Callers validate this identity against PackageManager, not wall-clock ordering.
        if (next.installedAt != installedAt) return true;
        return next.revision > revision || (next.revision == revision && next.enabled == enabled && next.boot == boot && next.framework == framework);
    }
    boolean allows(boolean armed) { return enabled && framework && (boot || armed); }
    boolean sameAs(GuardianPolicy other) {
        return user == other.user && revision == other.revision && installedAt == other.installedAt
            && enabled == other.enabled && boot == other.boot && framework == other.framework;
    }
}

package dev.autohotspot;

import java.util.ArrayDeque;

/** Pure policy: per-boot arming, fresh heartbeat, bounded recovery and upgrade grace. */
final class GuardianEngine {
    enum Action { NONE, START, RESTART_HUNG }
    private boolean armed;
    private long heartbeat = -1, aliveSince = -1, nextAttempt;
    private int failures;
    private final ArrayDeque<Long> attempts = new ArrayDeque<>();
    void arm() { armed = true; }
    void heartbeat(long now) { armed = true; heartbeat = now; }
    long lastHeartbeat() { return heartbeat; }
    boolean protects(GuardianPolicy policy) { return policy.allows(armed); }
    void died() { heartbeat = -1; aliveSince = -1; }
    Action decide(GuardianPolicy policy, long now, boolean userRunning, boolean updating, boolean serviceAlive) {
        if (!policy.enabled || !policy.framework) { armed = false; heartbeat = -1; aliveSince = -1; return Action.NONE; }
        if (!policy.allows(armed) || !userRunning || updating) return Action.NONE;
        if (heartbeat >= 0 && now >= heartbeat && now - heartbeat <= GuardianContract.HEARTBEAT_TTL) {
            if (aliveSince < 0) aliveSince = now;
            if (now - aliveSince >= 30_000) { failures = 0; nextAttempt = 0; }
            return Action.NONE;
        }
        if (serviceAlive) {
            if (aliveSince < 0) aliveSince = now;
            long last = heartbeat >= 0 ? heartbeat : aliveSince;
            if (now - last < GuardianContract.HUNG_TIMEOUT) return Action.NONE;
        } else aliveSince = -1;
        if (now < nextAttempt) return Action.NONE;
        while (!attempts.isEmpty() && now - attempts.peekFirst() >= 300_000) attempts.removeFirst();
        if (attempts.size() >= 3) { nextAttempt = attempts.peekFirst() + 300_000; return Action.NONE; }
        attempts.addLast(now);
        nextAttempt = now + Math.min(120_000, 5_000L << Math.min(failures++, 5));
        return serviceAlive ? Action.RESTART_HUNG : Action.START;
    }
}

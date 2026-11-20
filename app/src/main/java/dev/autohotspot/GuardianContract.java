package dev.autohotspot;

final class GuardianContract {
    static final String PACKAGE = "dev.autohotspot";
    static final String PERMISSION = PACKAGE + ".permission.GUARDIAN_CONTROL";
    static final String CONTROL = PACKAGE + ".GUARDIAN_CONTROL";
    static final String STATUS = PACKAGE + ".GUARDIAN_STATUS";
    static final String DIRECTORY = "/data/system/mobile-cpe-guardian";
    static final String SERVICE_CLASS = PACKAGE + ".HotspotService";
    static android.content.ComponentName serviceComponent() { return new android.content.ComponentName(PACKAGE, SERVICE_CLASS); }
    static final long HEARTBEAT_TTL = 45_000;
    static final long HUNG_TIMEOUT = 90_000;
}

# app_process loads these entry points by name outside the Android manifest.
# Their reachable implementation remains eligible for R8 shrinking and obfuscation.
-keep,allowoptimization class dev.autohotspot.RootMain {
    public static void main(java.lang.String[]);
}
-keep,allowoptimization class dev.autohotspot.RootUpdateWorker {
    public static void main(java.lang.String[]);
}

# META-INF/xposed/java_init.list is a framework-owned entry point. Keep this
# one name stable so existing managers and installed module metadata remain valid.
-keep,allowoptimization class dev.autohotspot.CpeModule {
    public <init>();
}
-dontwarn io.github.libxposed.annotation.**

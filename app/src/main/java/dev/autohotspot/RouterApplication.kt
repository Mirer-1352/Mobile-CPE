package dev.autohotspot

import android.app.Application

class RouterApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppDiagnostics.initialize(this)
        RootSettingsSync.initialize(this)
        runCatching { XposedConnection.initialize(this) }
            .onFailure { Router.event("LSPosed 服务不可用：${it.message}", updateStatus = false) }
        runCatching { FrameworkGuardian.initialize(this) }
            .onFailure { Router.event("框架守护初始化：${it.message}", updateStatus = false) }
    }
}

package dev.autohotspot

/** Classification uses platform constant names, not numeric values that may differ between ROMs. */
enum class SharingFailureKind { PERMISSION, UNSUPPORTED, POLICY, CONFIGURATION, TRANSIENT, UNKNOWN }

data class SharingFailure(val code: Int?, val name: String, val message: String = name) {
    val kind: SharingFailureKind get() = when {
        name.contains("PERMISSION") || name == "SECURITY_EXCEPTION" -> SharingFailureKind.PERMISSION
        name.contains("UNSUPPORTED") || name.contains("UNKNOWN_TYPE") -> SharingFailureKind.UNSUPPORTED
        name.contains("PROVISION") || name.contains("ENTITLEMENT") -> SharingFailureKind.POLICY
        name.contains("CFG_ERROR") || name.contains("DHCPSERVER") || name.contains("NAT_ERROR") -> SharingFailureKind.CONFIGURATION
        name.contains("UNAVAIL") || name.contains("PENDING") || name.contains("SERVICE") || name.contains("DUPLICATE") -> SharingFailureKind.TRANSIENT
        else -> SharingFailureKind.UNKNOWN
    }
    val needsChange get() = kind in setOf(SharingFailureKind.PERMISSION, SharingFailureKind.UNSUPPORTED, SharingFailureKind.POLICY)
    val detail get() = when (kind) {
        SharingFailureKind.PERMISSION -> "系统拒绝权限，等待条件变化"
        SharingFailureKind.UNSUPPORTED -> "系统不支持此共享方式"
        SharingFailureKind.POLICY -> "系统鉴权或策略未通过"
        SharingFailureKind.CONFIGURATION -> "接口配置失败，稍后重试"
        SharingFailureKind.TRANSIENT -> "系统暂不可用，稍后重试"
        SharingFailureKind.UNKNOWN -> "共享请求失败，稍后重试"
    }
}

data class InterfaceFailure(val iface: String, val mode: Sharing?, val failure: SharingFailure, val sequence: Long = 0)
data class SharingProgress(val detail: String, val failure: SharingFailure? = null)

internal fun sharingType(type: Int): Sharing? = when (type) {
    0 -> Sharing.WIFI
    1, 4 -> Sharing.USB
    5 -> Sharing.ETHERNET
    else -> null
}

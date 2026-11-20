# VPN 共享接入

0.5.0 使用 libxposed API 102，在系统自带网络共享的上游选择中接入 VPN。没有修改 VPN 客户端配置，也没有全局改写路由、防火墙或关闭 SELinux。

设置中的「共享流量走 VPN」默认开启，可关闭。偏好通过 libxposed RemotePreferences 同步到注入进程；开关改变和 VPN 网络变化都会触发上游重新选择。无 VPN 时保留系统原上游，有 VPN 时使用 VPN 的 Network、LinkProperties 和 NetworkCapabilities 创建原生上游状态，让系统处理后续 DNS/NAT。开启 VPN 锁定且 VPN 不可用时，不主动选择直连上游。

模块覆盖系统进程以及设备实际使用的 Tethering / NetworkStack 包。应用通过服务解析和已安装的系统包确定所需作用域，自动申请缺失项。仅授权 system 不代表 Android 10+ 的独立网络共享进程已授权。模块和新增作用域首次生效需要重启相应进程或手机。

适配入口：

- Android 9/旧实现：com.android.server.connectivity.tethering.UpstreamNetworkMonitor，NetworkState 六参数结构。
- 模块化实现：com.android.networkstack.tethering.UpstreamNetworkMonitor，UpstreamNetworkState 三参数结构。
- 上游选择 getCurrentPreferredUpstream / selectPreferredUpstreamType，以及 Tethering 内部 chooseUpstreamType。
- 注册 VPN 网络回调，并在共享状态机的 Handler 上处理变化。

厂商私有实现、分应用/分流 VPN、VPN 客户端自身限制、DNS 和 IPv6 的实际行为仍需按具体 ROM 验证。没有 VPN 或共享服务缺少所需 Hook 入口时不能把开关显示为开启视作链路已经验证；设置会显示框架连接/授权状态。不能仅凭修改 NetworkCapabilities 的一个标志就认定流量已经进入隧道。

本次按用户要求 **不测试 VPN 链路**，没有连接或更换用户的 VPN。随后经用户授权已启用 Vector/Zygisk Next 并完成真实框架守护验收；这不代表 VPN 数据链路已通过。已完成编译、作用域策略回归测试和开关 UI 检查；后续需在模块激活、作用域授权并重启后，用下游设备的出口 IP、DNS 和 IPv6 检查验证。

参考：[AOSP 上游选择](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/Tethering/src/com/android/networkstack/tethering/UpstreamNetworkMonitor.java)、[原生共享接口选择及 IPv6 条件](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/Tethering/src/com/android/networkstack/tethering/TetheringInterfaceUtils.java)。

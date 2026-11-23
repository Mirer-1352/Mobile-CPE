# 蜂窝载波与网络下发速率

版本 0.11.1/code33。Android 9+ 保持可安装；字段可用性由系统、基带、权限与当前数据连接决定，发布记录见 [0.11.1](RELEASE_0.11.1.md)。

## 显示

主屏顶部信息区增加两项小字，实时上下行 M/s 主块保持原布局：

- 聚合：例如 LTE 2CC、NR 2CC；同时报告 LTE 和 NR 服务载波时标注 LTE+NR 双连接，单纯双连接不会当成 2CC 聚合。
- 签约：例如 `签约 ↓125.00 ↑12.50 M/s`。这里是基带返回的 APN-AMBR 或 Session-AMBR，属于网络下发上限，不代表当前测速或一定与套餐宣传速率相同。

MCPE 设置增加「蜂窝网络」，显示各服务载波主/辅角色、B/n 频段、上下行 MHz 带宽、AMBR 类型，以及未读取到数据的原因。无法确认时显示 —，不使用 5G+/4G+ 品牌图标、邻区数量、链路带宽估计或测速来补齐。

## 采集边界

`RootRadioObserver` 直接向本机 telephony.registry 注册选定订阅的物理信道和精确数据连接回调。现代 listenWithEventList 与旧 listenForSubscriber 按实际参数签名匹配；事件常量、回调事务号及 Parcelable.Creator 从当前 ROM 读取，不猜测 Binder 事务号。

仅接收 primary/secondary serving 配置。已知制式与下行频点相同的记录去重，优先保留主载波；重叠 n77/n78 标签不会凭空增加载波。Android 9 的旧回调可保留其实际提供的角色/带宽，制式或频段缺失时不推测。

普通信号监听与 ROOT 采集均跟随实际 active data subscription；旧系统无对应接口时使用默认数据订阅。每次切换为信号监听创建新实例，以会话代号拒绝迟到事件。切 SIM、PLMN、数据制式、默认互联网 APN 或连接身份变化后清理旧 AMBR，异步结果必须仍属于同一会话才能使用。

## AMBR 适配

目前实现 MediaTek 的受限辅助 AT 通道适配：

1. 仅识别 MT 平台并使用已知 `/dev/ttyC0`，不遍历写入其他未知串口。
2. 打开前检查占用，打开后核对实际字符设备并要求 TIOCEXCL，再次检查已有持有者；检查失败、无独占能力或通道忙时退出。结束时撤销本会话独占标记并关闭。
3. 只查询 AT、ESUO?、COPS?、CGACT?、CGDCONT?。ESUO? 必须能确认此通道已绑定到当前数据 SIM，不发送 ESUO 设置命令去改变绑定。
4. 必须与框架精确数据连接事件提供的默认互联网 APN 相符，并有唯一活跃上下文；IMS、歧义连接或不明 SIM 均不取值。
5. LTE/NSA 查询 CGEQOSRDP，SA 查询 C5GQOSRDP，分别读取 APN-AMBR 与 Session-AMBR；保留 CSV 空列，不把相邻的 GBR/MBR 列当作 AMBR。0、缺失、冲突或非法值均不显示。

速率按标准 kbit/s 解析，除以 8000 换为本项目的十进制 M/s。成功约每分钟刷新，失败按原因退避；缓存最长 90 秒。通道完整操作预算 5 秒，每个响应最大 16 KiB。蜂窝采集与共享协调使用不同 ROOT 会话，基带 I/O 运行在独立线程，不阻塞三秒共享巡检，也不改变共享意图。

这不意味着所有 MTK 均已适配；不提供辅助 AT 通道、不支持独占 ioctl、通道属于其他 SIM或框架未提供精确默认 APN 时都会显示无法读取。现代 Qualcomm 的 QMI/DIAG、其他厂商专用通道尚未实现。当前没有新增 LSP Hook 或作用域。

蜂窝诊断仅保存订阅编号、槽位、制式、频段、频点、带宽、AMBR 及状态；APN/PLMN 查询文本只在 ROOT 内存中用于归属判断，不写诊断或发送给界面。不收集号码、IMSI、IMEI、Cell ID、PCI 或基带原始响应。

## 验证边界

单元测试覆盖聚合/双连接区分、重复频点与未知字段、速率单位、过期和跨订阅数据、EPS/5GS 协议、GBR/MBR 混淆、默认 APN 唯一匹配、SIM 不匹配提前终止，以及查询命令集合。

2026-11-23 最终干净构建：Debug/Release、两种 lint 通过（0 errors / 38 warnings），132 项 JVM 测试通过。R8 最终 DEX 检查确认 ROOT 与模块入口保留、177 个应用类已混淆；签名校验通过且沿用已有证书。APK 为 `dist/PhoneCPE-0.11.0.apk`，2,101,025 字节，SHA-256 `1649c714bfff261595871dc40aaed2f2a578dc1f6048693e9bd64b1f3e1fceb1`；映射和检查记录归档于 `.release/r8-0.11.0-code32/`，不上传到公开源码。

0.11.0 首次构建时未连接实机。Find X9 Pro 的辅助通道、真实 AMBR 返回及其他 ROM 的回调权限仍未验证，不能用构建与协议夹具通过替代实机结果。

### 2026-11-24 一加 9R / Android 14 实测

- 核对用户已安装的 0.11.0 与交付 APK 摘要一致，发现已插 SIM 仍返回 NO_SIM。独立 ROOT/app_process 中 TelephonyFrameworkInitializer 的服务管理器为空，导致订阅接口返回 -1、SIM 状态查询抛空指针；仅注册 telephony.registry 并不能补齐这些依赖。
- 0.11.1 在创建蜂窝采集后端时，仅为当前 ROOT 进程补齐该管理器，已有实例不重复设置；旧系统不存在此模块类时沿用原路径。此初始化不添加系统 Hook，也不修改系统全局属性或共享配置。设备探针确认初始化前后 active data subscription 从 -1 恢复为有效订阅，SIM_READY、NR 数据制式与物理信道回调正常。
- 通过应用自身 ROOT 更新器实际完成 32→33，核对安装 APK SHA-256、前台服务恢复、全部共享偏好与 system_server PID 保持不变。最终 R8 构建的应用诊断和独立 ROOT 会话均收到真实 NR 物理载波。
- 系统 telephony.registry 本身仅报告一条 PrimaryServing NR，band=0、上下行带宽=0、下行频点=2147483647。界面显示 NR 1CC，频段/带宽显示 —，与系统源值相符；本轮不能证明多载波 CA 的实机识别。
- 临时关闭 Wi-Fi 客户端连接后，上游从 wlan0 切到 rmnet_data2，热点 wlan2 始终保持开启；精确默认互联网连接就绪后 AMBR 明确返回 UNSUPPORTED（此机为 Qualcomm），没有尝试未知基带端口或填入估算值。蜂窝上游在本轮系统验证标志为 false，不将其记为互联网可用性验收。随后恢复 Wi-Fi 并确认重新关联、上游 wlan0 validated=true，偏好和 system_server 未变。
- 实际长按进入 MCPE，检查蜂窝详情；强停再打开后，SIM/NR 回调与前台守护恢复，热点和 Wi-Fi 上游保持。两个 ROOT app_process 及其 shell 包装进程在强停后全部退出，重开后恢复原数量，无遗留会话。
- Debug/Release 构建与 lint 通过（0 errors / 40 warnings），132 项 JVM 测试通过；签名与 R8 入口检查通过。最终 `dist/PhoneCPE-0.11.1.apk` 为 2,101,025 字节，SHA-256 `aec0e9b4b53e212ac23589037d2d0e2b534ce4d82089846b9d7b252f7204ac20`，映射归档 `.release/r8-0.11.1-code33/`，私有实测记录 `.device-tests/radio-oneplus9r/`。

本轮实测只操作用户新连接的一加 9R，未重启手机；实测结束时尚未发布 GitHub。没有 MTK 辅助通道、真实 AMBR、多 SIM 切换或多载波 CA 的实机验收。

参考：[Android 物理信道接口](https://developer.android.com/reference/android/telephony/PhysicalChannelConfig)、[Android 9 PhoneStateListener](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-9.0.0_r1/telephony/java/android/telephony/PhoneStateListener.java)、[TelephonyRegistry](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/services/core/java/com/android/server/TelephonyRegistry.java)、[3GPP TS 27.007 / ETSI](https://www.etsi.org/deliver/etsi_ts/127000_127099/127007/18.06.00_60/ts_127007v180600p.pdf)、[Linux ioctl 常量](https://raw.githubusercontent.com/torvalds/linux/master/include/uapi/asm-generic/ioctls.h)。辅助通道思路参考 [BeaRerM](https://github.com/Luckyji6/BeaRerM)。

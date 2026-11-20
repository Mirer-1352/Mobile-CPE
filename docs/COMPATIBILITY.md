# Android 9+ 适配审查（0.9.0）

本次审查覆盖全部功能入口、SDK 条件分支、Root 命令、Direct Boot 存储、权限变化和升级恢复。最低 API 28；lint 使用该 minSdk 检查所有调用，构建与核心逻辑测试通过。**源码/API 审查通过不等于该 Android 或厂商 ROM 已完成运行验证。**

## 系统版本分支

| 功能 | Android 9–10 / API 28–29 | Android 11–12L / API 30–32 | Android 13 / API 33 | Android 14–17 / API 34–37 |
| --- | --- | --- | --- | --- |
| Wi-Fi / USB 控制 | Root IConnectivityManager + ResultReceiver | Root TetheringManager 请求/接口回调 | 同左 | 同左，隐藏方法运行时核对 |
| 以太网共享 | 保存期望；提示执行不支持，不调用缺失 API | 原生类型 5，需 USB Host/网卡驱动 | 同左 | 同左 |
| USB NCM | 不假定有独立 NCM API | 识别 ncm 接口；关闭 USB 也尝试停止类型 4 | 同左 | 同左 |
| Wi-Fi 客户端 | SoftApCallback + Handler，数量回调 | Executor + 客户端列表回调 | 同左 | 同左，厂商/多 AP 差异待实测 |
| 运营商/信号 | READ_PHONE_STATE；9 的 dBm 可未知，10+ 读取 CellSignalStrength | 增加 TelephonyDisplayInfo NSA 标识 | 同左 | 同左，旧监听 API 仍需 ROM 支持 |
| 默认桌面 | 9 使用 HOME 设置；10+ RoleManager | RoleManager 可用性检查与 HOME 设置回退 | 同左 | 同左 |
| 前台守护 | startForegroundService + 通知 + START_STICKY | 处理后台启动限制；Direct Boot 接收器 | 通知运行时权限，拒绝不伪造通知可见性 | specialUse 类型与权限；不使用限时 dataSync |
| 常亮/防烧屏 | 应用窗口 KEEP_SCREEN_ON；Compose 整体位移，物理像素边缘预留 | 同左，RESUMED 生命周期与屏幕交互/焦点检查 | 同左 | 同左，不修改全局屏幕超时或亮度 |
| Root 自动安装 | cmd package install -r --user -S，stdin | 同左 | 同左 | 同左；适配新 shell 对裸 - 的拒绝 |
| Root 安装器存活 | app_process + Os.setsid，Root 私有副本 | 同左 | 同左 | 执行器 APK 0400，避免可写动态代码限制 |
| LSPosed | 框架需支持现代 API 102；旧框架不启用模块能力 | 同左 | 同左 | 同左；厂商入口须实际注入验证 |
| 框架守护 | AMS/SystemServer 启动、进程退出适配；五参数 AsUser 广播 | 反射已有重载，15 秒巡检兜底 | 带 EXPORTED 标志的 AsUser 接收器 | 同左；API37 独立 Root 广播注册不等同系统进程，须真实注入验证 |
| 厂商服务启动 | 可选 ColorAppStartupManager 私有服务启动适配，缺失时跳过 | 可选 OplusAppStartupManager 适配 | 同左 | 本机 API37 已实测 Oplus 适配；仅守护线程启动自有服务时生效，外层高温检查保留 |

依据：[Android 10 ConnectivityManager](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-10.0.0_r1/core/java/android/net/ConnectivityManager.java)、[Android 11 TetheringManager](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-11.0.0_r1/packages/Tethering/common/TetheringLib/src/android/net/TetheringManager.java)、[Android 9 SoftApCallback](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-9.0.0_r1/wifi/java/android/net/wifi/WifiManager.java)、[Android 9 包安装器](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-9.0.0_r1/services/core/java/com/android/server/pm/PackageManagerShellCommand.java)。

## 本次修正与安全降级

- 所有共享开关只绑定同一 OptionsStore 的持久期望，界面与守护观察同一状态流；Preferences 在应用进程中只有一个实例，OptionsStore 统一原子修改，落盘只写变更字段。硬件快照、能力缺失和失败不得改 desired。USB/网卡事件和以太网 carrier 恢复会重置相关退避，重新评估已保存的期望。

- HOME Hook 从已识别的 resolver 方法参数读取请求用户，避免把 ResolverActivity 的系统 UID 误认成用户 0；未知方法签名保留系统结果。HOME 标记按用户隔离，原生桌面选择与手势逻辑独立。
- 升级恢复与 boot 解耦：包替换后只按 enabled 恢复；关闭 boot 不阻断升级恢复，暂停 enabled 不被自动开启。Direct Boot 仍只读取设备加密共享配置。
- 自动更新由服务驱动，不依赖桌面 Activity。CE 更新数据在首次解锁前不读；成功每 6 小时检查、失败 15 分钟重试。多用户按实际 UID 所属用户安装和恢复，不硬编码 user 0。
- Android 的同包 APK 代码由系统在用户间共享；指定发起用户不代表其他用户能够保留同包的旧 APK。多用户实际运行仍待验收。
- 安装前应用和 Root 均验 size、hash、package、code、当前 signer，另核对 minSdk。自有 Root 逻辑为 Java/dex；APK 中 AndroidX graphics.path 原生库已确认同时打包 arm64-v8a、armeabi-v7a、x86、x86_64，未设置本机 ABI 专用过滤。其他 ABI 不宣称支持；非 ARM64 运行仍待验证。该项目不支持拆分 APK/多 APK Release。
- 安装执行器调用 Os.setsid 自建会话，不依赖第三方 su 的后台 shell 生命周期；原应用被替换后仍能完成安装。Root 状态原子保存，安装与恢复结果区分，临时目录只删除自身固定文件。
- 蜂窝权限撤销、无蜂窝硬件或 SIM 监听失败时清掉旧信号和制式；失败后允许重绑。5G SA 优先于增强 NSA 标识，避免 SA 被误标 NSA。蜂窝异常不再使整个共享监控协程结束。
- 旧版 CM 的 mService 字段向父类查找，避免厂商子类导致找不到字段。现代服务不存在时明确报不支持，客户端数量不可用则未知，不从 ARP 残留猜测。
- LSPosed 初始化异常不会杀掉 Application/共享服务；作用域解析失败可回退已安装系统网络包列表。授权和实际 Hook 注入仍分别对待。
- 速率继续只计共享下游并去重桥成员；硬件/eBPF 卸载误差仍存在，不宣称计费级精度。未知/计数重置保持未知，不虚构零速率。
- 日期按设备时区更新，后台暂停；窄屏/大字体可让日期和时钟分两行。速度字体与行高一起按空间调整，横屏空间不足省去辅助 dBm/守护文字。
- APK/元数据失败、Root 拒绝、SELinux、企业安装限制均显示或记录真实错误；没有关闭系统安全策略，也没有包名猜测关闭厂商组件。

## 证据与待验收

| 范围 | 当前证据 | 仍需对应环境 |
| --- | --- | --- |
| Root 更新及进程替换 | OnePlus 8T/API37，前台 14→15、后台 15→16 和重构版独立 ROOT 21→22 实际覆盖、恢复及原配置验证 | Magisk 以外 Root 方案、其他 Android 与厂商系统 |
| 开机/断线恢复 | 同机真实重启、Wi-Fi/USB；升级恢复与 boot=false；API37 真实 system_server 强停 5.12秒/冻结 103.52秒恢复和暂停 60秒验收 | 其他 Android/ROM 的框架入口、启动限制及 Direct Boot 差异仍需运行验证 |
| Wi-Fi/USB/以太网 | 同机 Wi-Fi/USB；USB 状态颜色切换 | Wi-Fi 真实多客户端、双频/多 AP、USB 网卡、物理重插 |
| 屏幕/交互 | 同机横竖屏、日期时钟、原桌面手势、普通与 200% 字体检查 | 平板、折叠屏、OEM 桌面限制及系统手势差异 |
| 双 SIM/NSA/SA | 版本保护与源码审查，现机 SA 显示 | 真双卡切换、NSA/SA 切换和 SIM 热插拔 |
| LSPosed HOME/VPN | API102 作用域策略单测，框架和网络共享进程已实际加载模块；系统守护已实测 | HOME 接管与 VPN 上游功能仍未作实际链路验收；VPN 按用户要求未连接测试 |
| 更新网络源 | 信任与回退单测、发布器本地 Git 集成测试；缓存包到 Root 安装实测 | 首个真实 Release、多 CDN 健康筛选和网络下载 |
| Android 9–16 运行 | minSdk28 lint、AOSP API/命令审查 | 当前没有可运行的这些系统镜像/设备；旧 API28 AVD 配置残留但镜像与 emulator 不在现 SDK，不能记为实测 |

主机另有平板接入，本次未擅自安装或改变其设置。无任何证据可支持「全部 ROM 已兼容」的承诺；矩阵保留具体待验收项。


0.9.0 / code23 Release: see [RELEASE_0.9.0.md](RELEASE_0.9.0.md) for final APK identity, USB/Ethernet guards, 86 passing tests, Root upgrade evidence and the unconfirmed Find X9 Pro issue.

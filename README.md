# 手机CPE / AutoHotspot

基于 Root 的 Android 随身路由器，原生 Kotlin + Jetpack Compose。最低 Android 9（API 28），以太网输出需要 Android 11+ 的原生 Tethering 服务与 USB 网卡驱动。本项目不承诺所有厂商 ROM 通用；下面的设备验证仍需在真实 Root 手机上执行。

[下载 0.9.0](https://github.com/daxiaamu/Mobile-CPE/releases/tag/v0.9.0)。发布包为非调试 Release 变体，沿用现有开发签名，可覆盖此前测试安装。当前为公开测试阶段。

## 功能

- Wi-Fi 热点、USB 网络共享、以太网共享可分别选择，并保存到设备加密存储。
- 开关只表示持久化的用户期望；实际共享状态只影响图标和状态文字。没有网卡、未接线、共享失败或重开面板均不取消选择，设备重新接入时重新尝试。
- 前台守护每 3 秒读取实际共享接口；开机恢复、断线重试可配置。失败按 3/6/12/24/48/60 秒退避，正常开启后清零。
- 默认开启系统框架守护：现代 API 102 Hook system_server 启动和进程退出，结合心跳、巡检与限速恢复共享服务。需启用模块、批准 system 作用域并重启；连接状态按实际框架确认显示，见 [守护说明](docs/FRAMEWORK_GUARDIAN.md)。
- USB 线断开时保留用户意图，重新接入后重开。USB 网卡掉线时重试以太网共享，由系统管理 DHCP、NAT 和上游，不硬编码 iptables 或 Binder 事务号。
- 全屏大字显示上下行共享速率，固定 M/s、两位小数（1 M/s = 1,000,000 字节/秒）。顶部显示运营商、无线制式、信号和 Wi-Fi 客户端数量；未知值显示「—」。
- HOME 桌面入口，支持系统默认桌面选择；内置可选 LSPosed HOME 解析适配。
- 顶部显示状态，中间显示速率，底部横排 Wi-Fi、USB、以太网图标开关；无 APP 标题、副标题和设置文字入口。长按进入 MCPE 设置，侧边返回回仪表，底部上划打开原桌面。竖屏上下排列速率，横屏左右排列。
- 最顶部显示日期、星期和实时秒钟；底部图标默认白色，Wi-Fi 有客户端时变绿，USB/以太网以实际共享开启状态变绿。
- MCPE 设置提供「屏幕常亮」和默认开启的「防烧屏位移」：参考 mijiapanel，每 3 分钟在 ±16 物理像素范围轮换位置，800 毫秒过渡；面板整体移动并预留边缘，后台暂停，关闭复位。常亮只作用于本应用窗口。
- VPN 共享开关默认开启，可在设置关闭；通过现代 LSPosed 适配原生共享上游，见 [接入说明](docs/VPN_SHARING.md)。
- Root 全自动更新连接 `daxiaamu/Mobile-CPE`：后台定时检查、stable/beta、多源下载、APK 哈希/身份校验与独立进程覆盖安装，升级后恢复共享。无需确认安装或未知来源授权。设置可检查与查看 Markdown 说明；发布工作流和首发步骤见 [更新说明](docs/APP_UPDATES.md)。
- 本地诊断记录最多 30 条，不包含热点密码，不提供网络监听端口。

## 使用

1. 安装 APK，打开一次；在系统网络设置中配置热点名称、密码和频段。
2. 首次打开默认自动启用守护和 Wi-Fi 热点，在 Magisk / KernelSU / APatch 中给予 Root 授权。长按主显示区可更改共享方式。授权弹窗有超时，超时后会重试。
3. 授予电话状态权限以显示运营商和信号；Android 13+ 可授予通知权限。未授权不影响 Root 共享控制。
4. 「开机自动共享」默认开启；允许 ROM 自启动和后台运行，并配置电池优化。用户手动关闭的选项在升级时保留。
5. 可选择设为默认桌面，面板默认保持亮屏。以太网指通过手机 USB 网卡向外提供网络，USB 共享指手机作为 USB 网络设备连接电脑，两者需要相应线材和设备角色，单个 USB 端口通常不能同时承担两种角色。

开关表示希望守护的共享方式，下方文字表示已读取的实际状态。关闭开关会排队请求停止该共享；暂停守护只停止监控，保留系统当前共享。停用自动恢复后，每次新增选择仅尝试一次，重新开关对应选择可再次尝试。

## LSPosed 桌面接管

仅在系统桌面选择受限时使用。模块已迁移到正式发布的 libxposed API 102.0.0 / Service 102.0.0，最低需要框架支持 API 102；不再打包旧版 API 82 或 `assets/xposed_init`。Android 9+ 的 Root 共享功能不依赖模块激活。

在 LSPosed 中启用「手机CPE」后返回应用。服务绑定及应用回到前台时，通过 `getScope()` 同步实际作用域；缺少目标时自动调用 `requestScope()`。现代 API 中系统进程目标是 **`system`**，不是 `android`（Android 系统的普通进程）。0.5.0 还会发现并申请设备实际使用的 Tethering/NetworkStack 包，`staticScope=false` 允许动态申请。

管理器仍需确认授权。拒绝/失败/等待超过 60 秒后不会反复自动弹窗，可在设置点击「同步并申请作用域」。服务断开会清除旧状态，重连后重新检查；应用在后台只同步，不发起申请。作用域已授权不代表 Hook 已注入，首次启用或作用域变更后请重启手机。系统进程不启用自动热重载。

随后打开「LSPosed 桌面接管」。Root 同步器按单调版本顺序应用配置；接管标记保存在 `/data/system/mobile-cpe-home/<user>.enabled`，仅按 HOME 请求所属用户生效。用户 0 的旧 `/data/system/autohotspot-home` 在迁移前仍可读取。Hook 在 `onSystemServerStarting()` 注册，使用现代拦截链，仅改写无显式目标的 MAIN + HOME 解析结果，适配失败时保留原结果。

已提供 Android 9/10 PackageManagerService、较新系统 ComputerEngine / ResolveIntentHelper 的 AOSP 入口。厂商私有启动策略仍可能覆盖结果，**模块开关成功不表示模块已激活或厂商限制已解除**，须按 Home 实测。没有使用包名猜测去关闭厂商安全组件。

恢复：关闭应用中的接管开关；或在 LSPosed 停用模块并重启；或删除对应用户的 `/data/system/mobile-cpe-home/<user>.enabled`（用户 0 同时检查旧 `/data/system/autohotspot-home`）。卸载前建议先关闭接管。

## 构建

JDK 17+，Android SDK Platform 37，Gradle 9.4.1。Android Studio 打开根目录，配置 SDK；或设置 ANDROID_HOME：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。debug 使用开发签名，正式发行请自行配置私有签名；项目不包含签名密钥。Gradle 从 Google Maven、Maven Central 获取依赖，Google 与 Central 配有阿里云镜像用于网络受限环境。

Windows 用户目录含中文时，如果 Gradle Test Executor 无法找到 GradleWorkerMain，可用 `powershell -ExecutionPolicy Bypass -File scripts/Test-Core.ps1` 编译并直接执行相同的 JUnit 测试，绕过 Gradle worker 参数文件的路径编码问题。

## 技术边界

- Root 进程从当前 APK 的 dex 启动，通过父进程 stdin/stdout JSON 通信；隐藏 API 豁免仅作用于该 Root 进程。Android 9/10 使用 IConnectivityManager 的 ResultReceiver，Android 11+ 使用 TetheringManager 的 TetheringRequest 和回调。请求成功后仍以 `getTetheredIfaces` 确认实际状态。
- Root 或 SELinux 策略可能拒绝 app_process / Binder 调用，界面保留错误；不会全局关闭 SELinux、关闭隐藏 API 策略或修改运营商鉴权。系统要求的共享资费/鉴权仍然有效。
- Android 9/10 缺少本项目使用的原生以太网共享接口；仍允许保存以太网期望选择并提示当前系统不支持，不调用缺失 API，不将静态 NAT 脚本伪装成通用兼容。
- SoftApCallback 由 Root 注册，若 ROM 不支持则数量未知，不用 ARP 表猜测设备数量。多 AP 的新回调、厂商回调及双频并发热点需真机验证。
- 下载为共享接口 TX，上传为 RX；这是共享客户端方向，不是手机所有 App 的总流量。硬件 / eBPF 卸载可绕过接口计数，导致低估，未实现全 ROM 精确计费统计。
- 5G NSA 取系统 TelephonyDisplayInfo 标识，SA 取 NR 类型；信号取当前数据订阅。信号不代表公网连通性。Android 9 的 NSA 显示依赖厂商实现，可能只能报告 4G。
- 前台服务使用 specialUse 类型，不使用有时长上限的 dataSync；持有有超时并续期的部分唤醒锁，保证息屏检查，会增加耗电。框架注入后可从外部恢复自有共享服务；未注入时，强行停止后仍可能需要重新打开应用。开机默认不主动拉起 Activity；使用默认桌面显示面板。

## 真机验收

见 [docs/DEVICE_TESTS.md](docs/DEVICE_TESTS.md)。单元测试只能验证计数和重试算法，不能替代真实 Root / USB / 热点 / 开机 / LSPosed 测试。

全功能 Android 9+ 适配审查、版本分支、修正和待验收项见 [COMPATIBILITY.md](docs/COMPATIBILITY.md)。本机实测不代表全部 ROM 已通过。

已在 OnePlus 8T 完成 Wi-Fi、USB 共享、外部关闭后恢复、USB 实际外网访问与开机恢复验证；0.9.0 重构后再次完成真实 system_server 注入、强停/冻结恢复和暂停不恢复验收，详见 [实测结果](docs/DEVICE_RESULTS.md)。以太网硬件、LSPosed HOME/VPN 功能链路和其他 Android/ROM 仍待验证。

全代码审查发现、职责边界与本次验证见 [CODE_REVIEW.md](docs/CODE_REVIEW.md)。

## API 参考

- [Android TetheringManager](https://developer.android.com/reference/android/net/TetheringManager)
- [AOSP TetheringManager 实现](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/Tethering/common/TetheringLib/src/android/net/TetheringManager.java)
- [Android 9 ConnectivityManager](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r3/core/java/android/net/ConnectivityManager.java)
- [现代 libxposed API 102 与 system 作用域](https://libxposed.github.io/api/io/github/libxposed/api/package-summary.html)
- [libxposed Service](https://github.com/libxposed/service/releases/tag/102.0.0)

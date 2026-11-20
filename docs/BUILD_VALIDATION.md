# 本次构建验证

2026-11-20，Windows，JDK 24，Android SDK Platform 37，Gradle 9.4.1。

- `:app:assembleDebug`：通过，生成 debug APK。
- `:app:lintDebug`：通过，0 个错误；保留隐藏 API、版本更新等警告。Root 依赖非公开共享接口，需随 Android / ROM 变化验证。
- `scripts/Test-Core.ps1 -SkipCompile`：JUnit 12 项测试通过，覆盖原有 5 项流量/退避测试及 7 项作用域策略测试，包括 system 与 android 区分、前后台门禁、防并发、拒绝后手动重试、服务重连和授权撤销。
- Gradle `testDebugUnitTest`：本机 worker 进程因中文路径编码无法加载 GradleWorkerMain；并非测试断言失败，使用仓库提供的直接 JUnit 入口完成相同测试。
- 0.1.0/0.2.0 当时未安装到 ADB 设备，未切换设备网络、默认桌面或 LSPosed 设置；0.3.0 的真机实测见下文。
- 编译与单机验证不能视为跨 ROM 兼容。请继续执行 DEVICE_TESTS.md 中未验证的项目。

当前开发 APK：`dist/PhoneCPE-0.5.0-debug.apk`；构建原始产物：`app/build/outputs/apk/debug/app-debug.apk`。

## 0.2.0 现代 Xposed API 迁移

- Maven Central 元数据核对：API 最新正式版 102.0.0，Service 最新正式版 102.0.0；上游 master 的 Service 102.0.1 尚未发布，本项目使用正式版依赖。
- `:app:assembleDebug :app:compileDebugUnitTestKotlin :app:bundleDebugClassesToRuntimeJar :app:lintDebug`：通过，Lint 0 错误、16 警告。
- APK 解包确认：包含 `META-INF/xposed/java_init.list`、`module.prop`、`scope.list`，目标为 `system`，最低和目标 API 均为 102；不含旧 `assets/xposed_init`。
- `apksigner verify --verbose`：通过，APK v2 签名有效。
- SHA256：`D25254F38AEAE7AA36C35756A00FF0C31EBC39DF80E5CAC1F08F87D167801F86`。
- 作用域申请的管理器 UI、Binder 断连/重连、system_server 注入及 HOME 接管尚需真机验证。

## 0.3.0 界面与真机修复

- 共享开关移至设置；增加 Root 的常见绝对路径兼容和退出原因诊断，修复手机布局细节。
- assembleDebug、lintDebug 通过；直接 JUnit 12 项测试通过；APK 签名校验通过。
- SHA256：`3CF46B6A590B135419B74E100E1D144C7E9878ED2FA4FFA780475A7EA9CF9FD2`。
- 已在连接的 OnePlus 8T 原位安装，并验证 Wi-Fi/USB 共享、实际网络转发、关闭后恢复、一次重启恢复和界面布局。详见 [DEVICE_RESULTS.md](DEVICE_RESULTS.md)。

## 0.4.0 全屏仪表

- 上下行速率是主显示区，固定 M/s 两位小数；移除首页 APP 标题、副标题、按钮和设置入口文字。
- 长按进入设置，系统侧边返回回仪表，无横向翻屏手势。竖屏双行、横屏双列；设置与诊断保留在设置页。
- assembleDebug、lintDebug、APK 签名校验通过。
- OnePlus 8T 真机验证：普通点击不跳转；长按进入设置；实际侧边返回手势回到仪表；横竖屏读数不截断；两个 M/s 标签保持固定；旋转测试结束后恢复原来的 free 模式。
- SHA256：`1132A8BCA352C95F92B4A115631FD33C8476CB5C95DB20CB79592FDCEEBEB021`。
- 实机截图：`dist/AutoHotspot-0.4.0-screen.png`。

## 0.4.1 文案精简

- 移除共享接口信息和常规解释段落，设置保留名称、开关、必要状态和操作入口。
- assembleDebug、lintDebug 通过；已更新到 OnePlus 8T。

## 0.4.2 默认自动共享

- 新配置默认开启守护、开机恢复与 Wi-Fi，首次打开自动请求 Root 并运行；已保存的明确选择不被默认值覆盖。
- assembleDebug、lintDebug 通过。
- OnePlus 8T 上暂存原配置，验证空配置的 Wi-Fi、守护、开机共享均开启，前台服务自行运行；恢复原配置后确认保存的关闭选择仍保留。
- 按本次用户要求，将测试手机的开机自动共享实际设为开启，其余原配置保留。

## 0.4.3 顶部信息区

- 运营商、信号、共享状态和设备数量整体移至顶部，下面保留上下行大字读数。
- assembleDebug 通过；已安装到 OnePlus 8T，并检查信息区位于速率区上方。

## 0.4.4 设置标题

- 设置页左上角标题改为 MCPE。
- assembleDebug 通过，已在 OnePlus 8T 安装并验证标题。

## 0.4.5 流量去重

- 保持仅统计共享出口、上下行分开的口径；补充同名接口及桥接父子接口去重。
- assembleDebug、lintDebug 通过，17 项 JUnit 测试通过，已更新手机。
- USB 双向各 1 MiB 的校验传输未出现加倍，详见 [TRAFFIC_ACCOUNTING.md](TRAFFIC_ACCOUNTING.md)。

## 0.5.0 快捷开关、原桌面和 VPN 接入

- 名称改为手机CPE；底部增加三组图标开关，压缩网速区占用；长按入口和固定 M/s 保留。
- 原桌面保存为独立的显式 Activity 目标，不切换默认 HOME。已在 OnePlus 8T 验证底部上划区域、系统最底边上划均打开 com.android.launcher，再按 Home 返回 CPE。
- HOME 手势兼容结合前台状态和短暂生命周期窗口，首次启动仍显示 CPE；其他 ROM 的手势需验证。
- VPN 上游接入和 RemotePreferences 开关同步已实现，作用域增加实际 Tethering/NetworkStack 包。按用户要求本次不测 VPN 链路，也未启用当前禁用的 Vector。
- 19 项核心测试通过（含新增的多作用域授权策略）。界面、名称、设置开关已在手机上检查。

## 0.6.0 GitHub 更新接入

- `assembleDebug`、`compileDebugUnitTestKotlin`、`bundleDebugClassesToRuntimeJar`、`lintDebug` 通过；lint 为 0 errors / 29 warnings（包括已有隐藏 API、版本建议与应用级 Context/KTX 建议）。
- `scripts/Test-Core.ps1 -SkipCompile`：37 项 JVM 测试通过；新增 18 项覆盖共享检查会话、清单解析、权威/镜像信任、冲突/回退、过期指针、安装前文件重验与 APK 身份规则。
- `python -m unittest discover -s scripts -p test_update_metadata.py -v`：11 项通过，含真实本地 bare Git 的发布过程，验证失败不推进远端及成功清单的固定 commit/摘要一致性；网络与 APK 解析工具在该集成夹具中模拟。
- `apksigner verify --print-certs dist/PhoneCPE-0.6.0-debug.apk` 通过，保持原开发签名。证书 SHA-256：`4929d9e532d1bd29997e2e49672eab3c51c4c171fa5a8620187e373276dc315e`。
- 产物：`dist/PhoneCPE-0.6.0-debug.apk`，SHA-256：`65709b17a65e1bd6e497eb7c0892777468a8ff9965251df4fdb6f30a2b7c7e7d`。
- 更新配置、Actions 和首发步骤见 [APP_UPDATES.md](APP_UPDATES.md)。线上仓库尚无 Release；没有把模拟 CDN、夹具清单发布到 GitHub，也没有执行真实 Release 发布流程。

## 0.6.1 状态颜色与日期时钟

- `assembleDebug`、`lintDebug` 通过，0 errors / 29 warnings。纯显示修改，没有新增或重复运行核心逻辑单元测试。
- 最顶部新增设备本地时区的日期、中文星期与 HH:mm:ss，每秒更新，进入后台停止刷新；横屏合并运营商与共享状态行，保证速度数字不截断。
- 图标默认白色，Wi-Fi 需要状态新鲜、共享实际开启且客户端数大于零才变绿；USB/以太网按状态新鲜且对应共享实际开启变绿，开关意图不触发绿色。
- 手机像素检查确认三图标初始为白色、实际 USB 开启后绿色、关闭后恢复白色；确认日期星期、时钟更新、横屏速度完整。USB 重新枚举时重启 ADB 服务恢复连接。
- 产物 `dist/PhoneCPE-0.6.1-debug.apk`，SHA-256：`2aeb8d5cea4bc3c1f5f0db0858ee0f743095da7534215292d1fee66b121cee50`。

## 0.7.3 Root 全自动更新及全功能适配审查

- `assembleDebug`、`compileDebugUnitTestKotlin`、`bundleDebugClassesToRuntimeJar`、`lintDebug` 通过；lint 0 errors / 28 warnings。
- 45 项 JVM 测试通过，新增升级恢复/暂停策略、自动检查周期窗口和安装命令标准输入参数回归。11 项 Python 发布脚本测试通过。
- 实际 Root 14→15 覆盖安装及无 Activity 的 15→16 自动安装测试通过，见 DEVICE_RESULTS.md。后者使用本地可信缓存夹具，不是远端发布下载测试。
- minSdk28 全项目 API 检查，核对 AOSP Android 9/10/11 共享、客户端回调和安装命令；全功能版本矩阵及未实测项见 COMPATIBILITY.md。主机只有旧 API28 AVD 配置，没有可用镜像与 emulator，不虚报模拟器结果。
- 最终 0.7.3 / code16 已经由应用后台 Root 自动安装到 OnePlus 8T。调试签名验证通过；APK `dist/PhoneCPE-0.7.3-debug.apk`，SHA-256：`4940c8542e9e950760541de3253f5e98f2207c24da0c055e39b31e147caea79d`。

## 0.7.4 用户期望状态持久化

- assembleDebug、lintDebug、测试编译通过，lint 0 errors / 28 warnings；52 项 JVM 测试通过。
- 新增 OptionsStore 重启读取、失败写入、并发配置修改、共享状态流、重新启用取消待停和以太网未共享前 carrier 识别测试。
- 实机连续三次 force-stop 后重开，横竖屏重建及设置页均保留 USB/以太网开启；无网卡时以太网开关仍开启、图标白色。最终保留用户 USB/以太网开启，不恢复成旧测试基线。
- 产物 `dist/PhoneCPE-0.7.4-debug.apk`，SHA-256：`03c7b1f51c413489c3eb4e1daaab511561e20d5397289fb3bb628c719bd29879`。

## 0.8.0 系统框架守护

- assembleDebug、测试编译、bundleDebugClassesToRuntimeJar、lintDebug 通过；lint 0 errors / 33 warnings。62 项 JVM 测试通过，新增 10 项覆盖开机门控、手动启动、暂停、心跳、死亡恢复、启动宽限、升级与用户状态、退避限速、旧配置拒绝与安装身份。
- 使用现代 API102，框架启动/进程退出 Hook 的工作均投递到独立线程；跨进程策略采用文件锁、AtomicFile 和递增版本。目标 Android 9+，具体接口与验证边界见 FRAMEWORK_GUARDIAN.md。
- 0.8.0/code18 已同签名原位安装到 OnePlus 8T。MCPE 设置已显示系统框架守护，当前框架禁用时准确显示 LSPosed 未连接，未伪报注入；共享前台服务正常，三个共享期望均为 true。
- APK 签名验证通过；产物 `dist/PhoneCPE-0.8.0-debug.apk`，SHA-256：`3385fc3fe2f9ba4aa108cf255369b4e6dac4c478318707641717afa7656e0980`。没有发布到 GitHub。
- Vector 仍禁用，真实 system_server 注入、强停拉起、卡死恢复和框架开机恢复尚待实测。独立 Root 进程试验遭遇 API37 广播注册 ProcessRecord 限制，未计作通过，实验入口已移除。

## 0.8.1 常亮与防烧屏

- assembleDebug、测试编译、bundleDebugClassesToRuntimeJar、lintDebug 通过；lint 0 errors / 33 warnings。64 项 JVM 测试通过；新增位移循环覆盖与不重复测试，验证 81 个位置全部访问、物理像素范围、步长与循环边界不重复。
- 设置「屏幕」提供常亮与防烧屏开关，保持既有 screen 选择，新增 burnInProtection 默认 true。参考本地 mijiapanel，完整周期 180 秒、过渡 800 毫秒、±16 物理像素；实现说明见 DISPLAY_PROTECTION.md。
- 已在 OnePlus 8T 测试常亮开关实际添加/撤销 KEEP_SCREEN_ON，防烧屏配置落盘；真实等待三分钟，图标整体按网格移动，恢复设置后原位显示。共享意图保持不变。
- 最终包 0.8.1/code19，同开发签名验证通过；`dist/PhoneCPE-0.8.1-debug.apk`，SHA-256：`b46c9b7f2a5a97491441bc9a8278496387a7a7cfbd8133794fdcdec816d3e3ea`。未发布 GitHub。
- 最终包再次真实等待 180 秒，整体偏移 (0,-8) 像素；回原桌面再返回时遇到系统自动横转竖，按返回方向重新校准后确认位移复位。关闭/重新开启防烧屏及期望保持检查通过，详见 DEVICE_RESULTS.md。

## 0.8.2 真实框架守护与厂商启动兼容

- 用户明确授权启用 Vector 并重启后，启用现有 Vector 2.2/API102 及 Zygisk Next 依赖，通过框架 CLI 启用手机CPE、批准 system 与 com.android.networkstack.tethering，其他模块启用状态保持原值。
- 实际发现 OplusAppStartupManager 拦截 system UID 发起的后台服务启动并返回空；增加限定在守护线程、自有明确组件、已通过恢复策略内的私有服务启动 Hook。外层高温检查保留，其他包和普通启动不改写。另补充自身 stopped 状态清理、系统调用回退和持久的最近恢复记录。
- assembleDebug、测试编译、bundleDebugClassesToRuntimeJar、lintDebug 通过；64 项 JVM 测试通过，lint 0 errors / 36 warnings（新增内部 API 反射警告，与模块能力相关）。
- 最终 OnePlus 8T 实测：真实 system_server PID2294 确认；强停 6079→10801，5.12 秒；冻结 10801→12514，103.52 秒，明确 RESTART_HUNG；暂停 60 秒无共享服务和无新恢复动作；全程系统 PID 不变。
- 测试前台改为普通系统设置页，结合 lastRecovery、调用者、服务和心跳排除默认 HOME 自启的假阳性。曾经只检查 PID 的中间结果不计作通过。
- MCPE 实际显示「系统框架守护已连接」；核对测试前后共享、守护、boot、recover、VPN、亮屏、防烧屏和原桌面原值一致。
- `dist/PhoneCPE-0.8.2-debug.apk`，SHA-256：`a78309ac3eed74df106a1b2c520c1bca05010c79730b7ee6b046445ce396d382`；签名验证通过，已与手机实际 APK 摘要一致。未发布 GitHub。


## 0.9.0 全代码审查与职责重构

- 重构内容和发现见 CODE_REVIEW.md。MainActivity 从 473 行降到 152 行；界面、状态/偏好、共享协调、ROOT 会话、框架平台边界独立。删除旧 HomeHook 混合入口及未验证的额外 AIDL 启动分支。
- `assembleDebug`、`compileDebugUnitTestKotlin`、`bundleDebugClassesToRuntimeJar`、`lintDebug` 最终通过。lint 0 errors / 31 warnings；保留必要的隐藏 API 和依赖/风格建议，未全局跳过检查。
- `scripts/Test-Core.ps1 -SkipCompile` 自动发现测试类并执行 77 项 JVM 测试，全部通过；11 项 Python 更新发布器测试通过。Gradle worker 中文路径问题继续由直接 JUnit 入口规避。
- 三组关键回归：共享关闭和挂起请求的最新意图检查；ROOT 业务拒绝/通信失败/超时/取消/关闭边界；HOME 重建与更新取消传播。既有流量去重、作用域、配置存储、守护限速和更新信任测试继续通过。
- 真机完成重构候选版强停/冻结/暂停、候选版到最终版的独立 ROOT 覆盖安装，以及最终 APK 重启/息屏恢复/HOME 旋转/USB 实际开关验证。详细数值见 DEVICE_RESULTS.md。
- 最终 0.9.0/code22，同开发签名校验通过，证书 SHA-256 `4929d9e532d1bd29997e2e49672eab3c51c4c171fa5a8620187e373276dc315e`。APK SHA-256 `d6fd06f6dc2d21b66600345b0d0f49423474626587d8af921f5dba9de913c72e`，手机当前安装文件与交付文件一致。未发布 GitHub。

- 最终包额外完成常亮开关、真实 180 秒位移 (4,4)、生命周期复位和底部上划原桌面；没有变更其他共享/屏幕原选择。所有代码检查与设备回归至此完成。


0.9.0 / code23 Release: see [RELEASE_0.9.0.md](RELEASE_0.9.0.md) for final APK identity, USB/Ethernet guards, 86 passing tests, Root upgrade evidence and the unconfirmed Find X9 Pro issue.

# 0.9.0 全代码审查与重构

范围：全部 Kotlin/Java 生产代码、核心测试、Manifest、模块入口/作用域、Gradle 构建、更新发布脚本和工作流。以 0.8.2 为基线，保留既有配置键、框架配置 schema 1 与开发签名；没有改为另一套 UI，也没有发布 GitHub 版本。

## 修正的问题

| 问题 | 本次处理 |
| --- | --- |
| Activity 同时负责展示、共享、ROOT 设置和异步 HOME 写入 | MainActivity 只保留生命周期和用户操作边界；RouterDisplay / SettingsScreen 独立呈现；RootSettingsSync 单队列应用最新配置 |
| 配置封装重复，共享状态和设置职责混在 RouterState | 应用进程只持有一个 Preferences / OptionsStore；模型、存储、计数分别归位；仅持久化成功后发布状态 |
| ROOT 有效拒绝和通信中断被混为一谈 | RootOperationException 保留健康通道；RootChannel 独占每次会话的输入、输出、读线程和取消清理，连接损坏才重建 |
| 旧读任务可能跨会话消费回复，关闭时等待读锁 | 读任务仅捕获本会话，关闭底层管道并销毁进程；请求串行，取消/超时可重新连接，关闭后禁止重启 |
| 共享关闭确认依赖旧快照，可能漏停正在异步启动的共享 | SharingReconciler 分开用户期望、观察、提交和完成；先实际提交停止，再由后续快照确认；每个挂起调用前重新检查意图 |
| 待关闭操作沿用开启失败的退避，关掉开关也可能恢复总守护 | 新停止请求重置对应退避；暂停状态下取消单项选择不会擅自启动守护 |
| 框架控制、反射、文件和多组并行 Map 交织 | GuardianHooks / GuardianRuntime / GuardianEngine / GuardianPlatform / GuardianFiles 各负责入口、调度、策略、系统接口和存储；每个用户一个 Session |
| 存在未验证的额外系统服务启动回退 | 删除额外 AIDL 启动路径；保留标准 AsUser 与已实测、严格限定的 Oplus 服务启动适配；失败保留真实原因 |
| AtomicFile 在旧系统读备份时也会修改文件 | 共用 AtomicJsonFile，读写均加进程内互斥与跨进程锁；保留旧策略锁文件名，保证新旧模块跨 APK 升级协作 |
| HOME 标记全局保存；从解析结果的系统 UID 猜测用户有歧义 | 按用户保存标记，并从已识别 resolver 签名读取目标 userId；未知签名保持原结果；兼容用户 0 旧标记迁移 |
| HOME Intent 在旋转重建后可能被误判为返回原桌面的手势 | HomeNavigationPolicy 明确排除 Activity 重建；原桌面入口仍使用显式组件 |
| VPN 观察者与短命 monitor 逐个绑定，可能累计 | 每个目标进程一个网络观察者，控制器弱引用；配置同步串行，读取失败保留最近选择；仅成功安装的 Hook 记入集合 |
| 更新任务准备可并发，状态读取和写入没有共同边界 | 每用户准备锁串行安装交接；状态复用 AtomicJsonFile；安装输入完成后再关闭线程池，恢复时重读目标用户 enabled 并核对命令结果 |
| 下载取消被当作坏源继续回退，阶段超时可能不断重置 | 取消继续向上传播；跳转、请求和读取共用总时限，失败结果使用明确的 Result 类型 |

## 职责和约束

- 用户意图只有一个写入口。硬件不可用、ROOT 失败、应用重开、接口状态和框架重启均不能清掉 desired。
- 共享协调器是可测试的纯逻辑，HotspotService 负责执行和观察。Wi-Fi/USB/以太网独立维护，异步执行不使用旧 UI 状态覆盖新选择。
- 框架只拉起自己的 HotspotService，不直接操作共享选项；Binder、文件和限时恢复唤醒锁在独立线程执行，不进入 AMS 锁。
- ROOT 配置由独立 actor 同步，使用单调 revision 拒绝迟到写入；普通屏幕/共享改动不再无意义地递增框架版本。
- ROOT 请求按职责惰性初始化：配置、HOME 和更新不依赖 Tethering 服务或热点回调可用。
- Android 隐藏 API 集中在平台边界；API 28–29 与 30+ 的共享分支继续保留，不用私有静态 NAT 脚本扩大兼容承诺。
- 主屏继续为嵌入式桌面：无应用标题、长按设置、固定 M/s、日期时钟在上、图标按实际状态变色，常亮和防烧屏只作用于本窗口。

## 验证

- 最终构建、测试编译、运行时类打包、lint 通过；77 项 JVM 测试、11 项 Python 发布器测试通过。
- 新增回归覆盖共享关闭/恢复时序、暂停选择、ROOT 拒绝复用、取消、超时重连、终止关闭、HOME 重建和更新取消传播。测试入口自动发现测试类，避免漏跑新增测试。
- lint：0 errors / 31 warnings。剩余主要为必要的系统隐藏接口、依赖版本建议、应用级 Context 与 KTX 风格建议；未全局忽略检查。旧版 Tethering 服务名常量的 InlinedApi 仅在已保护的适配方法内标注原因。
- 重构版在 OnePlus 8T / API37、Vector 2.2/API102 中实际注入 system_server；再次执行强停、冻结、暂停与 ROOT 覆盖安装。最终版另通过息屏恢复、真实 HOME 旋转、USB 实际状态、180 秒防烧屏位移/复位和底部上划原桌面。具体数值、候选版/最终版区别和产物摘要见 DEVICE_RESULTS.md / BUILD_VALIDATION.md。
- 仅操作授权的 OnePlus，平板未操作。所有共享期望、原桌面、VPN 与屏幕选择保留；测试不恢复整份旧配置。

Android 9–16、其他厂商 ROM、多用户实际切换、USB 以太网硬件、Wi-Fi 多客户端以及 VPN 上游出口仍需相应环境验证。VPN 按用户要求未连接；源码审查和单测不替代这些实测。框架代码覆盖后仍需重启系统进程才能加载新 Hook。

HOME 参数适配核对了 [Android 9 PMS](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-9.0.0_r1/services/core/java/com/android/server/pm/PackageManagerService.java) 与 [AOSP ResolveIntentHelper](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/services/core/java/com/android/server/pm/ResolveIntentHelper.java) 的签名。


0.9.0 / code23 Release: see [RELEASE_0.9.0.md](RELEASE_0.9.0.md) for final APK identity, USB/Ethernet guards, 86 passing tests, Root upgrade evidence and the unconfirmed Find X9 Pro issue.

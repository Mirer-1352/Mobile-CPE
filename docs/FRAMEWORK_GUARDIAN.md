# 系统框架守护（0.9.0 / code23）

使用现代 libxposed API 102，将守护放在 `system_server` 中，应用进程退出后仍可检查和恢复自身共享服务。设置里的「系统框架守护」默认开启；已有前台服务、开机广播和共享恢复逻辑继续工作。

## Hook 与恢复

- `ActivityManagerService.systemReady`、`SystemServer.startOtherServices`：按方法名寻找现有重载，在原方法完成后异步初始化一次。注册广播、读写文件和 Binder 调用均在专用线程进行，不在 AMS 锁内执行。
- `ActivityManagerService.handleAppDiedLocked`：只识别手机CPE 的 ProcessRecord，原方法完成后投递任务，约 2 秒后检查恢复。厂商改动导致此适配不可用时，15 秒巡检仍作为兜底。
- 应用服务每 10 秒向框架发送心跳；45 秒内不重复启动。服务尚在但 90 秒没有心跳时，才尝试仅结束手机CPE 包并重新启动其前台服务；初始化也有 90 秒宽限。
- 使用 `startForegroundServiceAsUser` 启动明确的自有组件，支持 Direct Boot。不会拉起面板抢占当前桌面。
- 强停会留下 stopped 状态，恢复前只清除当前目标用户的手机CPE 停止标记。AsUser 返回空或被拒绝时记录失败并按既定策略重试；已删除未被成功验证的额外 AIDL 启动分支。
- ColorOS/OxygenOS 的可选适配 Hook `OplusAppStartupManager.isAllowStartFromService`，旧命名 `ColorAppStartupManager` 缺失时跳过。只对系统守护线程、明确的 `dev.autohotspot.HotspotService` 组件、已通过恢复策略的本次启动放行。线程标记在 finally 清除，Hook 内不读配置或调用 Binder；其他包和普通启动保持原结果。外层的高温等检查保留。当前实机成功使用 Context AsUser 路径。
- 已启用策略下，死亡通知与恢复过程使用最长 15 秒的部分唤醒锁，覆盖应用进程退出后的恢复窗口；不在 AMS Hook 回调中获取锁。
- 重试从 5 秒开始指数退避，上限 120 秒；任意 5 分钟最多尝试 3 次，避免应用崩溃循环。不会修改其他包的 OOM 分值或关闭系统后台管理。

## 用户控制与更新

总守护 `enabled=false` 或框架守护关闭时不恢复。`boot=false` 时重启手机后不主动启动；本轮开机手动启动共享服务后，仍能守护它。停止的 Android 用户不启动，使用用户实际 UID 与安装身份隔离配置。

包替换期间暂缓恢复，最长 180 秒；收到替换完成事件后重新检查。卸载或清除应用数据会清除框架配置。模块代码已载入系统进程时，覆盖 APK 不会热替换该进程里的旧 Hook；框架代码升级需要重启。

Wi-Fi、USB、以太网开关始终是持久化的用户期望，框架不根据实际接口、进程退出或能力缺失修改它们。框架只负责恢复共享服务，服务读取原选择并在条件满足时开启相应共享。

## 同步与状态确认

应用通过要求自有签名权限的动态接收器提交控制和心跳；框架核对发送用户、目标包实际 firstInstallTime 和单调配置版本。单一 RootSettingsSync 队列按最新意图同步框架配置和按用户保存的 HOME 标记，应用界面不直接发 Root 写入。Root 同步 `/data/system/mobile-cpe-guardian/<user>.json` 供下次开机读取，目录 system:system 0700，记录 0600。读写由共用 AtomicJsonFile 封装，同时使用进程内互斥、文件锁和 AtomicFile，防止旧心跳/迟到的 Root 写入撤销较新的暂停配置。

界面只有收到 system UID 发出的新鲜确认才报告框架已连接；仅作用域授权、开关开启或 Root 配置成功不算实际注入。没有确认时显示 LSPosed 未连接、等待作用域授权或等待注入。Root/SELinux/厂商接口拒绝会记录错误，不关闭 SELinux。

私有状态记录包含最近一次恢复的动作、时间、成功/错误和目标组件，供实机核对因果。不能仅凭 PID 变更或默认桌面重新出现判定守护成功；暂停验收检查共享服务和新的恢复动作，系统为了 HOME 或模块 Binder 拉起空应用进程不等于共享服务被恢复。

## 启用与当前验证边界

1. 先打开应用一次并授予 Root，以落盘开机守护配置。
2. 在支持 API 102 和 system 能力的框架启用手机CPE，批准应用自动申请的 system 及实际网络共享包作用域。
3. 重启手机，打开 MCPE 设置确认「系统框架守护已连接」。再测试杀进程恢复、暂停不恢复、息屏、重启及更新。

2026-11-20 经用户明确授权，已启用该机现有 Vector 2.2/API102 和其原本同样禁用的 Zygisk Next 依赖，批准 system 与实际的 com.android.networkstack.tethering 作用域，并重启测试。其他模块启用状态保持原值，没有连接 VPN。

OnePlus 8T/API37 的最终 0.8.2 实测：系统 PID 2294 的真实心跳确认、开机 START 成功、强停后 5.12 秒 START 恢复、SIGSTOP 冻结后 103.52 秒 RESTART_HUNG 恢复，以及暂停 60 秒无共享服务/无新恢复动作。测试期间系统 PID 未变，后台前台使用普通系统设置页，排除 HOME 重开造成的假阳性；MCPE 实际显示「系统框架守护已连接」。64 项 JVM 测试通过，原共享、常亮、防烧屏等选择均保留。

0.9.0 重构回归：候选版真实系统 PID2208 下强停 3.11 秒、冻结 92.86 秒 RESTART_HUNG、暂停 60 秒通过；最终 code22 再重启加载 Hook，PID1698 下息屏强停 6.39 秒恢复。ROOT 21→22 独立安装成功，原选择保留；77 项 JVM 测试通过。详细区分候选版与最终版证据见 DEVICE_RESULTS.md。

此前独立 app_process 实验遭遇 API37 AMS 的 ProcessRecord 广播注册限制，未作为成功证据，测试入口也未打包。该机曾出现厂商启动拦截，已通过读取本机框架与 OplusAppStartupManager 日志定位并加入上述限定适配；早期只检查 PID 的结果已作废，以最终的恢复记录、服务和心跳联合验收为准。

Android 9+ 的方法适配按版本反射：旧版接收器采用五参数 AsUser 接口，新版采用带导出标志的接口。API/源码审查与单元测试不能证明全部厂商 ROM 已运行通过。实际注入失败时保留原系统行为和既有应用层守护。

参考：[libxposed API 102](https://libxposed.github.io/api/io/github/libxposed/api/XposedModule.html)、[Vector 的 Zygisk 要求](https://github.com/JingMatrix/Vector)、[Android 9 AMS](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-9.0.0_r1/services/core/java/com/android/server/am/ActivityManagerService.java)、[Android 13 Context](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/core/java/android/content/Context.java)、[AOSP 广播注册校验](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/services/core/java/com/android/server/am/BroadcastController.java)。

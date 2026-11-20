# 0.10.1 桌面与后台可靠性

本地验证版 0.10.1/code29，未发布 GitHub Release。主要改动在应用与 ROOT 进程，系统框架 Hook 未新增或替换，不需要为这些改动重启手机。

## 默认桌面

设置仅保留「设为默认桌面」开关，代替原来的系统选择按钮和独立「LSPosed 桌面接管」开关。沿用 `forceHome` 持久化键，保留原有选择；开启时记录接管前的原桌面，通过 ROOT 设置本用户 HOME，保留按用户的框架解析兜底，并读取实际解析结果。界面区分正在应用、已生效和未生效原因。

关闭时清除框架标记；只有当前原生 HOME 仍为手机CPE，才恢复保存的原桌面。若用户已经换成其他桌面，不覆盖该选择。还原目标按用户和安装身份保存，ROOT 写入前落盘，应用私有原桌面记录同步为该目标。

删除了 MainActivity 收到 HOME 后自动跳回原桌面的逻辑及已失去用途的 HomeNavigationPolicy。该逻辑会抵消默认桌面，正是本次发现的直接问题。现在 HOME 留在手机CPE，只有明确的底部上划才打开原桌面，且不改变默认角色。

原生路径不依赖 Android 10 才引入的 RoleManager UI；Android 9 已具备 [set-home-activity --user](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-9.0.0_r1/services/core/java/com/android/server/pm/PackageManagerShellCommand.java) 和 [getHomeActivities](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-9.0.0_r1/core/java/android/content/pm/PackageManager.java)。隐藏入口仍可能受厂商改动影响，需核对实际结果；源码确认不等于全部 ROM 真机验收。

## 后台保护

Find 的原始观测表明：前台服务和部分唤醒锁不能防止 ColorOS Hans 冻结 UID；普通 deviceidle 豁免也不足以阻止它。进程 cgroup 自身可显示 freeze=0，但父 UID 已 freeze=1、events frozen=1，应用心跳停止，框架随后按失联策略重启服务。

实现分为两个明确边界：

- 标准 Android 电池优化豁免：仅添加手机CPE 本包，记录本应用是否拥有该豁免。暂停时仅撤销自己创建的记录，保留用户原有豁免；多个 Android 用户中只要仍有有效守护就保留共有记录。安装身份变化不继承旧所有权。
- 可选的 ROOT ROM 适配：发现当前 ROM 的 OplusHansFreezeManager/IOplusHansFreezeManager 后，使用系统 requestFrozenDelay 对实际用户的手机CPE UID 申请 60 秒保护，监控运行时每 30 秒续期。接收系统成功、拒绝和超时回调，并读取剩余时间确认，回调事务号读取当前 ROM 生成的 Stub，未硬编码服务事务号。

保护连接属于持续监控的 ROOT 进程 Binder，暂停关闭通道、异常退出或租期到期后回收。没有直接写 cgroup 或关闭全局 freezer，也没有伪造音频、通知、桌面小部件等运行场景。接口缺失、权限拒绝、反射/链接失配及回调超时只影响可选保护，继续原生共享读取和恢复；失败后五分钟低频重试。没有使用 DexKit。

## 验证记录

- Debug/Release 构建、两种变体 lint、111 项 JVM 测试及 APK 签名验证通过；lint 0 errors / 32 warnings。删除两项旧 HOME 跳转策略测试，新增五项电池豁免所有权/多用户策略测试，原有共享、流量去重、守护、作用域与更新测试保留。
- Find 桌面完整往返在 code27 和最终 code29 均通过，OnePlus 在 code28 通过：系统原生 HOME 角色变成手机CPE；从面板重复 HOME、从系统设置按 HOME 均进入手机CPE；底部上划进入原桌面且 HOME 角色不变；关闭后恢复原桌面。共享期望保持不变。
- Find 先通过独立限时探针核对回调成功、60 秒租期、续期和撤销；70 秒后台对照中进程与 UID 均未冻结、心跳持续。
- Find code28 集成保护后台观测窗口约三分钟，14 个采样覆盖 173.53 秒，PID 始终 28376；进程与父 UID 每次均 frozen=0，最新心跳年龄最大 9.072 秒。此前无保护/仅普通豁免的观测均发生父 UID 冻结。本轮不是息屏深度休眠验收。
- OnePlus 通过独立 ROOT 更新器完成 26→28→29：错误 SHA 候选被拒绝，最终安装成功、APK 摘要一致、前台服务/原生回调/原始偏好保留，system_server 未重启。
- OnePlus 最终版的可选 ROM 保护实际报告 supported=true、active=false，未把接口存在等同保护生效；原有原生共享和服务照常运行。Find 的 active=true 和后台冻结对照单独记录，不能外推到 OnePlus。
- Find 通过独立 ROOT 更新器完成 26→27→28→29，最终 APK 摘要、eth0 原生回调及全部偏好检查通过，system_server 未重启；无线调试传输曾停滞，重建独立 ADB 服务后补完检查，不将传输失败记作功能验收成功。
- Find 最终 code29 的守护生命周期通过：UI 暂停后原生保护租期与本应用拥有的普通电池豁免均释放，前台共享服务停止，系统仍报告 `eth0 - TetheredState - lastError = 0`；重新开启后保护重新获取。结束时所有偏好仅单调控制版本递增，守护开启、默认桌面关闭，原桌面恢复。

原始 0.8.1 网卡不枚举故障的根因仍未证实。0.10.0/code26 的最后一次真实开机已由用户确认没有拔插扩展坞，日志显示约开机 23.7 秒共享成功，详见 [上一版记录](RELIABILITY_0.10.0.md)。本轮用户休息后没有再次重启手机，也没有要求物理拔插。

Android 9–15、其他厂商、ROM 自有保护接口权限限制、多用户实际切换、长时间息屏和更多冷启动仍需对应环境验收；VPN 按用户要求暂不连接测试。模块使用现有现代 API 102，没有宣称全部系统兼容已实测。

## 产物

- `dist/PhoneCPE-0.10.1.apk`，0.10.1/code29，21,014,426 字节，非调试 Release。
- APK SHA-256：`8748875dc4eec7c52c0bddae56a60a49fa90ac2f0e85741cf4c23e6128d23c13`。
- 签名证书 SHA-256：`4929d9e532d1bd29997e2e49672eab3c51c4c171fa5a8620187e373276dc315e`，沿用已安装版本签名。
- ROM 私有 jar、USB 探针、设备日志和调试地址仅留在忽略的本地目录，不进入发布包或公开文档。

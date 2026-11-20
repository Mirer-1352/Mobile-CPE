# 0.10.2 / code31：源码入口与 R8

Release 产物与 [GitHub 版本页](https://github.com/daxiaamu/Mobile-CPE/releases/tag/v0.10.2) 对应，保持公开测试阶段。

## 改动

- MCPE 设置的「版本」区加入「查看源码」，以 ACTION_VIEW 打开 `https://github.com/daxiaamu/Mobile-CPE`，仓库标识复用更新配置。没有可用浏览器时给出提示。
- Release 开启 R8 代码压缩、优化、混淆和资源压缩，使用 `proguard-android-optimize.txt`；Debug 保持未压缩。
- 保留两个 app_process 独立入口 RootMain/RootUpdateWorker 的类名与 public static main，以及 META-INF/xposed/java_init.list 对应 CpeModule 的类名和构造函数。其他可达实现继续优化和混淆，没有保留整个应用包。
- 增加 `scripts/verify_r8_release.py`，直接检查最终 APK 的 DEX 定义、模块元数据、构造/回调和混淆映射，不以 Gradle 开关代替产物验证。

## 验证

- 最终 Debug/Release 构建、lint 通过，111 项 JVM 核心测试通过；lint 0 errors / 33 warnings，新增项为 String.toUri 的 KTX 风格建议。
- APK v2 签名验证通过，证书沿用已有安装。最终 DEX 共 2,333 个类，映射中 165 个应用类发生改名；两个 ROOT main、模块构造、onPackageReady/onSystemServerStarting 均实际存在。框架 API 仍为 compileOnly，未打包进 APK。
- code30 候选压缩 APK 在 Find 和 OnePlus 的独立 ROOT 进程中均可直接执行 RootMain，读取原生事件、ROOT 身份和已有共享接口；测试只观察，不调用共享开关。
- OnePlus 实际由旧 code29 更新到 R8 code30，再由 R8 code30 的独立 ROOT 更新执行器更新到最终 R8 code31。两轮均检查错误 SHA 拒绝、安装摘要、前台服务、共享回调、全部偏好保留和 system_server 未重启，最终安装文件与交付文件相同。
- OnePlus code30 与最终 code31 均实际点击「查看源码」，系统浏览器收到正确仓库 URL；R8 后 API 102 服务仍实际连接。压缩模块在带 API 102 的隔离进程中成功加载、构造，系统/包回调仍存在；未向该进程安装 Hook。
- OnePlus 最终 code31 默认桌面完整往返通过：开启后从面板重复 HOME、从其他应用 HOME 均进入手机CPE；底部上划只打开原桌面而不改角色；关闭恢复原桌面，原共享意图保留。
- Find 在候选 ROOT 只读检查后无线 ADB 无法重连，定向 mDNS 未发现新服务，因此没有安装最终 R8 版；没有要求用户继续调试或重启。

系统进程当前仍使用之前已加载的模块。没有为了本次改动重启手机，故不能将隔离加载和元数据检查表述为最终 R8 模块重启注入通过。Android 9–15 等原有跨设备边界保持不变。

## 产物与回溯

- `dist/PhoneCPE-0.10.2.apk`，2,068,257 字节，约 2.07 MB；相比上一版 21,014,426 字节减少约 90.2%。
- APK SHA-256：`c051038a3ef87e65b19aada13effe26b62471e0af3753df7a5f9e5aca9b4369e`。
- 签名证书 SHA-256：`4929d9e532d1bd29997e2e49672eab3c51c4c171fa5a8620187e373276dc315e`。
- 对应 mapping.txt、R8 配置和产物报告本地归档在 `.release/r8-0.10.2-code31/`，不依赖下次构建会覆盖的 outputs 目录。映射应按版本保存以供堆栈还原。

规则参考 [Android R8 配置](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization/) 与 [libxposed 官方入口保留说明](https://github.com/libxposed/api#for-module-developers)。本项目选择固定模块入口名，无需改写入口资源中的类名。

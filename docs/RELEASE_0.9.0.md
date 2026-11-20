# 0.9.0 / code23 首次发布验证

发布包为非调试 Release 变体，沿用原开发签名以保持覆盖更新能力，没有引入混淆或加壳。minSdk28，包含 arm64-v8a、armeabi-v7a、x86、x86_64；v2 签名验证通过，v1/v3 未启用。

APK：`PhoneCPE-0.9.0.apk`，20,948,894 字节。

SHA-256：`380dd1c6f4ab58839e1e40fb3d98053d0cde21a97918751fc9e7a82a520216b0`。

签名证书 SHA-256：`4929d9e532d1bd29997e2e49672eab3c51c4c171fa5a8620187e373276dc315e`。

## 发布前追加修正

用户报告 Find X9 Pro 上 0.8.1 / 系统16 授 ROOT 后系统以太网络共享无法开启，USB 共享关闭，关闭总守护的对照尚未完成。不能据此认定 USB 共享开启抢占是该机故障原因。

审查发现两处通用风险，已修正：

- ROOT 只读 USB 数据角色，不按供电角色推断 Host。角色接口不可用时用已枚举外设识别 Host，保留旧系统 gadget/广播回退。Host 且无 Device 端口时暂缓会改变 gadget 的共享启停，并在执行边界重查；独立的 Host+Device 端口可共存。用户选择继续保留。
- 以太网无接口时不发起请求，接口存在即可尝试，不要求 carrier。已接受请求留出 30 秒稳定窗口，carrier 上升或无关 USB 事件不取消等待；用户停止不受等待限制。观察到实际共享后，恢复正常断线重试。

没有加入 Find/Oplus 特判，没有更改系统 USB 数据角色，没有关闭鉴权、SELinux 或系统安全策略。

## 本次证据

- Release 构建和 lint 通过：0 errors / 31 warnings。
- 新增 9 项 USB 角色、接口门控、启动等待和停止优先回归，共 86 项核心测试通过；11 项发布器测试通过。
- OnePlus 8T 实际 ROOT 22→23 安装成功，独立执行器交接后恢复前台服务和新鲜框架心跳；完整配置保持一致。实际 USB Device=true、Host=false，USB 共享仍开启，安装 APK 摘要与交付文件一致。
- 此前 code21/22 的完整重构验收见 DEVICE_RESULTS.md。Find X9 Pro 的原问题未在对应硬件上复现或证实修复，需后续取日志与对照测试。

接口依据：[AOSP USB 数据角色](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/hardware/usb/UsbPortStatus.java)；[AOSP 重复共享请求处理](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/Tethering/src/com/android/networkstack/tethering/Tethering.java)。

## 发布结果

- [GitHub Release v0.9.0](https://github.com/daxiaamu/Mobile-CPE/releases/tag/v0.9.0) 已公开，唯一 APK 的 GitHub 服务端摘要与上述摘要一致。
- 发布源码提交：`e962b9762dc145cdd073f56c28d989047d75a372`；设备测试材料、框架提取文件、构建缓存和签名私钥未上传。
- [更新工作流 44651150926](https://github.com/daxiaamu/Mobile-CPE/actions/runs/44651150926) 成功；Runner 再次运行 11 项发布器测试，并通过 8 个不同 CDN 主机的完整 APK GET/哈希验证。
- stable revision 1，versionCode 23，强制边界仍为 0。不可变 manifest 提交 `4e12276be3e08b140d4a6b715fd4c68461b97ab0`，摘要 `9338dc50eb78348bc21bd22ccbbee8e0ed06f887f97ef8b8bd0fbff592ed1e90`；`updates/latest-stable.json` 已指向该提交。

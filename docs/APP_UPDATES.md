# GitHub / Root 自动更新

仓库：[daxiaamu/Mobile-CPE](https://github.com/daxiaamu/Mobile-CPE)。适用于 Android 9+，当前 0.9.0 / versionCode 23。

原始更新技能为 `G:/My_Projects/TempROOT/.reference/update-by-github-skill/SKILL.md`。2026-11-20 用户明确要求「有 ROOT 能力，全自动更新」，因此本项目采用 Root 自动下载和安装，替代通用技能的三按钮确认、未知来源授权和系统安装器流程；其清单信任、SHA-256、APK 身份与发布校验要求继续保留。

## 自动流程

`后台检查 -> 信任清单 -> 下载/缓存重验 -> 独立 Root 安装器 -> 覆盖安装 -> 恢复共享`

- 首次解锁后检查；守护服务运行时每 6 小时检查，失败 15 分钟后重试。自动与手动共用一个进行中会话，手动加入可收到可见结果；手动检查不复用已结束的检查结果。
- 检查由共享服务驱动，不需要面板在前台。锁屏首次解锁前不读取更新 CE 存储，Direct Boot 共享恢复不受影响。
- 发现合法的新 versionCode 后自动下载、校验并 Root 安装，无下载确认、未知来源设置或安装器点击。普通后台更新不弹窗；设置中的检查入口可查看说明、进度与失败原因。
- 已接受的 manifest、原始字节摘要及有效期保存在私有存储；恢复进程时只有摘要和有效期均匹配才复用。完整缓存 APK 必须再次验哈希与身份，然后自动继续安装。
- 不再执行旧版本的「跳过/忽略」决策。强制边界继续验证、持久防回退；手动显示强制更新说明时仍不可跳过。
- stable 构建只检查 stable；版本名含 beta/alpha/rc 的构建检查 stable、beta 并选合法高版本。比较只用 versionCode，禁止降级和同 code 换 APK。

## Root 安装与进程恢复

1. 应用先验证 APK size、SHA-256、包名 `dev.autohotspot`、目标 versionCode、minSdk 和当前 signer 集合。
2. 使用现有 RootClient，经 su 启动 RootMain；不同 Root 方案沿用 PATH su 和 `/debug_ramdisk/su`、`/sbin/su` 等兼容入口，需要当前应用已经获得授权。
3. Root 再校验源文件，将当前执行器 APK 和候选 APK 复制到随机的 Root 私有目录 `/data/local/tmp/mobile-cpe-update-*`。目录 0700、APK 0400，重新验副本，避免应用进程结束、旧 APK 路径移除或应用侧文件变化影响安装。
4. 独立 `app_process` 执行器调用 API 21 起已有的 `Os.setsid()`，所有标准流重定向；执行器就绪后才返回。它与调用方 su 会话、应用 UID 进程生命周期分离，不依赖 ROM 提供额外的 setsid/nohup 程序。
5. 通过 `/system/bin/cmd package install -r --user <发起用户> -S <字节数>` 标准输入安装。省略路径参数：Android 9 与现代 PackageManagerShellCommand 均支持；不能附一个裸 `-`，部分新实现会将它当未知选项。不给 `-d`、`-g` 或绕过低 target 的参数，不改 SELinux 或全局安装权限。
6. 安装进程最多等待 120 秒，检查命令返回值及实际安装版本。原子状态写入 `/data/system/mobile-cpe-updates/<user>.json`，该目录仅 Root 可读写。准备阶段按用户加锁，避免并发启动两个安装器；状态读写共用文件锁与 AtomicFile，避免旧系统读取过程中回滚备份。
7. 成功后重新读取目标用户的 Direct Boot 配置，仅在 enabled 时恢复 HotspotService，并检查恢复命令返回值。`MY_PACKAGE_REPLACED` 也会恢复已启用的守护，独立于 boot 开关；显式暂停的守护不会被重新启用。更新前面板在前台时恢复面板，后台更新不主动打开界面。
8. 当前 signer 集合必须完全相同，不自动接受密钥轮换。Root 被拒绝、SELinux/企业策略限制、APK 不支持当前系统等情况保留原应用并记录原因，稍后重试；不会降级成需要点击的安装流程。

安装仍会替换并结束应用进程，**不承诺共享业务零中断**；守护恢复后重新核对接口并维护原选择。后台失败不会伪装为已更新。实际安装成功但恢复命令失败时，日志区分安装结果与恢复问题。

## 清单权威与多源

代码在 `main`，元数据在 `updates` 分支。客户端并发检查 GitHub Contents API、Raw、四个 jsDelivr 入口和 Statically，七源均为 HTTPS；每次加 cache-buster、无缓存头、User-Agent、连接/读取超时和 1 MiB 大小上限，每级重定向必须 HTTPS。

GitHub Contents API 是唯一权威。有效时优先采用并等待 800ms 镜像冲突收敛窗口；同 revision 的规范化指针不同则拒绝。权威不可用且尚无应用层签名时，需要两个不同主机提供完全相同指针才能回退，不能按最快或多数覆盖权威。jsDelivr 多入口不代表独立服务商。

指针 `latest-stable.json` / `latest-beta.json`：

```json
{
  "schemaVersion": 1,
  "channel": "stable",
  "policyRevision": 1,
  "commit": "40位固定提交SHA",
  "path": "manifests/1-清单SHA256.json",
  "manifestSha256": "64位小写清单SHA256",
  "expiresAt": "2027-11-20T00:00:00Z"
}
```

不可变 manifest：

```json
{
  "schemaVersion": 1,
  "channel": "stable",
  "versionCode": 16,
  "versionName": "0.7.3",
  "policyRevision": 1,
  "maxForcedVersionCode": 0,
  "sha256": "64位小写APK的SHA256",
  "size": 12345678,
  "publishedAt": "2026-11-20T00:00:00Z",
  "changelog": "## 更新\n\n- 修复问题",
  "urls": ["至少五个不同第三方CDN主机的HTTPS地址，附加GitHub原始地址"]
}
```

示例占位值不能直接发布。指针固定 commit 和原始字节 SHA-256；客户端保存最高 revision、指针摘要、code、强制边界，拒绝回退和同 revision 修改。有效期为一年，续期必须人工增加 revision。强制条件为 `current <= maxForcedVersionCode && latest > current`，初始策略为 0，不强制。

APK 下载每约 300ms 更新进度，验证完成前最多 99%，未知长度显示不确定环；逐个使用清单的 URL，HTTP、大小、哈希或身份失败删除临时文件再试下一源。512 MiB 上限，流关闭并同步落盘、验包通过后同目录原子重命名。进程中断不续传字节，缓存完整文件也必须重验。

更新说明仍为原生可选择文本和受限 Markdown；不执行 HTML、远程图片或自定义 URI。时间解析为 Instant 并按本地时区/Locale 展示，非法时间隐藏。长日志整体滚动，动作区和进度区域固定占位。

## GitHub Actions 发布

工作流 `.github/workflows/update-metadata.yml` 在 Release published 后运行，也可明确输入已有 Release 的 tag 重试。它不创建 Release、不上传 APK、不自动改强制策略；Release 和 APK 由维护者明确发布后才触发元数据流程。

1. 将代码和工作流提交至 `main`。发布前人工修改 `updates/policy.json` 对应渠道：递增 policyRevision，保留或审核强制边界并填变更原因。
2. 固定签名、增加 versionCode，Release tag 与 APK versionName 相同或加 v；beta/alpha/rc 与 prerelease 标记一致。只放一个 `.apk` 资产。
3. 脚本 `scripts/update_metadata.py` 下载唯一 APK，aapt2 从 APK 读真实身份，apksigner 验证签名结构，再计算 SHA-256 和 size。
4. 并发 HEAD/完整 GET 检查 `updates/cdn-sources.json` 候选，必须至少五个不同第三方主机的字节与 APK 完全一致；最终 GET 主机须与入口一致，避免重定向别名凑数。GitHub 原始地址附在最后，不算五个 CDN。
5. 不足五个合格 CDN 或配置/策略冲突则失败，保留原指针。通过后先生成不可变 manifest 的 commit，再生成指针 commit，最后一次非强制 push 原子移动 `updates` 分支。
6. concurrency group 串行发布；contents:write 为最小工作流权限，凭据仅来自 GitHub token。推送前再核对远端头；有外部并发写入则重新运行，不能 rebase 改变固定 commit SHA。

## 验证范围

- JVM 覆盖清单解析、信任冲突、回滚、文件重验、身份规则、共享检查会话、周期窗口、升级后启动策略及安装参数；Python 覆盖发布策略和本地真实 Git 的原子指针发布。
- OnePlus 8T：实际 Root 14→15 覆盖升级，验证原应用 PID 被替换、调用会话结束后安装器继续工作、Wi-Fi/前台服务及配置恢复；坏哈希、非升级、损坏签名被拒绝。
- OnePlus 8T：私有缓存夹具驱动无 Activity 的 15→16 自动升级，boot=false 仍恢复服务和 Wi-Fi，不抢原桌面前台，结束恢复原配置。夹具模拟「此前已接受清单及完成缓存下载」，**不是 GitHub/CDN 网络发布实测**。
- 其他 Android/ROM、USB 网卡、VPN、双卡等状态见 [COMPATIBILITY.md](COMPATIBILITY.md)。没有把本机通过等同于全 ROM 验证。

依据：[Android 9 PackageManagerShellCommand](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-9.0.0_r1/services/core/java/com/android/server/pm/PackageManagerShellCommand.java)、[Os API](https://developer.android.com/reference/android/system/Os)、[前台服务后台启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)。

# v1.5.1 验证

日期：2026-10-04。APK versionCode 10，Android 10+ arm64，Room 版本保持 4。

## 改动

- X 下载在所选分辨率有完整 HTTP 格式时优先选它，缺失时回退原格式；不会仅因 HTTP 可用而自动降低分辨率。
- 下载 socket 超时 45 秒，HTTP / 分片各重试最多 5 次，退避等待最大 8 秒。启用 `--abort-on-unavailable-fragments`，不跳过失败分片生成残缺成品。
- 可恢复的 X 错误自动重新运行一次提取及下载（刷新媒体 URL）。同格式保留 `.part` 和分片状态，临时文件包含 format_id，避免格式切换后混接；恢复仍失败时显示请求错误详情。
- 下载期间持有 PARTIAL_WAKE_LOCK，单次上限 6 小时，结束、失败或取消后释放。Service 销毁会停止活跃 yt-dlp 进程。
- 修正通知百分比；保留 403、分片失败、超时上下文，并隐藏媒体 URL 查询参数。

## 构建与测试

- 最终 `:app:testDebugUnitTest :app:assembleRelease` 成功，42 个单元测试全部通过，release lint 通过；`git diff --check` 无错误。
- 新测试覆盖同分辨率 HTTP 选择及回退、可恢复错误与权限/证书/磁盘错误区分、保留请求原因和隐藏签名参数、Android request wrapper 保留两种 retry-sleep。
- `tools/test_download_recovery.py` 使用 App 内置的 yt-dlp 2026.08.19，所有请求指向本机 HTTP 服务器。FFmpeg 生成 4 秒 H.264 + AAC HLS 素材：
  - 首片连续两次 503，第三次成功；结果 4.040 秒，整段 FFmpeg 解码通过。
  - 首片持续 403，重试后中止；未继续请求下一片，不产生 MP4 成品，也没有丢失成因而仅报告空文件。
  - 同分辨率有 HTTP 则选 HTTP，只有低分辨率 HTTP 时保留所选分辨率 HLS；模拟选择不请求媒体。

## Android 下载流程

- Android 14 arm64 模拟器，360×760。先安装既有 v1.5.0，再覆盖同签名 debug 测试包；最终覆盖交付 release v1.5.1 成功，已完成条目保留，无 AndroidRuntime 异常。
- 使用一条平台字段为 `x`、实际 URL 为 `http://127.0.0.1:52532/fixture.mp4` 的本机任务，验证 X 下载策略和自动恢复。测试视频为本机 FFmpeg 合成画面及声音，没有登录信息、平台帖子或平台媒体。
- 本机服务器首次 GET 返回 403；重新提取后下载成功，服务器共收到 3 次 GET。下载中关闭屏幕，继续运行并保存到 MediaStore，数据库状态为 COMPLETED。
- PowerManager 记录：17:34:05 获取 `videodl:download` 唤醒锁，17:34:20 下载结束释放；息屏时为 PARTIAL_WAKE_LOCK，完成后不再持有。
- 保存成品 9896022 字节，SHA256 `a9ebfa29a5f9ea43465aea2be51f1c97766e189d3059c50e38da61ed88d5737a`，与原始合成 MP4 逐字节一致，整段 FFmpeg 解码无错误。
- 最终 release 保留测试完成记录，截图 `screenshots/v1.5.1/recovered-local-download.png`。

## 交付

- `dist/视频下载器-v1.5.1-arm64.apk`：77573672 字节。
- SHA256：`50fa96e014d14665808b14e51fba201ed244eae2529ed842d8a9c98237796d86`。
- 签名证书 SHA256：`f9d41eb7aef7c9f1742557d7b447c7c6fd12bd1ea47d6e1ec52447e1a205d9eb`，与已有交付相同。

## 验证范围

没有请求用户提供的 X 帖子、预览、清单或视频。上述测试证明本机条件下的下载恢复和完整性，不能证明真实 X 的 VPN、登录权限和 CDN 均已通过。真实 X 长视频仍由用户验收；若仍失败，新版将显示更具体的请求原因。

本轮未加入脱衣或色情图片生成功能，APK 不含图像修复模型。

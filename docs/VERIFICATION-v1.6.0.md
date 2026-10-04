# v1.6.0 并发下载验证

日期：2026-10-04。versionCode 11，Android 10+ arm64，Room 版本保持 4。

## 改动

- 默认两个下载 worker。领取任务的读取和状态转换在 Room 事务内完成；重复点击只会把 READY 转为 QUEUED 一次。
- 每条任务使用独立协程、yt-dlp process ID、临时目录、进度记录和 CPU 唤醒锁。取消一条只停止这一条；空闲位置在另一条仍运行时继续接收新任务。
- 单次 SQL 同时取消全部下载和排队任务，防止取消途中启动后续任务。保存、取消、失败使用条件更新；已取消条目不会被完成回调覆盖。
- 通知显示两条任务的汇总和独立进度，分别支持取消此条和全部取消；更新限速，避免通知互相覆盖或频繁刷新。队列标题注明最多同时下载 2 条。
- FFmpeg 后处理插件使用 `fcntl.flock` 跨进程锁，网络下载不占锁。FFmpeg 子进程继承锁文件描述符，取消父进程时仍保留锁到子进程退出，避免重叠和残留锁。插件加载使用 [yt-dlp 插件接口](https://github.com/yt-dlp/yt-dlp#plugins)。
- 同名成品保存时不替换既有文件，避免并发任务误删彼此的成品。

## 构建和单元测试

- 最终 `:app:testDebugUnitTest :app:assembleRelease` 成功；44 项测试，0 失败；release lint 通过，`git diff --check` 无错误。
- 新 worker 测试验证六条任务只执行一次、同时最多两条、确实发生并行；另一条被阻塞时，新加入任务能够立即使用空闲位置。
- `tools/test_ffmpeg_serial.py` 使用 App 内置 yt-dlp 和插件，两个独立进程请求后处理，执行区间不重叠；持锁父进程被取消后，下一条等待其子进程结束再执行。
- `tools/test_download_recovery.py` 现在也加载同一插件；分片 503 恢复、持续 403 中止及所选分辨率 HTTP/HLS 回退均通过，成品整段解码正常。

## Android 14 arm64 实测

使用本机 FFmpeg 合成的 12 秒 H.264 1280×720 + AAC 视频。测试任务的平台字段为 `x`，实际 URL 全部为 `http://127.0.0.1:56517/` 本机素材，不访问平台帖子或媒体。

- 四条任务 A/B/C/D 批量开始，A/B 同时处于 DOWNLOADING，C/D 排队。
- 在 B 下载过程中发送与单条取消按钮相同的 Service Intent：B 变为 CANCELED，A 继续下载，C 自动补位，之后 D 接续。
- 115 次数据库采样中最多两条处于 DOWNLOADING/MERGING/SAVING；最终 A/C/D 为 COMPLETED，B 为 CANCELED，没有被后续回调覆盖。
- A/C/D 成品各 9896022 字节，分别从 MediaStore 对应的下载目录读回，与原合成 MP4 逐字节相同；同名旧文件仍保留。
- 通知存在 `下载进行中 · 2/2` 汇总及两个不同 `task-progress` 记录；各自的「取消此条」PendingIntent 携带独立任务 ID。
- 另一轮批量开始后执行全部取消，两条运行中、两条排队中的任务全部变为 CANCELED，没有请求排队的 C/D 素材。
- 使用 App 内嵌 Android Python 和 FFmpeg，加载同一插件，对独立视频流和音频流实际执行合并；成功产出双轨 MP4，主机整段解码通过。
- 最终 release 覆盖旧签名安装成功，队列记录仍保留，Room user_version=4；无 AndroidRuntime 异常，任务结束后未持有下载唤醒锁。
- 双任务 UI 截图：`screenshots/v1.6.0/two-downloads.png`。

## APK

- `dist/视频下载器-v1.6.0-arm64.apk`：77603449 字节。
- SHA256：`aee0de3e4e10bd2ac9600a6c932b32334633ff43bc81293e7a079651f3d050c2`。
- 签名证书 SHA256：`f9d41eb7aef7c9f1742557d7b447c7c6fd12bd1ea47d6e1ec52447e1a205d9eb`，沿用既有签名，可覆盖旧版。

## 范围

未请求或下载真实 X 帖子、预览、清单或视频。以上结果证明本机素材条件下的并发调度、取消、保存和合并行为；真实平台的带宽、VPN、Cookie 与 CDN 限制仍需要用户验收。

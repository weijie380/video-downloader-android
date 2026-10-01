# v1.1.1 修复验收

日期：2026-09-30。版本号 1.1.1，versionCode=3；Room 仍为 v2，不改队列结构。

用户报告：`2104910972989460910: No video could be found in this tweet`。

已定位的 App 缺陷：`RequestPolicy.shouldRetryX` 将 `no video could be found` 放进不回退名单，导致 yt-dlp 主接口缺少媒体信息时没有调用备用接口。修正为在没有明确删除、私密、登录或网络限制时尝试一次 syndication，保存成功接口供下载复用。无法据此断言该帖子实际能被访客下载。

已通过：

- Kotlin/Room 编译及 release 构建。
- 20 项离线 JVM 测试，零失败。新增两项覆盖用户提供的原始错误文本及明确访问限制优先的情况。
- APK 签名验证通过，签名证书与 v1.0、v1.1 相同。
- 仅修改 X 回退判断、失败说明和版本信息；抖音下载代码沿用 v1.1 验证版本。

没有请求这条 X 帖子、任何 X 视频信息或媒体文件。真实解析和下载由用户覆盖安装后点击失败任务「重试」完成。

交付：`dist/视频下载器-v1.1.1-arm64.apk`，75,253,410 bytes。

SHA256：`d2a52d23e6bd77845c71af90eceb6a8485cefd5467933e02362fc3cb75737cf5`。

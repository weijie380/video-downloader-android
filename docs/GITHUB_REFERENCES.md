# GitHub 参考与采用范围

核对日期：2026-09-30。已阅读以下项目的实际源码，而非只按 README 的支持列表判断。

| 项目 | 核对的源码 | 本项目采用的思路 |
| --- | --- | --- |
| [Seal](https://github.com/JunkFood02/Seal) | `DownloadUtil.kt`、`WebViewPage.kt`；参考提交 `7677f61` | WebView 会话、Cookie 与 User-Agent 一致；解析和下载共用请求配置 |
| [YTDLnis](https://github.com/deniscerri/ytdlnis) | `YTDLPUtil.kt`、`WebViewActivity.kt`；参考提交 `7adfbcc` | 可配置代理、请求超时、浏览器验证、完整错误信息 |
| [ai-video-downloader](https://github.com/Shaun520/ai-video-downloader) | `packages/core/src/douyin.ts`、游客 ttwid 修复记录；参考提交 `e64b19f` | 抖音独立游客解析；保留 Set-Cookie 后重试官方分享页；从 `_ROUTER_DATA.loaderData` 提取视频数据 |
| [Douyin-API](https://github.com/RandallAnjie/Douyin-API) | `src/utils/tokens.js`、`src/douyin/crawler.js` | 官方游客 ttwid 注册接口；自行用 Kotlin 实现，不复制其源码 |
| [yt-dlp](https://github.com/yt-dlp/yt-dlp) | `yt_dlp/extractor/twitter.py`、官方 2026.08.19 发布文件 | X 的 GraphQL / syndication 接口选择；打包官方引擎 |

## 取舍

- Seal、YTDLnis 仍以 yt-dlp 为解析核心，不能据此保证抖音无需账号即可成功。
- 游客 Cookie 是访问会话，不等于登录账号。分享页没有媒体信息时，通过官方 WebView 完成游客验证，再读取页面或重试 HTTP 请求。
- 本项目自行用 Kotlin 实现相关流程，没有复制 Seal/YTDLnis 的 GPL 源文件。
- ai-video-downloader 采用 MIT 许可证；仅参考其会话和分享页 JSON 处理思路。没有移植 Node 下载器、整文件缓冲或验证码计算代码。
- 实测分享页根节点 `video.width/height` 与默认播放流分辨率不同，因此不拿原视频尺寸标注下载画质。
- 不引入第三方解析服务器。Cookie 留在手机，媒体元数据缓存不含 Cookie。
- 旧的 `fly-studio/douyin-downloader` 已归档，不作为当前平台兼容性的依据。
- X 的回退不能解决手机本身无法访问 X 的网络问题；错误会显示 DNS、TLS、超时及两次接口失败详情。
- 本轮没有请求或下载真实 X 视频，X 的实际验收由用户完成。

## 相关源码链接

- [Seal 请求选项](https://github.com/JunkFood02/Seal/blob/main/app/src/main/java/com/junkfood/seal/util/DownloadUtil.kt)
- [YTDLnis 请求选项](https://github.com/deniscerri/ytdlnis/blob/main/app/src/main/java/com/deniscerri/ytdl/util/extractors/ytdlp/YTDLPUtil.kt)
- [抖音游客解析](https://github.com/Shaun520/ai-video-downloader/blob/main/packages/core/src/douyin.ts)
- [X extractor](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/twitter.py)

## v1.2.0 X 登录状态

- [yt-dlp Twitter extractor](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/twitter.py)：auth_token 判断登录，ct0 提供 CSRF；携带登录 Cookie 时强制 GraphQL。公开 syndication 回退使用独立访客请求。
- [上游问题 #7920](https://github.com/yt-dlp/yt-dlp/issues/7920)：2023 年曾报告公开视频在访客身份下出现相同 no-video 错误、使用浏览器 Cookie 后成功。此历史案例仅解释一种可能原因，不能证明当前帖子会成功。
- 本项目自行实现 Kotlin Cookie 筛选、私有存储、逐请求快照、官方 WebView 入口与系统文件导入。登录及真实 X 解析由用户验证，本轮不访问 X 帖子或视频。

## v1.3.0 抖音图文

- [astrbot_plugin_parser 的 Douyin 数据结构](https://github.com/Zhalslar/astrbot_plugin_parser/blob/main/core/parsers/douyin/video.py)：官方分享页 loaderData 下的 note_(id)/page、videoInfoRes.item_list、images.url_list。
- [douyin-downloader 的图文改进记录](https://github.com/jiji262/douyin-downloader/pull/180)：images/image_post_info、image_list 和多个图片地址字段的兼容思路。
- 用户提供作品 7691179030373265061 的官方分享页实测返回 2 张图片，包含 download_url_list 与 url_list。本项目自行实现 Kotlin 按序解析与下载，不复制第三方下载器源码，不使用第三方解析服务，不更改媒体 URL 去水印。

## v1.5.0 无水印播放源

- [video-parser 抖音实现说明](https://github.com/zhulin025/video-parser)：从官方分享页 play_addr 提取视频，再切换 `/playwm/` 到 `/play/`。本项目自行实现限定官方域名与准确路径的转换，不复制下载器源码，不修改 CDN 签名；同样用于已有任务缓存。
- 本轮实际对照作品 7586993328005123347：旧分享流存在移动抖音标识及账号水印，官方 play 原始流中该水印消失。原画面中的品牌文字和字幕保持不变。

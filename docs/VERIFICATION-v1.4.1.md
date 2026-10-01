# v1.4.1 图标验收

日期：2026-09-30。

- APK：`dist/视频下载器-v1.4.1-arm64.apk`，versionCode 7 / versionName 1.4.1。
- 大小：77,521,804 bytes，SHA256：`7dc5497330fbedbbe636a6e5c70992850847771e734b64a3a9b4a03cd286508d`。
- `:app:assembleRelease` 离线构建成功，Android 14 arm64 模拟器覆盖安装成功。
- 原签名 SHA256：`f9d41eb7aef7c9f1742557d7b447c7c6fd12bd1ea47d6e1ec52447e1a205d9eb`。
- Android PackageManager 返回 AdaptiveIconDrawable；从已安装资源原生绘制 512px、48px，检查默认形状和额外圆形 mask，主图未被裁断。
- getMonochrome() 返回有效资源，原生绘制单色主题预览。
- 导出图及生成提示词：`design/icon/`。只修改图标资源、Manifest 图标引用和版本号；下载与解析代码沿用 v1.4.0。
- 本轮未请求 X 帖子/元数据或下载视频。
- 用户新报告「没有找到抖音作品编号」：已定位 DouyinGuestParser.shareUrl 的编号识别分支，当前只读取 HTTP 跳转后的 URL；未收到这次失败分享链接，尚未复现或宣称修复。需要原始链接进一步检查重定向、验证页或链接类型。

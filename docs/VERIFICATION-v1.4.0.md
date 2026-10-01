# v1.4.0 UI 验证记录

日期：2026-09-30。平台：现有 Android 14 arm64 模拟器，320×640，小屏；另检查 640×320 横屏布局。

## 交付

- APK：`dist/视频下载器-v1.4.0-arm64.apk`
- 版本：versionCode 6 / versionName 1.4.0；Android 10+，arm64-v8a。
- 大小：76,886,404 bytes。
- SHA256：`126ca38a7796b5a4c4ae06ecb3de7ff855c110a9d86ab8268be0b3b0b189aa81`。
- 签名 SHA256：`f9d41eb7aef7c9f1742557d7b447c7c6fd12bd1ea47d6e1ec52447e1a205d9eb`，与前版一致。
- 已在原安装上覆盖；数据库仍为版本 3，原有 8 条任务状态保留，无新迁移。

## 构建与测试

`:app:testDebugUnitTest :app:assembleRelease` 成功；29 项测试通过，0 failures / 0 errors：UrlUtils 11、ResolverPolicy 9、XCookieFile 6、GalleryPolicy 3。图片加载使用固定 Coil 2.7.0。第一次构建获取依赖，后续最终构建使用 --offline。

## 实际界面与下载检查

1. 点击底部「首页 / 下载队列」成功切换；横向滑动可双向切换，切换后所选标签随页面更新。列表可纵向滚动，底部预留导航栏空间。
2. 在队列页从其他 App 分享抖音链接后返回首页，确认后点击解析；解析成功自动切到队列顶端。
3. 用户提供图文 `https://v.douyin.com/_LrB0ChkCp0/` 解析为「你眼睛吞了我。#陈粒 #情绪」，两张图片。解析后远程预览成功显示；完成任务从 MediaStore 首图显示预览。
4. 两条待下载图文任务（10 / 11）同时存在时，点击任务 11 的「下载全部图片及文案」：11 进入 QUEUED 并完成，10 保持 READY。顶部仍显示剩余待下载数。
5. 任务 11 实际保存：001.webp 113,928 bytes；002.webp 128,164 bytes；文案.txt 121 bytes。删除 11 条目后，MediaStore 1000000080 / 81 / 82 仍可查询，大小一致，is_pending=0。系统对重复保存自动加文件名后缀，原文件保留。
6. 用已有抖音视频任务 7 的元数据创建 READY 测试任务 12（原任务不变），检查视频预览、「下载视频」及画质弹窗。点击单条下载后完成，MP4 4,076,253 bytes，MediaStore 1000000083。删除完成条目后，该 MP4 仍存在且 is_pending=0。
7. 新增测试条目 10 / 11 / 12 已通过界面删除，最后保留原有 8 条任务；真实下载文件按用户要求保留。
8. Android 14 上实时折射着色器正常显示：读取导航栏背后的实时页面，胶囊边缘对采样坐标进行弯曲，中心轻微放大，附带高光和少量色散。仅背景图层处理，前景图标/文字保持清晰。滚动列表时背景随内容变化。
9. 检查浅色、深色、font_scale=1.3 及小屏布局；字体和主题变化后透镜参数刷新。最后字体恢复 1.0、浅色，屏幕尺寸恢复。横屏截图未出现崩溃，页面仍可滚动。
10. 最终 APK 再次覆盖安装并检查首页、队列和横屏绘制。截图见 `screenshots/v1.4.0/`（video-ready 与 dark-large-font 截图来自最终参数缓存调整前的同布局版本）。

## 设计与实现参考

- [Apple Meet Liquid Glass](https://developer.apple.com/videos/play/wwdc2025/219/)：导航控件悬浮、折射、透镜及高光方向。
- [Android AGSL / RuntimeShader](https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl)：Android 图层着色器实现。
- [Compose Pager](https://developer.android.com/develop/ui/compose/layouts/pager)：两页切换。

## 验证边界

- 未请求 X 帖子、媒体元数据或下载视频；模拟器中的旧 X 记录封面被置空，避免 UI 图片加载请求 X。X 真机验证继续由用户进行。
- Apple 风格折射为本项目的 Android AGSL 实现；没有声称复刻系统 Liquid Glass 的全部交互和光学行为。
- 实时折射要求 Android 13+；Android 12 用平台模糊，Android 10/11 用透明色与高光。旧 Android 分支本轮未启动对应系统模拟器，按 API 版本门控；着色器创建失败会回退。
- 本轮未重复下载 B站；平台解析及图文处理单元测试全部保留。真机 GPU、FlClash 网络和登录态仍需用户验收。

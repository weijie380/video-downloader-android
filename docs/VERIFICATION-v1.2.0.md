# v1.2.0 验证记录

日期：2026-09-30。用户确认 X 中的视频需要登录；旧版没有向解析引擎提供 X 会话。手机 X 客户端的登录不会共享给此 App。

## 修改

- 新增官方 X 登录 WebView 入口和系统文件选择器导入 Netscape Cookie；必须用户操作，不自动请求帖子或下载。
- auth_token/ct0/twid 存在 App 私有 noBackup 目录，排除其他站点 Cookie；过期导入记录被拒绝，UI 保存状态不冒充认证成功。
- 解析和下载共用 RequestPolicy，以独立临时文件附带 Cookie；解析 finally 清理，下载目录 finally 清理。公开 syndication 不附带 Cookie，避免上游强制 GraphQL。
- 可清除 X 会话，只对 X 域的登录 Cookie 操作，不调用清除所有 Cookie。抖音代码与数据库迁移未变。

## 验证

- 最终离线 Gradle 构建成功：`testDebugUnitTest`、`assembleRelease`。
- 26 项单元测试，失败 0，错误 0；新增 6 项会话格式、域筛选、过期、CSRF 与控制字符检查，使用虚构令牌。
- Android 14 arm64 模拟器关闭 Wi-Fi 和移动数据后覆盖安装；7 条原任务仍保留，X 登录入口和管理页实际查看截图。
- 通过系统文件选择器导入虚构 Cookie，管理页显示保存成功；清除后显示访客模式，私有文件已删除，AndroidRuntime 无崩溃。
- 在 Android 上用临时离线诊断程序调用安装 APK 的真实 RequestPolicy：两个 GraphQL 请求得到不同快照且均附带 cookies；syndication 和 B站无 X Cookie，快照清理成功。
- 官方内置 yt-dlp 2026.08.19 离线读取 App 规范化的虚构 Cookie，TwitterIE.is_logged_in 为 true，api.x.com 可读取 ct0；未发送任何网络请求。
- APK 版本 1.2.0（versionCode 4），原签名，arm64，Android 10+。
- APK SHA256：`fa8ff1feadc23d856e8735e0df0f5d2ea6e063d087002f6bd013ab57a9ae054b`。

## 验证边界

没有访问 X 登录网页、帖子或媒体，没有实际账号凭据；官方 WebView 登录是否可完成、真实会话有效性、这条帖子的解析和下载须由用户测试。新增 Cookie 导入和本机请求参数已离线验证，不能据此宣称真实 X 下载成功。v1.1 的抖音下载结果仍见 VERIFICATION.md，本轮未重做平台下载。

安装：`dist/视频下载器-v1.2.0-arm64.apk`。开启 FlClash 并将下载器纳入 VPN → X 登录 → 官方网页登录 → 保存登录信息 → 关闭 → 失败任务重试。网页不支持登录时使用 Cookie 文件导入。

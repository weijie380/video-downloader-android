# v1.4.2 抖音 slides 修复验收

日期：2026-09-30。Android 14 arm64 模拟器，原 App 覆盖安装。

## 交付

- APK：`dist/视频下载器-v1.4.2-arm64.apk`；versionCode 8，versionName 1.4.2。
- 大小：77,520,592 bytes。
- SHA256：`dabfda0e3d3f48fcc2a16fb865024b8e04f2bb92a0c110bea0f067cabde80ed3`。
- 原签名：`f9d41eb7aef7c9f1742557d7b447c7c6fd12bd1ea47d6e1ec52447e1a205d9eb`。
- 保留 v1.4 的双页与折射导航、独立下载按钮，以及 v1.4.1 新图标。数据库仍为版本 3。

## 复现和原因

用户完整分享文字中的 `https://v.douyin.com/6LAGbhpYi_c/`，HTTP 302 跳转至官方 `/share/slides/7691196598550698993/`。旧正则只识别 video/note，无法提取 slides 作品编号，产生「没有找到抖音作品编号」错误。短链正常，不是从 mid 配乐参数获取作品编号。

原 slides 分享页面为客户端模板，不提供旧解析器读取的 _ROUTER_DATA 视频信息。在与 App 相同的官方游客会话下，同编号的 `/share/note/` 页面提供完整 item_list：aweme_type=2，images 五张，作者「小龙虾🦞joy」，标题「Hot #夏日度假风美女 #夏天度假风 #夏日度假style #夏季度假风 #夏日度假look」。

修复编号提取，并规范到同作品的官方 note 页；游客验证页面也处理 slides 跳转。未引入第三方解析服务或账号要求。

## 额外发现并修复

全新 App 进程从首页解析，尚未布局的队列 LazyColumn 使 scrollToItem 等待首次布局；导航事件又在这个等待后才切页，导致解析成功仍停在首页。改成 Compose 1.7 的 requestScrollToItem，请求下次布局定位，不阻塞页面切换。之前已访问队列的流程不触发此问题。

## 验证

- 最终 `:app:testDebugUnitTest :app:assembleRelease` 离线构建成功。
- 31 个单元测试通过，0 failures/errors：GalleryPolicy 5、UrlUtils 11、ResolverPolicy 9、XCookieFile 6。新增测试覆盖真实 slides 跳转、配乐编号混淆、直接 slides 地址以及非作品地址拒绝。
- 使用原始整段中文分享文字通过 Android ACTION_SEND 导入，从全新进程直接点击解析，没有提前访问队列。自动切页成功，任务 14 为 READY / douyin_gallery，正确编号及五张图片，预览显示。
- 点卡片「下载全部图片及文案」，实际完成，保存 001.webp—005.webp 及文案.txt，总计 1,522,738 bytes。未把对应 video 配套字段当成单独普通视频。
- 通过 MediaStore 原生读取已保存文件，逐张完整解码验证：前四张 1308×1744，第五张 1440×1920；六条记录 is_pending=0，字节数一致。文案 UTF-8 包含原标题、作者及规范来源链接。
- 初次修复生成的额外任务 13 保持 READY；下载仅启动选中任务 14。
- 截图见 `screenshots/v1.4.2/`。

| 文件 | Bytes | 尺寸 / 格式 | SHA256 |
| --- | ---: | --- | --- |
| 001.webp | 286,072 | [1308, 1744] | `f9e601fc74745e0465bed54c130567347fc349c05f86476599f6277b7e505ed5` |
| 002.webp | 172,804 | [1308, 1744] | `860da115016ab2e4ff10f3c62a0deebbcbb0dc7f360f75de8760f296f52e1f56` |
| 003.webp | 240,562 | [1308, 1744] | `cc037c53ac6f3a6dd494853a1599bb79800ab6835423c89e2a2ca8b80eed2d36` |
| 004.webp | 235,312 | [1308, 1744] | `92197c7b23d37be94aede3fb9487030a2e9568a6b94c1fa0a89d973d73988b03` |
| 005.webp | 587,796 | [1440, 1920] | `c8f07a878760a7324666526d93ae783bc3b96452f464ac8175ce742652636bc9` |
| 文案.txt | 192 | UTF-8 | `3677d8edeed074d41dcbf14a9577ff0e9568452058d095564f1fe33d4c3ccd0e` |

## 边界

真实验证限于用户提供的这条 slides 图文链接；其他内容仍受平台返回数据和验证要求影响。新版可对旧失败条目点击「重试」。未请求或下载 X 内容。游客验证 WebView 的 slides 规范路径由代码检查，主解析及下载完整实测通过。

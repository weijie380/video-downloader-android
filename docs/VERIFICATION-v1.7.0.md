# v1.7.0 生图功能验证

日期：2026-10-04。versionCode 12，Android 10+ arm64；下载队列与 Room 结构不变。

## 接入范围

- 第三个底部玻璃导航入口「生图」，支持点击、左右滑动和返回首页。生图页面使用独立 ViewModel，切换页面保留正在等待的请求及输入。
- 模型为 `qwen-image-2.0` 和 `wan2.7-image`，由 [基元律动公开模型目录](https://tokenrhythm.studio/models) 核对。页面通过 `GET /api/models` 更新两者的价格和可用性；价格获取失败时明确提示，以平台结算为准。
- 使用标准 `POST /v1/images/generations`：Bearer 鉴权，JSON `model / prompt / n=1`。响应处理 `data[].url` 或 `data[].b64_json`。原图下载使用独立无鉴权请求，只允许 HTTPS 重定向。
- 一次生成一张，支持等待状态、停止等待、全屏预览、私有持久作品历史、导出至 `下载/视频下载器/生图`。
- 密钥由 Android Keystore AES-GCM 加密后保存在手机 SharedPreferences 中；错误提示隐藏密钥，不内置账户凭据。应用已关闭系统备份。
- 计费生成请求不自动重试。取消只停止客户端等待，不能保证撤销服务端任务或计费；中断时提示查看平台调用记录。

## 平台验证限制

[平台公开接入文档](https://tokenrhythm.studio/docs/api-integration) 当前没有图片生成请求示例。实际无鉴权探测 `/v1/images/generations` 返回 401；不存在的 `/v1/does-not-exist` 返回 404，表明图片路由存在，但不证明鉴权后的参数、返回结构或付费生图成功。

没有使用用户 API Key、没有真实付费生成图片。两种模型按标准图片接口接入，最终平台出图仍需用户配置密钥后验证。若平台要求额外参数或异步任务协议，应根据实际生图调用示例调整。

未请求任何真实 X 帖子、预览、视频清单或媒体。

## 自动验证

- `:app:testDebugUnitTest`：53 项测试，0 失败。包含既有 44 项下载解析、选择和并发测试。
- 新增 9 项图片接口测试：两个模型的请求地址、Bearer 头和文字参数；401 错误不重试且不泄露凭据；取消等待停止请求；错误 HTML 不被当成图片；无效描述、模型、Base64、HTTP 或带凭据图片地址被拒绝；目录价格和状态解析；CDN 及重定向不收到 API Key 或 Cookie；HTTPS 不降级到 HTTP。
- `:app:connectedDebugAndroidTest`：Android 14 arm64 上 3 项测试，0 失败。密钥加密后读回、替换和清除；本机 PNG 写入作品索引后重新加载；MediaStore 导出字节完全一致；无效图片不新增作品记录。
- 在 release APK 上通过手动 instrumentation 再执行以上 3 项原生存储测试，全部通过。
- Release 构建、签名验证、`git diff --check` 通过；签名与旧版本一致。

## 最终 APK 的界面检查

- 点击生图入口，两个模型和实时价格正确显示；切换 Wan 模型、输入文字后滑回下载队列，再点击返回，输入仍保留。
- 用明确标注的测试密钥验证设置入口和重新启动后的解密读取，测试结束清除密钥。未点击真实生成请求。
- 点击本机测试图片打开全屏预览；关闭后点击保存，出现「已保存」，系统下载目录存在 PNG。
- 正常字号亮色页面、约 366dp 宽度深色页面、系统字体 2.0 倍检查。底部三个入口可见；大字模式生图顶栏隐藏副标题，避免固定顶栏裁切，长导航文字居中换行。
- `logcat AndroidRuntime` 没有崩溃。APK 仅包含 Python 插件源码，没有构建产生的 Python 字节码缓存。
- 截图：`screenshots/v1.7.0/image-page.png`、`local-image-saved.png`、`dark-large-font.png`。图片为 Android Canvas 合成的本机测试画面，**不是模型生成结果**。

## APK

- 路径：`dist/视频下载器-v1.7.0-arm64.apk`。
- 大小：77706465 字节。
- SHA256：`1312d55fb218570bf11b652527f7e03db2a68f273184d519571b6cd7e69f69a4`。
- 签名证书 SHA256：`f9d41eb7aef7c9f1742557d7b447c7c6fd12bd1ea47d6e1ec52447e1a205d9eb`，支持覆盖旧版。

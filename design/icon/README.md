# 冰蓝玻璃图标

2026-09-30。主体为下载箭头，箭身的播放镂空表明视频用途；冰蓝玻璃高光搭配深蓝底色，与 v1.4 的玻璃导航栏一致。

## 资产

- `glass-download-foreground.png`：内置 image_gen 生成的原始透明前景，1254×1254 RGBA，未修改像素。
- `launcher-preview.png`：Android 14 从安装包资源直接渲染的默认桌面形状，512×512。
- `launcher-circle.png`：Android Canvas 额外使用圆形 mask 检查裁切，512×512。
- `launcher-48.png`：Android 原生 48×48 小尺寸预览。
- `launcher-themed.png`：Android 13+ 单色主题层预览。

原图复制到 `app/src/main/res/drawable-nodpi/ic_launcher_glass_art.png`，使用比例 inset 保证不同大小一致。背景为 Android 矢量渐变；自适应图标在 mipmap-anydpi-v26，单色层在 mipmap-anydpi-v33。旧图标源码保留。使用内置 image_gen 工具，无 CLI/API fallback。

[Android 自适应图标规范](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)。按安全边距接入；实际检查默认形状、圆形及 48px，主体清晰且未被裁断。

## 生成提示词

```text
Use case: logo-brand. Asset type: final Android adaptive launcher icon foreground, square 1024 x 1024 transparent PNG. Design an original, stylish premium icon for a Chinese Android video downloader whose interface uses electric blue and Liquid Glass. Subject: ONE sculptural bold downward download arrow with a shallow rounded download tray below it, visually unified as a compact emblem; a small simple triangular PLAY-shaped negative-space cutout centered in the broad upper arrow stem makes the video function apparent. Silhouette must immediately read as download at 48 pixels. Materials: polished translucent ice-blue glass, liquid lens bevels, crisp white edge highlights, deep cobalt inner reflections, tiny cyan accents; restrained luxurious 3D depth, front-facing nearly orthographic view. Make forms broad, smooth, rounded, clean; not delicate, no clutter. Composition: transparent canvas with the COMPLETE emblem centered, emblem INCLUDING all highlights and subtle shadow occupies ONLY the central 58% of canvas width and 58% of height; keep a generous empty transparent margin on all four sides so Android circle/squircle masks cannot cut off the glyph. No background plate, NO rounded square tile, no environment, no floor, no visible frame, no extra icons, no letters, no text, no platform logos, no watermark. The glyph should look luminous and clear against a very dark navy app-icon background which will be provided separately. This is a production icon asset, not a mockup, not a presentation board.
```

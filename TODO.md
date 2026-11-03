# 项目待办

## 待处理

- [ ] **核验并精简 MMT TTML 的时间继承兼容补丁**（记录于 2026-10-31；更新于 2026-11-03）

  Media3 1.11.0 已修复子节点缺少 `begin` 时继承父节点起始时间的问题，1.11.1 已包含修复。
  原来为 `body/div/p/span` 逐一补写 `begin="0s"` 的处理已精简：删除 `div/p/span` 的补写，保留缺少 `begin` 的 `body` 起点。
  对照中发现：完全没有 `begin`、只写 `end` 或 `dur` 的文档，直接交给 1.11.1 仍不能正确输出字幕，因此暂不能删除 `body` 的兜底。
  MMT 数据单元组装、媒体时间轴换算、显式 `begin/end` 和缺失画布尺寸补齐保持原有行为。

  实施与验收：

  - [x] 核对官方修复，并用原始 TTML 验证父级 `div` 计时、嵌套容器与子节点显式时间。
  - [x] 保存旧补丁下的 MMT 字幕基线；真实样本保留在仓库外。
  - [x] 精简逐节点补写，补充仅 `end/dur` 的边界回归。
  - [x] 编译通过，13 项 MMT 核心测试通过。
  - [x] 精简后的 8 项手机字幕对照测试通过，3 份原始 TTML 及 MMT 流中的 5 个字幕样本文字、时间、位置与旧基线一致。
  - [x] 在 24129PN74C 上验证实时 MMT 播放进入就绪、进度推进及两个字幕轨道切换。
  - [x] 根据实测截图修复广播像素字号缩放、字幕层与视频区域对齐及订阅监听生命周期；新增 5 项字号和实际绘制回归，与原 8 项对照合计 13 项手机测试通过，实时播放及双字幕轨道切换复测通过。
  - [ ] 手动确认实际画面中的字幕显示，确认后勾选整个任务。

  当前限制：此前 CMA-AN40 上的无界面播放测试未进入就绪状态，期间日志出现
  `OMX.MTK.VIDEO.DECODER.HEVC` 解码异常。改用 24129PN74C 后，实时播放及两个字幕轨道切换测试通过，
  8 项字幕对照测试也通过。随后用户截图发现字号过大、重叠及溢出；已增加 MMT 专用像素字号适配，
  字幕层跟随视频区域缩放并裁剪，修正监听注册与释放。13 项字幕解析/绘制测试及实时播放、轨道切换复测通过；
  修复后的真实节目字幕仍待用户手动确认，不能以解析对照通过替代显示验收。

  相关代码与测试：

  - `core/data/src/main/java/top/yogiczy/mytv/core/data/media/mmt/MmtSubtitle.kt`
  - `core/data/src/test/java/top/yogiczy/mytv/core/data/media/mmt/MmtSubtitleTest.kt`
  - `tv/src/androidTest/java/top/yogiczy/mytv/tv/player/MmtSubtitleDecodeTest.kt`
  - `tv/src/main/java/top/yogiczy/mytv/tv/ui/screensold/videoplayer/player/mmt/MmtTtmlParser.kt`
  - `tv/src/androidTest/java/top/yogiczy/mytv/tv/player/MmtSubtitleRenderingTest.kt`

  官方依据：[问题 #3246](https://github.com/androidx/media/issues/3246)、[Media3 1.11.0 更新说明](https://developer.android.com/jetpack/androidx/releases/media3#1.11.0)。

## 使用约定

- 新任务记录背景和验收条件，完成后勾选；仅记录待办不代表已修改或验证功能。
- Git 提交仍需先确认具体描述，待办完成不自动提交或推送。

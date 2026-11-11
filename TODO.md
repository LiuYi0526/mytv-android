# 项目待办

## 已完成

- [x] **支持 HLS 显式媒体类型，避免无扩展名入口重复起播请求**（记录于 2026-11-05；完成于 2026-11-11）

  `#KODIPROP:inputstream.adaptive.manifest_type=hls` 现在直接选择现有 Media3 HLS 媒体源，
  兼容 `m3u8` 别名、大小写、双引号及首尾空白；无需清理已有频道缓存。
  未指定或未知值继续使用 URL 类型推断及原来的解析回退；无扩展名源需在 M3U 中明确指定才能避免探测请求。
  按实际选定类型记录尝试，显式类型遇到不支持的容器时不再反复重建同一种媒体源。

  - [x] 覆盖参数归属、分组转换、频道缓存往返、旧数据兼容及媒体源选择。
  - [x] 无线设备上的本地 HTTP 直返及 302 入口均只请求一次；正常刷新/网络重试不在此计数保证内。
  - [x] 同源认证、UA、自定义头、Cookie 以及 AES-128 密钥/分片请求回归通过。
  - [x] 保留 `.m3u8` 自动识别和无扩展名回退；普通 TS、DASH 媒体源准备及 Widevine/ClearKey 配置回归通过。
  - [x] 编译、43 项数据层测试及 25 项设备测试通过，已安装到 24129PN74C。

  本次 HTTP 设备用例使用固定合成轨道检查媒体源准备和请求次数，不代替具体运营商频道的实际解码验收。
  参考 APK 之前已静态对照：mytv3 3.0.0.230 支持 `manifest_type=hls`；YYKM 3.8.4-1 complete
  使用 `content-type=hls`；C天光云影C 1.17.2 保存 `manifest_type`，但未发现 Media3 的 HLS 显式选型。
  本次仅接入上述 Kodi 参数，不扩展其它语法。

- [x] **精简 Widevine 标准配置路径**（2026-11-03）

  使用 `MediaItem.DrmConfiguration` 与 `DefaultDrmSessionManagerProvider` 替代手动会话组装，
  保留订阅许可证代理优先、URL/显式认证、频道/全局 UA、请求头及独立许可证网络客户端。
  多会话和明文片段播放沿用原先的启用值，ClearKey 仍使用原有独立路径。
  编译通过；24129PN74C 上 8 项 Widevine 配置/HTTP 请求和 4 项 ClearKey/DASH 回归通过，
  包括原始二进制 POST、provisioning、显式 Authorization、同名 UA 请求头顺序和 Cookie 行为。
  已保留数据安装调试版，用户已确认实际 Widevine 频道播放正常。

- [x] **清理无效的“跳过多帧渲染”开关**（2026-11-03）

  移除播放器设置入口、ViewModel 状态、持久化访问和配置导出字段，以及已注释的播放器接入。
  旧本地值无需清空；旧 JSON 字段通过现有 `ignoreUnknownKeys` 兼容读取，不再导出或应用。

- [x] **核验并精简 MMT TTML 的时间继承兼容补丁**（记录于 2026-10-31；更新于 2026-11-03）

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
  - [x] 用户反馈之前的 MMT 源字幕应该正常，完成该源的显示验收。

  当前限制：此前 CMA-AN40 上的无界面播放测试未进入就绪状态，期间日志出现
  `OMX.MTK.VIDEO.DECODER.HEVC` 解码异常。改用 24129PN74C 后，实时播放及两个字幕轨道切换测试通过，
  8 项字幕对照测试也通过。随后用户截图发现字号过大、重叠及溢出；已增加 MMT 专用像素字号适配，
  字幕层跟随视频区域缩放并裁剪，修正监听注册与释放。13 项字幕解析/绘制测试及实时播放、轨道切换复测通过；
  用户随后反馈之前的源应该正常；本任务据此完成，新的 NHK G MPEG-TS 字幕兼容另行记录。

  相关代码与测试：

  - `core/data/src/main/java/top/yogiczy/mytv/core/data/media/mmt/MmtSubtitle.kt`
  - `core/data/src/test/java/top/yogiczy/mytv/core/data/media/mmt/MmtSubtitleTest.kt`
  - `tv/src/androidTest/java/top/yogiczy/mytv/tv/player/MmtSubtitleDecodeTest.kt`
  - `tv/src/main/java/top/yogiczy/mytv/tv/ui/screensold/videoplayer/player/mmt/MmtTtmlParser.kt`
  - `tv/src/androidTest/java/top/yogiczy/mytv/tv/player/MmtSubtitleRenderingTest.kt`

  官方依据：[问题 #3246](https://github.com/androidx/media/issues/3246)、[Media3 1.11.0 更新说明](https://developer.android.com/jetpack/androidx/releases/media3#1.11.0)。

## 待处理

- [ ] **播放快捷栏软硬解优先的直播实测验收**（2026-11-03）

  已参考 YYKM 3.8.4 与 mytv3 3.0.0.230 的代码；确认后者使用 Media3 1.11.1。
  新增“硬解优先 / 软解优先”切换，默认硬解优先；选择在当前播放会话内沿用，切换内核后保留。
  Media3 复用官方 `PREFER_SOFTWARE`，保留硬件候选与安全解码要求，音视频自动回退分别处理；
  IJK 通过 MediaCodec 开关切换 FFmpeg 视频软解。不新增全局配置，不改变独立的“强制音频软解”设置。

  - [x] 编译通过，保留数据安装到 USB 手机 CMA-AN40。
  - [x] 三项功能回归通过：实际 Media3/IJK 视频解码器切换、暂停与位置保留、触摸/遥控各触发一次。
  - [x] Media3 额外验证音量、显示模式保留，以及音频失败回退不改变视频解码策略。
  - [x] 8 项 Widevine 与 4 项 ClearKey/DASH 回归通过，合计 15 项手机测试通过。
  - [ ] 用户在真实直播源中验证切换效果及切换内核后的会话偏好。

- [x] **支持 MPEG-TS 中通过 ID3 传输的 ARIB STD-B24 字幕**（记录于 2026-11-03；实现及自动验证于 2026-11-07）

  用户提供的 NHK G HEVC 1080p 源返回 MPEG-TS。抓取样本确认 PMT 中 PID `0x103` 为 `stream_type=0x15`，
  ID3 `PRIV` 帧 owner 为 `aribb24.js`，含日语字幕管理数据及正文数据，不属于 MMT/TTML。
  Media3 保留原有 TS/HLS 元数据提取和播放时钟调度，通过 ARIB 字幕桥接在现有菜单中展示已发现的字幕服务。
  接入固定源码的 libaribcaption 1.1.2 和 FreeType 2.14.3，支持 JIS/UTF-8 字形、字幕/叠加字幕选择、关闭和清屏。
  原生解码在独立有界工作线程运行，位图按广播画布映射至视频区域；暂停/缓冲沿用媒体时钟，跳转和切源清理旧状态。
  四架构原生 AAR、JNI 源码、许可及重建脚本已加入，16 KB 静态对齐检查通过。

  - [x] 确认实际封装、ID3 owner 与字幕正文存在；样本保留在仓库外。
  - [x] 接入 ID3 内 ARIB 字幕识别、解码和时间同步，支持字幕选择与关闭。
  - [x] 21 项数据层回归通过（8 项新增 ARIB 封装回归及 13 项 MMT 解析回归）。
  - [x] 5 项 ARIB 设备回归通过：字形实际像素与位置、UTF-8 日文/叠加、清屏、选择/关闭/重置、暂停媒体时钟。
  - [x] 原有 13 项 MMT TTML 字幕设备回归通过。
  - [x] USB 手机 CMA-AN40 上 NHK G 实流验证通过：视频出帧、字幕服务发现、选择后绘出位图、切源恢复普通字幕选择。
  - [x] 用户于 2026-11-08 反馈实际播放未发现问题。仅覆盖 ID3 封装；原始 ARIB 私有 PES 和 IJK 字幕另行评估。

## 使用约定

- 新任务记录背景和验收条件，完成后勾选；仅记录待办不代表已修改或验证功能。
- Git 提交仍需先确认具体描述，待办完成不自动提交或推送。

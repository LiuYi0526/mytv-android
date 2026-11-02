# 流光 TV

面向 Android TV、电视盒子及横屏触摸设备的直播播放器，基于 [天光云影](https://github.com/yaoxieyoulei/mytv-android) 3.3.10 继续开发。

- 支持 Android **6.0 / API 23** 及以上。
- 不内置个人直播源，首次使用可扫码或手动添加订阅。
- 支持遥控器和触摸操作、首页最近观看、收藏及设置分类入口。
- 支持独立节目单管理、订阅关联及 M3U 节目单地址识别。
- 提供 Media3 和 IJK 播放内核；协议和解码能力依内核、设备及直播源而异。

## 下载

前往 [GitHub Releases](https://github.com/LiuYi0526/mytv-android/releases) 下载正式签名 APK：

| 文件后缀 | 适用设备 |
| --- | --- |
| `armeabi-v7a.apk` | 32 位 ARM 系统 |
| `arm64-v8a.apk` | 64 位 ARM 系统 |
| `universal.apk` | 包含 ARM32、ARM64、x86、x86_64 的通用包 |

按系统支持的 ABI 选择安装包；64 位芯片也可能运行 32 位 Android。无法确定时可使用 universal 包。
正式版包名为 `io.github.liuyi0526.mytv`，调试版为 `io.github.liuyi0526.mytv.dev`。
两者与之前的测试应用分别保存数据，更换包名前请先导出配置。

## 首次使用

1. 在首页打开“设置 → 订阅源”，扫码或手动添加订阅链接。
2. M3U 中的 `url-tvg` / `x-tvg-url` 可自动识别为节目单；也可单独管理默认节目单并关联订阅。
3. 在“设置 → 播放器”选择内核。Media3 提供本项目扩展的 RTSP MP2T、RTMP、DASH DRM 及 MMT/TLV 支持。
4. 遥控器确认键打开频道列表，左右方向键切换相关面板；触摸操作及按键映射可在“控制”中调整。
5. 手机或电脑与设备处于同一局域网时，可访问 `http://<设备IP>:10481` 配置。

已知限制：RTSP、RTMP 等非 HTTP 线路暂不进行线路测速，界面会显示“暂不支持测速”。

## 反馈与版本

- 问题反馈：[GitHub Issues](https://github.com/LiuYi0526/mytv-android/issues)。请说明设备、系统、内核、操作步骤及可公开的日志。
- 更新日志：[CHANGELOG.md](CHANGELOG.md)。应用首版仅提供稳定更新通道。
- 正式构建、签名、备份及 GitHub Actions 使用方法：[发布指南](docs/RELEASING.md)。

## 开源来源

保留原项目的 [MIT 许可证](LICENSE) 与作者版权声明。
播放器及原生依赖的来源、许可证和重建说明见 [THIRD_PARTY.md](docs/THIRD_PARTY.md)。

感谢 [天光云影](https://github.com/yaoxieyoulei/mytv-android)、[my-tv](https://github.com/lizongying/my-tv)、
[AndroidX Media](https://github.com/androidx/media)、[fanmingming/live](https://github.com/fanmingming/live) 等上游项目。

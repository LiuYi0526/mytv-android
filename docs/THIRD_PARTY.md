# 上游与第三方组件

流光 TV 基于 [天光云影](https://github.com/yaoxieyoulei/mytv-android) 3.3.10 继续开发。
原项目的 MIT 版权及许可全文保留在仓库根目录 `LICENSE`。

| 组件 | 来源与说明 |
| --- | --- |
| AndroidX / Media3 | [androidx/media](https://github.com/androidx/media)，Apache-2.0；版本及兼容库说明见 `scripts/media3/README.md` |
| Media3 HLS、RTSP 兼容 AAR | 基于固定版本官方 AAR 重建部分 Java 类；保留上游许可，源码补丁及重建脚本位于 `scripts/media3/` |
| RTMP 客户端 | [ant-media/LibRtmp-Client-for-Android](https://github.com/ant-media/LibRtmp-Client-for-Android)；AAR 内保留客户端许可及 librtmp 的 COPYING，16 KB 重建说明见 `scripts/native/README.md` |
| FFmpeg、IJK 及原生音频组件 | 各组件以其上游许可证为准；固定来源、构建参数、JNI 修改及重建脚本见 `scripts/native/README.md`、`scripts/media3/README.md` |
| GSYVideoPlayer | [CarGuo/GSYVideoPlayer](https://github.com/CarGuo/GSYVideoPlayer)，Java 与原生依赖版本见 `tv/build.gradle.kts` |
| 其它 Maven 依赖 | 版本见 `gradle/libs.versions.toml` 和各模块 Gradle 文件，许可声明以对应依赖包及上游项目为准 |

发布二进制时，请保留本说明、根许可证以及兼容 AAR 中的上游许可文件，并同时提供该版本的源码标签与重建说明。
本项目没有将参考应用的 APK 作为自身源代码发布。

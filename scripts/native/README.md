# Android 16 KB 原生库

`tv/libs/lib-decoder-ffmpeg-release.aar` 保留原有 Java 类、资源和两个 32 位 JNI 库。
仅重编译 `arm64-v8a`、`x86_64`，继续提供 AV3A（菁彩声）解码及双耳渲染。
没有通过修改 ELF 头或隐藏系统兼容提示处理对齐。

## 固定源码与工具链

- Android NDK **r27d**，Linux x86_64，Android API **21**。
- [Sourcecodeforplayer](https://github.com/lioumin1/Sourcecodeforplayer/tree/e7d244d29454eb04c968cd98a30587303a9c15f8)，提交 `e7d244d29454eb04c968cd98a30587303a9c15f8`。
  使用其中的 FFmpeg 6.1、AVS3AudioDec、av3a_binaural_render；保留各自的源代码许可。
- 本目录 `ffmpeg_jni.cc` 来自 [AndroidX Media 1.4.1](https://github.com/androidx/media/blob/1.4.1/libraries/decoder_ffmpeg/src/main/jni/ffmpeg_jni.cc)，保留 Apache 2.0 文件头。
  SHA-256：`7b5421215e963e099004eb04528dcd39ca36869ab9e2412ff5e3b950980daad9`。

需要 Python 3、GNU make、Linux/WSL。在上述固定提交的**独立源码副本**中构建：

```sh
python3 scripts/native/build_ffmpeg_16k.py \
  --source-dir /path/to/Sourcecodeforplayer \
  --ndk /path/to/android-ndk-r27d \
  --output /path/to/native-build \
  --aar tv/libs/lib-decoder-ffmpeg-release.aar
```

脚本将 AV3A 包装层的 `dlopen/dlsym` 改为静态链接，同时把原包装文件保存为
`libarcdav3a.c.original`，因此重复构建仍从相同源码生成。AV3A 解码器和渲染器源码未改动。
JNI 使用 `-Wl,-z,max-page-size=16384`、`-Wl,-z,common-page-size=16384`，
静态链接 C++ 运行库，不增加额外的动态库依赖。

## Maven 依赖

- Sentry SDK 固定为 **7.22.6**，其两个 64 位原生库已按 16 KB 对齐；Gradle 插件版本保持不变。
- GSY 的原生库 AAR 使用 **12.0.0**，修复旧版 x86_64 IJK 对齐；Java 层保持 **10.0.0**。
  该 AAR 的 `classes.jar` 为空，64 位 ELF 标记目标 API 21，四种架构的强导入符号均可由
  NDK API 21 系统库及同包 IJK 库提供。因此仅对 `com.shuyu.gsyvideoplayer.ex_so` 覆盖其
  清单的 minSdk 23 声明，保持应用 minSdk 21。不要将此覆盖扩展到 GSY Java 模块。

## 验证

```sh
python3 scripts/check_native_alignment.py tv/libs/lib-decoder-ffmpeg-release.aar
./gradlew :tv:assembleOriginalDebug
python3 scripts/check_native_alignment.py /path/to/built.apk
zipalign -c -P 16 4 /path/to/built.apk
```

检查脚本验证每个 64 位 ELF 的所有 LOAD 段，以及 APK 中未压缩库的数据起始位置。
32 位 Android 不使用 16 KB 页，原有 32 位库保持不变。
替换后还应验证 Media3 音频软解、IJK 播放和 AV3A 初始化。
4 KB 真机播放通过不等价于完成 16 KB 设备运行验证。

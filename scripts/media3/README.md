# Media3 1.4.1 AV3A / HLS compatibility

The app uses the official Media3 1.4.1 APIs, with an AV3A TS payload reader in
`tv/.../videoplayer/player/av3a` and bounded AATF parsing in `core/data/.../media`.
Stream type `0xD5` is reported as `audio/av3a`. The existing FFmpeg extension
decodes this MIME type with `libarcdav3a`.

## Why HLS also needs a patch

Official `HlsSampleStreamWrapper.getMappedTrackOutput` maps audio by type, retaining
only the first audio ID. A TS containing AAC (`15`) and AV3A (`213`) consequently
loses AV3A even when the TS reader understands it. The local
`tv/libs/media3-exoplayer-hls-1.4.1-multi-audio.aar` replaces only this wrapper and
its nested classes. All other classes and AAR entries originate from the official
Google Maven artifact (Apache-2.0).
Copyright (C) 2016 The Android Open Source Project. The upstream license is
included in `LICENSE-2.0.txt`; the audio-queue modifications are described below.

The patch keeps separate audio queues by ID before `endTracks`, preserves stable
IDs across extractor recreation, and retains upstream ID remapping for a single
audio queue. Unknown IDs after `endTracks` remain discarded. Video and metadata
mapping is unchanged. This fixes multiplexed audio with different TS stream types;
it does not extend Media3's TS support to multiple tracks of the same stream type.
HLS chunkless preparation is disabled so tracks are discovered from the segments.

Upstream sources:
- https://github.com/androidx/media/tree/1.4.1/libraries/exoplayer_hls
- https://dl.google.com/dl/android/maven2/androidx/media3/media3-exoplayer-hls/1.4.1/media3-exoplayer-hls-1.4.1-sources.jar
- AV3A header reference: https://github.com/yaoxieyoulei/media/tree/av3a_1.4.1

## Rebuild

Run `build_hls_compat.py` with Python 3 and JDK 17. Inputs are verified against
pinned SHA-256 hashes before patching. Supply the official 1.4.1 AAR and sources
JAR, plus a UTF-8 file containing the platform-separated Java compile classpath
for the TV app, `android.jar`, and Checker Framework's `checker-qual` JAR.
The TV classpath can be exported from Gradle's
`tasks.named("compileOriginalDebugJavaWithJavac").get().classpath.asPath`.

```text
python scripts/media3/build_hls_compat.py --aar <official.aar> --sources <sources.jar> --classpath <classpath.txt> --javac <JDK17/bin/javac> --output tv/libs/media3-exoplayer-hls-1.4.1-multi-audio.aar
```

The patch itself is readable in the script's `patch` function. A source-retained
`HlsChunkSource.ChunkPublicationState` annotation is also removed because its class
is omitted from the published AAR. The local `../media` source-tree build path
continues to use its own HLS module; apply the same mapping patch there if used.
Do not upgrade other Media3 artifacts without rebuilding and reviewing this patch.

## Verification

- `Av3aStreamParserTest`: fragmented headers/frames, invalid data, resync, timing,
  channel/object profiles, and bounds.
- `Av3aExtractorsTest`: progressive and HLS TS extraction, WebVTT fallback, FFmpeg MIME support.
- `Av3aHlsPlaybackTest`: prepares a complete HLS MediaSource and checks both AAC and
  10-channel AV3A in the player's track groups. The generated frames are synthetic,
  so audio rendering is disabled. Regenerate with `generate_av3a_test_stream.py`.
- `Av3aDecodeTest`: optional real-stream probe. Place a bounded AV3A elementary
  stream in the debug app's cache and pass `-e av3aSample <filename>` to the
  instrumentation runner. Checks decoded PCM size, sample rate, channels and
  nonzero amplitude. Real stream samples, URLs and credentials stay outside Git.

After installation, verify the actual channel exposes both tracks and switching
to AV3A initializes `ffmpeg…-libarcdav3a`. Run `scripts/check_native_alignment.py`
against the final APK to retain the 16 KB alignment regression check.

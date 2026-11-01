# Media3 1.11.1 AV3A / HLS compatibility

The app uses the official Media3 1.11.1 APIs (Android API 23+), with an AV3A TS payload reader in
`tv/.../videoplayer/player/av3a` and bounded AATF parsing in `core/data/.../media`.
Stream type `0xD5` is reported as `audio/av3a`. The existing FFmpeg extension
decodes this MIME type with `libarcdav3a`.

## Why HLS also needs a patch

Official `HlsSampleStreamWrapper.getMappedTrackOutput` maps audio by type, retaining
only the first audio ID. A TS containing AAC (`15`) and AV3A (`213`) consequently
loses AV3A even when the TS reader understands it. The local
`tv/libs/media3-exoplayer-hls-1.11.1-multi-audio.aar` replaces only this wrapper and
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
- https://github.com/androidx/media/tree/1.11.1/libraries/exoplayer_hls
- https://dl.google.com/dl/android/maven2/androidx/media3/media3-exoplayer-hls/1.11.1/media3-exoplayer-hls-1.11.1-sources.jar
- AV3A header reference: https://github.com/yaoxieyoulei/media/tree/av3a_1.4.1

## Rebuild

Run `build_hls_compat.py` with Python 3 and JDK 17. Inputs are verified against
pinned SHA-256 hashes before patching. Supply the official 1.11.1 AAR and sources
JAR, plus a UTF-8 file containing the platform-separated Java compile classpath
for the TV app, `android.jar`, and Checker Framework's `checker-qual` JAR.
The TV classpath can be exported from Gradle's
`tasks.named("compileOriginalDebugJavaWithJavac").get().classpath.asPath`.

```text
python scripts/media3/build_hls_compat.py --aar <official-1.11.1.aar> --sources <sources.jar> --classpath <classpath.txt> --javac <JDK17/bin/javac> --output tv/libs/media3-exoplayer-hls-1.11.1-multi-audio.aar
```

The patch itself is readable in the script's `patch` function. A source-retained
`HlsChunkSource.ChunkPublicationState` annotation is also removed because its class
is omitted from the published AAR. The local `../media` source-tree build path
continues to use its own HLS module; apply the same mapping patch there if used.
Do not upgrade other Media3 artifacts without rebuilding and reviewing this patch.

## FFmpeg renderer compatibility

`ffmpeg/FfmpegAudioRenderer.java` is the Apache-2.0 renderer from Media3 1.11.1,
with the existing AV3A 10-channel fallback retained for Android API < 32.
The new `DecoderAudioRenderer.getChannelMapping` returns `ImmutableIntArray`;
the old binary's `int[]` override would no longer be called.

`build_ffmpeg_compat.py` replaces only the renderer class in the pre-upgrade,
16 KB aligned `lib-decoder-ffmpeg-release.aar` (SHA-256 pinned in the script).
Obtain that input AAR from the Git revision before this upgrade. Decoder classes,
JNI interfaces, all four ABI native libraries, and AV3A/binaural decoding remain
byte-for-byte unchanged. The source is readable and can be compiled again:

```text
python scripts/media3/build_ffmpeg_compat.py --aar <pre-upgrade-ffmpeg.aar> --classpath <classpath.txt> --javac <JDK17/bin/javac> --output tv/libs/lib-decoder-ffmpeg-release.aar
```

Upstream renderer:
https://github.com/androidx/media/blob/1.11.1/libraries/decoder_ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegAudioRenderer.java

The app's Kotlin and Compose compiler plugins use 2.2.10 to read the Kotlin 2.2
metadata in the new dependencies. AGP and Gradle are unchanged. DRM callbacks
now return `MediaDrmCallback.Response`; TS readers use `endOfInputReached` and
the new constructors, and the MMT subtitle probe uses the offset/length cue API.

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

## RTSP MPEG-TS compatibility

`tv/libs/media3-exoplayer-rtsp-1.11.1-mp2t.aar` preserves the official RTSP
artifact except for the classes rebuilt by `build_rtsp_compat.py`. Both input
archives are SHA-256 pinned; the script performs no downloads. The original
license/notice entries are retained. Rebuild with JDK 17:

```text
python scripts/media3/build_rtsp_compat.py --aar <official-1.11.1-rtsp.aar> --sources <official-1.11.1-rtsp-sources.jar> --classpath <classpath.txt> --javac <JDK17/bin/javac> --output tv/libs/media3-exoplayer-rtsp-1.11.1-mp2t.aar
```

The compile classpath needs Android SDK `android.jar`, Media3 1.11.1 ExoPlayer,
extractor/common/container/datasource JARs, Guava, AndroidX annotations,
`checker-qual` and `error_prone_annotations`. The script prepends the official
RTSP classes to that classpath. The source checkout path (`../media`) must apply
the same RTSP changes, including `Factory.setTsPayloadReaderFactory`, before use.

Changes are scoped to RTSP:

- Interpret static payload type 33 as MP2T/90000, recognize dynamic MP2T and
  resolve missing media `control` against the session's Content-Base/URL.
- `rtsp/RtpMp2tReader.java` unwraps 188-byte TS or 192-byte M2TS, retaining partial
  packets across RTP boundaries and discarding unfinished bytes after RTP loss.
  TS PTS/PCR control sample timestamps. The app supplies `Av3aPayloadReaderFactory`.
  Feed complete TS packets without signaling extractor EOF at RTP boundaries:
  EOF flushes an incomplete unbounded PES/video access unit in Media3 1.11.1.
- Start SETUP/PLAY for MP2T before track selection, because PAT/PMT are transmitted
  in-band. Wait for TS endTracks/formats rather than prematurely finishing preparation.
- Keep separate sample queues by elementary track ID, deduplicate transport SETUP
  when multiple extracted tracks share one RTP stream, and resolve sample streams
  against current loaders after UDP-to-TCP fallback. Release replaced loaders,
  discard unread data for unselected tracks, and switch TS audio without a new SETUP.
- If PLAY receives no UDP RTP packets, probe for at most 1.5 seconds before trying
  TCP. Keep the user's full packet timeout after UDP reception starts and for TCP.
- Reconnect the control socket after 301/302, including a different host or port,
  and limit redirect loops. Reset session/challenge state; only retain implicit
  credentials for the same server. Retain the upstream ordinary RTP readers.

`RtspMp2tTest` covers SDP compatibility, fragmented TS/M2TS, sequence wrap/loss,
seek reset, multi-audio preparation over UDP/TCP, same/cross-server 302, TCP fallback,
ordinary PCMU RTSP and disabled tracks. Synthetic AAC/AV3A is never decoded. Its optional
`optionalDeviceSourceRendersAFrame` test accepts `-e rtspUrl <url>` and
`-e rtspTcp true|false`, mutes audio and requires an actually decoded video frame.
`-e rtspSustain true` also requires over 100 additional scheduled frames in eight
seconds; `-e rtspTimeout <milliseconds>` overrides the normal 15-second timeout.
Regression tests also ensure an RTP boundary never sends elementary-stream EOF,
and silent UDP falls back before the full configured packet timeout expires.
Private sample URLs are passed at invocation time, never committed as fixtures.

After installation, verify the actual channel exposes both tracks and switching
to AV3A initializes `ffmpeg…-libarcdav3a`. Run `scripts/check_native_alignment.py`
against the final APK to retain the 16 KB alignment regression check.

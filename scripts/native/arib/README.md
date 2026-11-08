# ARIB STD-B24 timed-ID3 captions

`tv/libs/libaribcaption-1.1.2-16k.aar` contains a JNI bridge, libaribcaption's
decoder/renderer, and statically linked FreeType. It includes ARM32, ARM64, x86,
and x86_64, targets Android API 23 and uses 16 KB ELF LOAD alignment. System
Japanese font fallback is handled by libaribcaption's Android font provider.
No WebView, remote JavaScript, or downloaded caption fonts are used.

## Pinned sources and rebuild

- [libaribcaption 1.1.2](https://github.com/xqq/libaribcaption/tree/c64c23b8905ba514b87c9789269e9f66f949ffe0),
  ZIP SHA-256 `709ca0d5c4076de645dc8a1f1546c5c4c1214058a0ee86cab061a94221e366b4`.
- [FreeType 2.14.3](https://github.com/freetype/freetype/tree/VER-2-14-3),
  ZIP SHA-256 `3f624983444f9e9149707853a792dc5a897f3afc9f23a9b8669c03ac564779d1`.
- Source archives are `https://codeload.github.com/xqq/libaribcaption/zip/refs/tags/v1.1.2`
  and `https://codeload.github.com/freetype/freetype/zip/refs/tags/VER-2-14-3`.
- Linux/WSL, NDK r27d (`27.3.13750724`), CMake >= 3.28 and GNU make.

```sh
python3 scripts/native/build_aribcaption.py \
  --source-zip /path/to/libaribcaption-v1.1.2.zip \
  --freetype-zip /path/to/freetype-2.14.3.zip \
  --ndk /path/to/android-ndk-r27d --cmake /path/to/cmake \
  --output tv/libs/libaribcaption-1.1.2-16k.aar
```

The script verifies both archives before extraction and overrides CMake's
FreeType FetchContent source with the pinned local tree; compilation performs
no source downloads. Native libraries use a static C++ runtime. License notices
are embedded in the AAR under `META-INF/licenses/` (libaribcaption MIT/ISC,
FreeType FTL and tinyxml2).

## Playback integration

Media3's existing TS/HLS metadata extraction and media-clock scheduling remain
in use. `PrivFrame` entries with owner `aribb24.js` carry an ARIB PES payload;
unrelated metadata never creates an ARIB menu entry. `AribCaptionPacket`
validates bounds and discovers caption/superimpose languages. The bridge exposes
only discovered services through the existing subtitle menu, with negative
internal indexes distinct from Media3's native text indexes.

The native decoder retains management/DRCS state even while a service is not
selected. A dedicated bounded worker performs decoding and bitmap rendering;
only the selected service publishes cues. Original caption-plane coordinates
become fractional bitmap cues, so subtitle size follows the video viewport
rather than Android font density. Display and finite expiry use the player's
clock, including pause/rebuffer. Seeks, source changes and release clear queued
presentation and stale decoder state; all native sessions close on their worker.

This integration handles timed-ID3 encapsulation used by tsreadex/aribb24.js.
Raw ARIB private PES streams without ID3 and IJK's separate subtitle path are
outside this bridge. The decoder supports the two languages allowed by the
broadcast profile, JIS/UTF-8 text, colors, positions, gaiji and DRCS rendering.

## Verification

- `AribCaptionPacketTest`: management/statement bounds, two languages, display
  conditions, original-time header, malformed payloads, unrelated metadata.
- `AribSubtitleTest`: native glyph pixels/position, Japanese UTF-8,
  superimpose, clear-screen, selection/off/reset, and an optional real-source
  check supplied as instrumentation argument `aribUrl`.
- Real stream samples remain outside Git. `scripts/check_native_alignment.py`
  checks the final APK; static alignment is not a 16 KB hardware playback test.

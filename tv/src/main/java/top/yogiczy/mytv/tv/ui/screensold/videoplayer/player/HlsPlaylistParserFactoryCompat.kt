package top.yogiczy.mytv.tv.ui.screensold.videoplayer.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.BufferedInputStream

/** Media3 1.11.1's Reader-based header check does not discard a UTF-8 BOM. */
@OptIn(UnstableApi::class)
internal class HlsPlaylistParserFactoryCompat : HlsPlaylistParserFactory {
    private val delegate = DefaultHlsPlaylistParserFactory()

    override fun createPlaylistParser() = withoutBom(delegate.createPlaylistParser())

    override fun createPlaylistParser(multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?) =
        withoutBom(delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist))

    private fun withoutBom(parser: ParsingLoadable.Parser<HlsPlaylist>) =
        ParsingLoadable.Parser<HlsPlaylist> { uri, input ->
            val buffered = BufferedInputStream(input)
            buffered.mark(3)
            if (buffered.read() != 0xEF || buffered.read() != 0xBB || buffered.read() != 0xBF) buffered.reset()
            parser.parse(uri, buffered)
        }
}

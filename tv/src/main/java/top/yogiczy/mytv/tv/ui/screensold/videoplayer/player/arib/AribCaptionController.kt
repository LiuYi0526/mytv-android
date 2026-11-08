package top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.arib

import android.util.Log
import androidx.media3.common.text.Cue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yogiczy.mytv.core.data.media.arib.AribCaptionPacket
import java.io.Closeable
import java.util.concurrent.Executors

/** Timed ID3 is delivered by Media3 at playback time, including after seeks and buffering. */
internal class AribCaptionController(
    private val positionMs: () -> Long,
    private val onTracksChanged: () -> Unit,
    private val onCues: (List<Cue>) -> Unit,
) : Closeable {
    data class Track(val type: Int, val languageId: Int, val language: String) {
        // Native Media3 text indexes are non-negative; these indexes only identify the ID3 bridge.
        val index: Int get() = -((type - 0x80) * 2 + languageId)
        val label: String get() = if (type == 0x81) "ARIB 叠加字幕" else "ARIB 字幕"
    }
    private data class Event(val epoch: Int, val position: Long, val packet: AribCaptionPacket?, val selection: Int?)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dispatcher = Executors.newSingleThreadExecutor { task -> Thread(task, "AribCaption").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val events = Channel<Event>(8)
    private var epoch = 0
    private var desiredIndex: Int? = null
    private var presentation: Job? = null
    private var closed = false
    var tracks: List<Track> = emptyList()
        private set
    val selectedIndex: Int? get() = desiredIndex?.takeIf { id -> tracks.any { it.index == id } }
    private val worker = scope.launch(dispatcher) {
        val decoders = mutableMapOf<Int, AribNativeDecoder>()
        var currentEpoch = -1
        try {
            for (event in events) {
                if (currentEpoch != event.epoch) {
                    decoders.values.forEach(Closeable::close)
                    decoders.clear()
                    currentEpoch = event.epoch
                }
                var frame: AribCaptionFrame? = null
                try {
                    val packet = event.packet
                    if (packet != null) {
                        for (language in 1..2) {
                            val index = -((packet.type - 0x80) * 2 + language)
                            val decoder = decoders.getOrPut(index) { AribNativeDecoder(packet.type == 0x81, language) }
                            val changed = decoder.decode(packet.bytes, event.position)
                            if (changed && event.selection == index) frame = decoder.render(event.position)
                        }
                    } else if (event.selection != null) frame = decoders[event.selection]?.render(event.position)
                } catch (error: UnsatisfiedLinkError) {
                    Log.w("LiuguangArib", "ARIB library unavailable", error)
                } catch (error: IllegalStateException) {
                    Log.w("LiuguangArib", "ARIB decoder unavailable", error)
                }
                val decoded = frame ?: continue
                withContext(Dispatchers.Main.immediate) {
                    if (!closed && event.epoch == epoch && event.selection == selectedIndex) present(decoded)
                }
            }
        } finally {
            decoders.values.forEach(Closeable::close)
            dispatcher.close()
        }
    }

    fun consume(owner: String, bytes: ByteArray) {
        if (closed) return
        val packet = AribCaptionPacket.parse(owner, bytes) ?: return
        val languages = packet.languages ?: packet.statementLanguageId?.let {
            val known = tracks.firstOrNull { track -> track.type == packet.type && track.languageId == it }
            listOf(AribCaptionPacket.Language(it, known?.language ?: "jpn"))
        }.orEmpty()
        val found = languages.map { Track(packet.type, it.id, it.code) }
        val before = selectedIndex
        val remaining = if (packet.languages != null) tracks.filterNot { it.type == packet.type }
            else tracks.filterNot { old -> found.any { it.index == old.index } }
        val next = (remaining + found).sortedBy { -it.index }
        if (next != tracks) {
            tracks = next
            if (before != null && selectedIndex == null) {
                presentation?.cancel()
                onCues(emptyList())
            }
            onTracksChanged()
        }
        if (!events.trySend(Event(epoch, positionMs(), packet, selectedIndex)).isSuccess) {
            // Bound live metadata memory; never block the playback application thread.
            reset(keepSelection = true)
        }
    }

    fun select(index: Int?) {
        if (closed) return
        desiredIndex = index?.takeIf { id -> tracks.any { it.index == id } }
        presentation?.cancel()
        onCues(emptyList())
        onTracksChanged()
        events.trySend(Event(epoch, positionMs(), null, selectedIndex))
    }

    fun reset(keepSelection: Boolean = false) {
        epoch++
        if (!keepSelection) desiredIndex = null
        tracks = emptyList()
        presentation?.cancel()
        onCues(emptyList())
        onTracksChanged()
        events.trySend(Event(epoch, 0, null, null))
    }

    private fun present(frame: AribCaptionFrame) {
        presentation?.cancel()
        presentation = scope.launch {
            // Use the player's media clock, so pause/rebuffer does not expire captions early.
            while (positionMs() < frame.startMs) delay(50)
            val end = if (frame.durationMs < 0) Long.MAX_VALUE else frame.startMs + frame.durationMs
            onCues(if (positionMs() >= end) emptyList() else frame.cues)
            if (end != Long.MAX_VALUE) {
                while (positionMs() < end) delay(50)
                onCues(emptyList())
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        epoch++
        presentation?.cancel()
        events.close()
        worker.cancel()
        scope.cancel()
    }
}

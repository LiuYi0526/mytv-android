package top.yogiczy.mytv.core.data.media.mmt

/** Streaming ARIB TLV/MMTP demuxer. Independent of Android and the playback engine. */
class MmtTlvParser(private val listener: Listener) {
    interface Listener {
        fun onTracks(context: Int, tracks: List<MmtTrack>)
        /** Video uses Annex B; LATM audio is wrapped in LOAS. Time is in microseconds. */
        fun onSample(context: Int, track: MmtTrack, data: ByteArray, timeUs: Long)
        fun onDiscontinuity(context: Int, packetId: Int) {}
    }

    private class MediaState {
        val fragments = MmtFragments(MAX_SAMPLE_BYTES)
        val subtitle = MmtSubtitle()
        var identity: List<Long>? = null
        var mpu = -1L
        var sampleIndex = -1
        var sampleNumber = -1L
        var lastTimeUs = 0L
    }
    private inner class Session(val context: Int) {
        val signaling = MmtSignaling { listener.onTracks(context, it) }
        val media = linkedMapOf<Int, MediaState>()
    }

    private val sessions = linkedMapOf<Int, Session>()
    private val flows = linkedMapOf<List<Int>, Int>()
    private var nextFlow = 0x1000
    private val pending = ByteArray(2 * MAX_TLV_BYTES)
    private var count = 0
    private var originUs: Long? = null

    fun reset() { sessions.clear(); flows.clear(); nextFlow = 0x1000; count = 0; originUs = null }

    fun consume(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        require(offset >= 0 && length >= 0 && offset <= data.size - length)
        var pos = offset
        val end = offset + length
        while (pos < end) {
            val copied = minOf(end - pos, pending.size - count)
            data.copyInto(pending, count, pos, pos + copied)
            pos += copied
            count += copied
            var cursor = 0
            while (cursor <= count - 4) {
                if (!isHeader(pending, cursor)) { cursor++; continue }
                val size = u16(pending, cursor + 2)
                if (size + 4 > count - cursor) break
                try {
                    packet(pending[cursor + 1].toInt() and 255, MmtBytes(pending, cursor + 4, cursor + 4 + size))
                } catch (_: MmtMalformed) {
                    // A damaged packet must not terminate a long-running live stream.
                }
                cursor += size + 4
            }
            pending.copyInto(pending, 0, cursor, count)
            count -= cursor
        }
    }

    private fun session(context: Int): Session? {
        sessions[context]?.let { return it }
        // Bound retained sample buffers across all contexts and tracks.
        if (sessions.size >= 8) return null
        return Session(context).also { sessions[context] = it }
    }

    private fun packet(type: Int, b: MmtBytes) {
        when (type) {
            1 -> ipv4(b)
            2 -> ipv6(b)
            3 -> {
                val context = b.u16() ushr 4
                when (b.u8()) {
                    0x20 -> { // Full compressed IPv4 header (UDP ports included).
                        b.need(20)
                        if (b.data[b.position + 7].toInt() and 255 != 17) return
                        val src = u16(b.data, b.position + 16)
                        val dst = u16(b.data, b.position + 18)
                        b.skip(20)
                        if (src == 123 || dst == 123) return
                    }
                    0x21 -> b.skip(2) // IPv4 identification only
                    0x60 -> {
                        b.need(42)
                        if (b.data[b.position + 4].toInt() and 255 != 17) return
                        val src = u16(b.data, b.position + 38)
                        val dst = u16(b.data, b.position + 40)
                        b.skip(42)
                        if (src == 123 || dst == 123) return
                    }
                    0x61 -> Unit
                    else -> return
                }
                mmtp(context, b)
            }
        }
    }

    private fun ipv4(b: MmtBytes) {
        val start = b.position
        b.need(20)
        val version = b.u8()
        val headerLength = (version and 15) * 4
        b.skip(1)
        val total = b.u16()
        b.skip(2)
        val fragments = b.u16()
        b.skip(1)
        val protocol = b.u8()
        if (version ushr 4 != 4 || headerLength < 20 || total < headerLength || total > b.limit - start ||
            fragments and 0x3fff != 0 || protocol != 17) return
        val key = b.data.copyOfRange(start + 12, start + 20).map { it.toInt() and 255 }
        b.position = start
        val ip = b.part(total)
        ip.skip(headerLength)
        udp(ip, key)
    }

    private fun ipv6(b: MmtBytes) {
        val start = b.position
        b.need(40)
        if (b.u8() ushr 4 != 6) return
        b.skip(3)
        val length = b.u16()
        var protocol = b.u8()
        b.skip(33)
        val ip = b.part(length)
        // Walk bounded IPv6 extension headers; fragmented datagrams are not joined speculatively.
        var headers = 0
        while (protocol in listOf(0, 43, 60, 51)) {
            if (++headers > 8) return
            val previous = protocol
            protocol = ip.u8()
            val units = ip.u8()
            ip.skip(if (previous == 51) (units + 2) * 4 - 2 else (units + 1) * 8 - 2)
        }
        if (protocol != 17) return
        val key = b.data.copyOfRange(start + 8, start + 40).map { it.toInt() and 255 }
        udp(ip, key)
    }

    private fun udp(ip: MmtBytes, address: List<Int>) {
        val src = ip.u16()
        val dst = ip.u16()
        val size = ip.u16()
        ip.skip(2)
        if (src == 123 || dst == 123 || size < 8) return
        val key = address + listOf(src, dst)
        val context = flows[key] ?: run {
            if (flows.size >= 8) return
            nextFlow++.also { flows[key] = it }
        }
        mmtp(context, ip.part(size - 8))
    }

    private fun mmtp(context: Int, bytes: MmtBytes) {
        val flags = bytes.u8()
        if (flags and 0xc0 != 0) return // MMTP version 0
        val payloadType = bytes.u8() and 63
        val packetId = bytes.u16()
        val timestamp = bytes.u32()
        val sequence = bytes.u32()
        if (flags and 0x20 != 0) bytes.skip(4)
        if (flags and 2 != 0) { bytes.skip(2); bytes.skip(bytes.u16()) }
        val scrambling = (flags ushr 3) and 3
        if (scrambling >= 2) return // Scrambled media needs a separate conditional-access implementation.
        val body = if (scrambling == 1) bytes.part(bytes.remaining - 4) else bytes
        val session = session(context) ?: return
        when (payloadType) {
            2 -> session.signaling.consume(packetId, sequence, body)
            0 -> media(session, packetId, sequence, timestamp, body)
        }
    }

    private fun media(s: Session, packetId: Int, sequence: Long, timestamp: Long, bytes: MmtBytes) {
        val track = s.signaling.tracks[packetId] ?: return
        if (!track.isVideo && !track.isAudio && !track.isSubtitle) return
        if (s.media.size >= MAX_TRACKS && packetId !in s.media) return
        val state = s.media.getOrPut(packetId) { MediaState() }
        val previousIdentity = state.identity
        if (!state.fragments.acceptSequence(sequence)) return
        val length = bytes.u16()
        val b = bytes.part(length)
        val flags = b.u8()
        val kind = flags ushr 4
        val timed = flags and 8 != 0
        val fragmentation = (flags ushr 1) and 3
        val aggregate = flags and 1 != 0
        b.skip(1)
        val mpu = b.u32()
        if (kind != 2) return // Only MFUs carry elementary media.
        if (aggregate && fragmentation != 0) { state.fragments.reset(); return }
        fun unit(part: MmtBytes, fragment: Int) {
            val identity = if (timed) {
                val movieFragment = part.u32()
                val sampleNumber = part.u32()
                val offset = part.u32()
                part.skip(2)
                listOf(mpu, movieFragment, sampleNumber, offset)
            } else listOf(mpu, part.u32())
            // Continuations must belong to the same MPU/sample, not merely consecutive packets.
            if (fragment >= 2 && previousIdentity != identity) {
                state.fragments.reset()
                state.subtitle.reset()
                listener.onDiscontinuity(s.context, packetId)
                return
            }
            state.identity = identity
            val data = state.fragments.append(fragment, part.bytes()) ?: return
            if (track.isSubtitle) {
                val xml = state.subtitle.consume(mpu, data) ?: return
                // Text must use the media clock; never let an early subtitle establish A/V's origin.
                val origin = originUs ?: return
                val absolute = track.subtitleReferenceUs ?: compactTimestamp(timestamp, s.signaling.ntpReferenceUs) ?: return
                listener.onSample(s.context, track, MmtSubtitle.prepareTtml(xml,track.subtitleResolution), absolute - origin)
                return
            }
            if (state.mpu != mpu) {
                state.mpu = mpu
                state.sampleIndex = -1
                state.sampleNumber = -1
            }
            val output = if (track.isVideo) annexB(data) ?: return else {
                if (track.audioObjectType == 4) return // ALS decoder is not bundled.
                if (track.audioTransport == 17 || track.audioConfig == null) {
                    if (data.size >= 8192) return
                    byteArrayOf(0x56, (0xe0 or (data.size ushr 8)).toByte(), data.size.toByte()) + data
                } else data
            }
            if (track.isVideo) {
                if (startsPicture(output, track.assetType)) state.sampleIndex++
            } else state.sampleIndex++
            val index = maxOf(0, state.sampleIndex)
            val absolute = track.presentationTime(mpu, index) ?: compactTimestamp(timestamp, s.signaling.ntpReferenceUs)
            val timeUs = if (absolute != null) {
                if (originUs == null) originUs = absolute
                maxOf(0, absolute - originUs!!)
            } else {
                // A source without timestamp descriptors can still advance using the declared frame rate.
                if (track.frameRateNumerator > 0) index * 1_000_000L * track.frameRateDenominator / track.frameRateNumerator
                else return
            }
            state.lastTimeUs = timeUs
            listener.onSample(s.context, track, output, timeUs)
        }
        if (aggregate) while (b.remaining > 0) unit(b.part(b.u16()), 0)
        else unit(b, fragmentation)
    }

    private fun compactTimestamp(timestamp: Long, reference: Long?): Long? {
        reference ?: return null
        val refSeconds = reference / 1_000_000L + 2_208_988_800L
        var seconds = (refSeconds and -65536L) or (timestamp ushr 16)
        if (seconds - refSeconds > 32768) seconds -= 65536
        else if (refSeconds - seconds > 32768) seconds += 65536
        return ntpTimeUs(seconds, (timestamp and 65535) shl 16)
    }

    companion object {
        const val MIME_TYPE = "video/mmt-tlv"
        private const val MAX_TLV_BYTES = 65539
        private const val MAX_SAMPLE_BYTES = 2 * 1024 * 1024
        private const val MAX_TRACKS = 8
        private fun u16(b: ByteArray, p: Int) = ((b[p].toInt() and 255) shl 8) or (b[p + 1].toInt() and 255)
        private fun isHeader(b: ByteArray, p: Int) = b[p] == 0x7f.toByte() &&
            (b[p + 1].toInt() and 255) in listOf(0, 1, 2, 3, 254, 255)

        /** Two complete consecutive TLV headers, plus a plausible first payload, avoid claiming TS/MP4. */
        fun sniff(data: ByteArray, length: Int = data.size): Boolean {
            for (p in 0..length - 4) {
                if (!isHeader(data, p)) continue
                val size = u16(data, p + 2)
                val next = p + size + 4
                if (next > length - 4 || !isHeader(data, next)) continue
                val type = data[p + 1].toInt() and 255
                val valid = when (type) {
                    1 -> size >= 20 && (data[p + 4].toInt() and 0xf0) == 0x40
                    2 -> size >= 40 && (data[p + 4].toInt() and 0xf0) == 0x60
                    3 -> size >= 3 && (data[p + 6].toInt() and 255) in listOf(0x20, 0x21, 0x60, 0x61)
                    255 -> size == 0
                    else -> false
                }
                if (valid) return true
            }
            return false
        }

        /** ISO length-prefixed NAL units to Annex B, preserving every NAL in the MFU. */
        fun annexB(data: ByteArray): ByteArray? {
            val b = MmtBytes(data.copyOf())
            try {
                while (b.remaining > 0) {
                    val start = b.position
                    val length = b.u32()
                    if (length <= 0 || length > b.remaining) return null
                    b.data[start] = 0; b.data[start + 1] = 0; b.data[start + 2] = 0; b.data[start + 3] = 1
                    b.skip(length.toInt())
                }
            } catch (_: MmtMalformed) { return null }
            return b.data
        }

        private fun startsPicture(b: ByteArray, type: String): Boolean {
            // Header NALs retain the timestamp of the following picture; only a first slice advances it.
            for (p in 0 until b.size - 5) {
                if (b[p] != 0.toByte() || b[p + 1] != 0.toByte() || b[p + 2] != 0.toByte() || b[p + 3] != 1.toByte()) continue
                if (type == "hev1" || type == "hvc1") {
                    if (p + 6 < b.size && ((b[p + 4].toInt() ushr 1) and 63) <= 31 && b[p + 6].toInt() and 128 != 0) return true
                } else {
                    val nal = b[p + 4].toInt() and 31
                    // first_mb_in_slice == 0 is the one-bit Exp-Golomb code '1'.
                    if (nal in 1..5 && b[p + 5].toInt() and 128 != 0) return true
                }
            }
            return false
        }
    }
}

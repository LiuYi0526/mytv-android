package top.yogiczy.mytv.core.data.media.mmt

/** Track configuration and timestamps carried by the MMT Package Table (MPT). */
class MmtTrack internal constructor(val id: Int, val assetType: String) {
    var language: String? = null
        internal set
    var audioConfig: ByteArray? = null
        internal set
    var audioObjectType = -1
        internal set
    var audioTransport = -1
        internal set
    var timeScale = 90_000L
        internal set
    internal var frameRateNumerator = 0
    internal var frameRateDenominator = 1
    var subtitleSupported = false
        internal set
    var subtitleSuperimpose = false
        internal set
    var subtitleResolution = 0
        internal set
    internal var subtitleReferenceUs: Long? = null
    internal val timestamps = linkedMapOf<Long, Long>()
    internal val durations = linkedMapOf<Long, Timing>()
    internal data class Timing(val decodeOffset: Int, val composition: IntArray, val duration: IntArray)
    internal fun merge(next: MmtTrack) {
        language = next.language ?: language
        audioConfig = next.audioConfig ?: audioConfig
        if (next.audioObjectType >= 0) audioObjectType = next.audioObjectType
        if (next.audioTransport >= 0) audioTransport = next.audioTransport
        timeScale = next.timeScale
        frameRateNumerator = next.frameRateNumerator
        frameRateDenominator = next.frameRateDenominator
        subtitleSupported = next.subtitleSupported
        subtitleSuperimpose = next.subtitleSuperimpose
        subtitleResolution = next.subtitleResolution
        subtitleReferenceUs = next.subtitleReferenceUs
        next.timestamps.forEach { (k, v) -> timestamps.putBounded(k, v, 32) }
        next.durations.forEach { (k, v) -> durations.putBounded(k, v, 32) }
    }
    internal fun presentationTime(mpu: Long, index: Int): Long? {
        val base = timestamps[mpu] ?: return null
        val timing = durations[mpu] ?: return base
        if (index !in timing.composition.indices) return null
        var ticks = -timing.decodeOffset.toLong()
        for (i in 0 until index) ticks += timing.duration[i]
        return base + (ticks + timing.composition[index]) * 1_000_000L / timeScale
    }
    val isVideo get() = assetType in listOf("hev1", "hvc1", "avc1", "avc3")
    val isAudio get() = assetType == "mp4a"
    val isSubtitle get() = assetType == "stpp" && subtitleSupported
}

internal class MmtSignaling(private val onTracks: (List<MmtTrack>) -> Unit) {
    val tracks = linkedMapOf<Int, MmtTrack>()
    var ntpReferenceUs: Long? = null
        private set
    private val fragments = linkedMapOf<Int, MmtFragments>()

    fun reset() { tracks.clear(); fragments.clear(); ntpReferenceUs = null }

    fun consume(packetId: Int, sequence: Long, bytes: MmtBytes) {
        val chain = fragments.getOrPut(packetId) {
            if (fragments.size >= 32) fragments.remove(fragments.keys.first())
            MmtFragments(512 * 1024)
        }
        if (!chain.acceptSequence(sequence)) return
        val flags = bytes.u8()
        bytes.skip(1)
        val fragmentation = flags ushr 6
        if (flags and 1 != 0) {
            if (fragmentation != 0) { chain.reset(); return }
            while (bytes.remaining > 0) {
                val length = if (flags and 2 != 0) bytes.u32().toInt() else bytes.u16()
                message(bytes.part(length))
            }
        } else {
            chain.append(fragmentation, bytes.bytes())?.let { message(MmtBytes(it)) }
        }
    }

    private fun message(bytes: MmtBytes) {
        val id = bytes.u16()
        bytes.skip(1) // version
        when (id) {
            0 -> {
                val content = bytes.part(bytes.u32().toInt())
                val tables = content.u8()
                val sizes = IntArray(tables) { content.skip(2); content.u16() + 4 }
                for (size in sizes) table(content.part(size))
                while (content.remaining >= 4) {
                    val start = content.position
                    content.skip(2)
                    val size = content.u16()
                    content.position = start
                    table(content.part(size + 4))
                }
            }
            0x8000 -> table(bytes.part(bytes.u16()))
        }
    }

    private fun table(bytes: MmtBytes) {
        if (bytes.u8() != 0x20) return
        bytes.skip(1)
        val body = bytes.part(bytes.u16())
        body.skip(1)
        body.skip(body.u8()) // package_id
        body.skip(body.u16()) // package descriptors
        val count = body.u8()
        val parsed = ArrayList<MmtTrack>()
        repeat(count) {
            body.skip(5) // identifier type, identifier scheme
            body.skip(body.u8())
            val asset = String(body.bytes(4), Charsets.US_ASCII)
            body.skip(1)
            var packetId = -1
            repeat(body.u8()) {
                when (body.u8()) {
                    0 -> { val id = body.u16(); if (packetId == -1) packetId = id }
                    1 -> body.skip(12)
                    2 -> body.skip(36)
                    3 -> body.skip(6)
                    4 -> body.skip(38)
                    5 -> body.skip(body.u8())
                    else -> throw MmtMalformed()
                }
            }
            val track = MmtTrack(packetId, asset)
            val descriptors = body.part(body.u16())
            while (descriptors.remaining > 0) descriptor(track, descriptors)
            if (packetId >= 0) parsed += track
        }
        // A malformed/truncated table never replaces an otherwise usable track list.
        for (next in parsed) {
            val old = tracks[next.id]
            if (old != null && old.assetType == next.assetType) old.merge(next)
            else tracks[next.id] = next
        }
        tracks.keys.retainAll(parsed.map { it.id }.toSet())
        if (tracks.isNotEmpty()) onTracks(tracks.values.toList())
    }

    private fun descriptor(track: MmtTrack, bytes: MmtBytes) {
        val tag = bytes.u16()
        val length = when (tag) {
            in 0..0x3fff, in 0x8000..0xefff -> bytes.u8()
            in 0x4000..0x6fff, in 0xf000..0xffff -> bytes.u16()
            else -> bytes.u32().toInt()
        }
        val d = bytes.part(length)
        when (tag) {
            0x8020 -> {
                if (d.u16() != 0x20) return
                d.skip(1)
                val flags = d.u8()
                track.language = String(d.bytes(3), Charsets.US_ASCII)
                val format = d.u8()
                val timeMode = d.u8() ushr 4
                val display = d.u8()
                track.subtitleSuperimpose = format ushr 6 == 1
                track.subtitleResolution = display ushr 4
                track.subtitleSupported = ((format ushr 2) and 15) == 0 &&
                    (display and 15) == 0 && track.subtitleResolution <= 2
                if (flags and 8 != 0) d.skip(4)
                if (timeMode == 2) {
                    track.subtitleReferenceUs = ntpTimeUs(d.u32(), d.u32())
                    d.skip(1)
                }
            }
            1 -> while (d.remaining >= 12) {
                val mpu = d.u32()
                val time = ntpTimeUs(d.u32(), d.u32())
                track.timestamps.putBounded(mpu, time, 32)
                ntpReferenceUs = time
            }
            0x8026 -> {
                val flags = d.u8()
                val mode = (flags ushr 1) and 3
                if (flags and 1 != 0) {
                    val scale = d.u32()
                    if (scale == 0L || scale > Int.MAX_VALUE) throw MmtMalformed()
                    track.timeScale = scale
                }
                if (mode == 3) return
                val defaultDuration = if (mode == 1) d.u16() else 0
                while (d.remaining > 0) {
                    val mpu = d.u32()
                    d.skip(1)
                    val decodeOffset = d.u16()
                    val samples = d.u8()
                    val composition = IntArray(samples)
                    val duration = IntArray(samples)
                    repeat(samples) {
                        composition[it] = d.u16()
                        duration[it] = when (mode) {
                            2 -> d.u16()
                            1 -> defaultDuration
                            else -> if (track.frameRateNumerator > 0)
                                (track.timeScale * track.frameRateDenominator / track.frameRateNumerator).toInt() else 0
                        }
                    }
                    track.durations.putBounded(mpu, MmtTrack.Timing(decodeOffset, composition, duration), 32)
                }
            }
            0x8009 -> {
                val flags = d.u8()
                d.skip(flags and 15)
                if (flags and 128 != 0) track.audioConfig = d.bytes(d.u8())
            }
            0x8014 -> {
                val streamType = d.u8() and 15
                d.skip(3)
                val transport = d.u8()
                d.skip(1)
                val flags = d.u8()
                track.language = String(d.bytes(3), Charsets.US_ASCII)
                if (streamType in 3..4 && transport in listOf(17, 28)) {
                    track.audioObjectType = streamType
                    track.audioTransport = transport
                }
                if (flags and 128 != 0) d.skip(3)
            }
            0x8010 -> {
                d.skip(1)
                val rate = d.u8() and 31
                val rates = mapOf(1 to (15 to 1), 2 to (24000 to 1001), 3 to (24 to 1),
                    4 to (25 to 1), 5 to (30000 to 1001), 6 to (30 to 1), 7 to (50 to 1),
                    8 to (60000 to 1001), 9 to (60 to 1), 10 to (100 to 1), 11 to (120000 to 1001), 12 to (120 to 1))
                rates[rate]?.let { track.frameRateNumerator = it.first; track.frameRateDenominator = it.second }
                d.skip(3)
                track.language = String(d.bytes(3), Charsets.US_ASCII)
            }
        }
    }
}

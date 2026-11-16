package top.yogiczy.mytv.core.data.entities.epg

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.epg.EpgList.Companion.displayGuide
import top.yogiczy.mytv.core.data.entities.epg.EpgList.Companion.match
import top.yogiczy.mytv.core.data.entities.epg.EpgList.Companion.recentProgramme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class EpgDisplayScheduleTest {
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private var originalZone: TimeZone? = null
    @Before fun useLocalZone() { originalZone = TimeZone.getDefault(); TimeZone.setDefault(zone) }
    @After fun restoreZone() { originalZone?.let(TimeZone::setDefault) }
    private fun at(text: String) = SimpleDateFormat("yyyyMMddHHmm", Locale.ROOT).apply { timeZone = zone }.parse(text)!!.time
    private val first = EpgProgramme(at("202611112100"), at("202611112130"), "第一档")
    private val second = EpgProgramme(at("202611112135"), at("202611112200"), "第二档")

    @Test fun insertsOnlyTheFiveMinuteGapWithoutChangingRealProgrammeTimes() {
        val result = EpgDisplaySchedule.fillGaps(EpgProgrammeList(listOf(first, second)), zone)
        assertEquals(listOf(first, EpgProgramme(first.endAt, second.startAt, "无信息"), second), result.value)
        assertSame(first, result.first())
        assertSame(second, result.last())
    }

    @Test fun exactBoundariesBelongToTheFollowingInterval() {
        val result = EpgDisplaySchedule.fillGaps(EpgProgrammeList(listOf(first, second)), zone)
        assertEquals(first, EpgDisplaySchedule.recent(result, first.endAt - 1).now)
        assertEquals("无信息", EpgDisplaySchedule.recent(result, first.endAt).now?.title)
        assertEquals(second, EpgDisplaySchedule.recent(result, first.endAt).next)
        assertEquals(second, EpgDisplaySchedule.recent(result, second.startAt).now)
    }

    @Test fun replayNextIncludesTheGapRatherThanSkippingToTheNextRealProgramme() {
        val channel = Channel(name = "测试")
        val guide = EpgList(listOf(Epg(listOf(channel.name), programmeList = EpgProgrammeList(listOf(first, second)))))
        val gap = guide.recentProgramme(channel, first)!!.next!!
        assertEquals(EpgProgramme(first.endAt, second.startAt, "无信息"), gap)
        assertEquals(second, guide.recentProgramme(channel, gap)?.next)
    }

    @Test fun overlapsAndInvalidIntervalsDoNotCreateFalseGaps() {
        val longer = first.copy(endAt = at("202611112145"))
        val overlap = first.copy(startAt = at("202611112115"), endAt = at("202611112120"))
        val next = second.copy(startAt = longer.endAt)
        val result = EpgDisplaySchedule.fillGaps(EpgProgrammeList(listOf(next, overlap, longer,
            EpgProgramme(10, 10, "invalid"))), zone)
        assertEquals(listOf(longer, overlap, next), result.value)
    }

    @Test fun crossMidnightGapIsVisibleInBothDates() {
        val before = first.copy(endAt = at("202611112350"))
        val after = second.copy(startAt = at("202611120010"), endAt = at("202611120100"))
        val result = EpgDisplaySchedule.fillGaps(EpgProgrammeList(listOf(before, after)), zone)
        assertEquals(listOf(before,
            EpgProgramme(before.endAt, at("202611120000"), "无信息"),
            EpgProgramme(at("202611120000"), after.startAt, "无信息"), after), result.value)
    }

    @Test fun fillingGapsIsIdempotentAndDoesNotChangeCachedRawGuide() {
        val raw = Epg(listOf("测试"), programmeList = EpgProgrammeList(listOf(first, second)))
        val before = Json.encodeToString(raw)
        val guide = EpgList(listOf(raw))
        val filled = guide.displayGuide(Channel(name = "测试"), first.startAt)
        assertEquals(listOf(first, EpgProgramme(first.endAt, second.startAt, "无信息"), second),
            filled.programmeList.filter { it.startAt >= first.startAt && it.endAt <= second.endAt })
        assertSame(filled, guide.displayGuide(Channel(name = "测试"), first.startAt + 60_000))
        assertSame(filled.programmeList, EpgDisplaySchedule.fillGaps(filled.programmeList))
        assertEquals(before, Json.encodeToString(raw))
        assertSame(raw, guide.match(Channel(name = "测试")))
    }

    @Test fun missingGuideHasWholeHourEntriesForYesterdayTodayAndTomorrow() {
        val schedule = EpgDisplaySchedule.hourly(at("202611112132"), zone)
        assertEquals(72, schedule.size)
        assertEquals(at("202611100000"), schedule.first().startAt)
        assertEquals(at("202611130000"), schedule.last().endAt)
        assertTrue(schedule.all { it.title == "无信息" && it.endAt - it.startAt == 3_600_000L })
        assertTrue(schedule.zipWithNext().all { (left, right) -> left.endAt == right.startAt })
        assertEquals(at("202611112100"), EpgDisplaySchedule.recent(schedule, at("202611112132")).now!!.startAt)
    }

    @Test fun hourlySchedulesAreSharedAndRollForwardAtMidnight() {
        val first = EpgDisplaySchedule.hourly(at("202611112100"), zone)
        assertSame(first, EpgDisplaySchedule.hourly(at("202611112359"), zone))
        val next = EpgDisplaySchedule.hourly(at("202611120000"), zone)
        assertEquals(at("202611110000"), next.first().startAt)
        assertNotSame(first, next)
    }

    @Test fun emptyChannelsGetDisplayEntriesWithoutChangingRealMatching() {
        val channel = Channel(name = "测试", epgId = "id")
        val actual = Epg(listOf(channel.name), logo = "logo", channelId = "id")
        val list = EpgList(listOf(actual))
        val displayed = list.displayGuide(channel)
        assertEquals("logo", displayed.logo)
        assertEquals("id", displayed.channelId)
        assertTrue(displayed.programmeList.isNotEmpty())
        assertSame(actual, list.match(channel))
        assertSame(displayed.programmeList, EpgList().displayGuide(Channel(name = "其他")).programmeList)
    }

    @Test fun daylightSavingDoesNotIntroduceHolesOrOverlappingHours() {
        val dstZone = TimeZone.getTimeZone("America/New_York")
        val time = Calendar.getInstance(dstZone).apply { clear(); set(2026, Calendar.MARCH, 8, 12, 0) }.timeInMillis
        val schedule = EpgDisplaySchedule.hourly(time, dstZone)
        assertEquals(71, schedule.size)
        assertTrue(schedule.all { it.endAt - it.startAt == 3_600_000L })
        assertTrue(schedule.zipWithNext().all { (left, right) -> left.endAt == right.startAt })
    }

    @Test fun expiredBeijingGuideKeepsHistoryAndShowsHourlyUnknownAfterMidnight() {
        val channel = Channel(name = "北京卫视")
        val last = EpgProgramme(at("202611162324"), at("202611170000") - 1_000, "走过万水千山")
        val raw = Epg(listOf(channel.name), programmeList = EpgProgrammeList(listOf(last)))
        val list = EpgList(listOf(raw))
        val display = list.displayGuide(channel, at("202611170145"))
        assertSame(last, display.programmeList.first { it.title == last.title })
        val recent = EpgDisplaySchedule.recent(display.programmeList, at("202611170145"))
        assertEquals(EpgProgramme(at("202611170100"), at("202611170200"), "无信息"), recent.now)
        assertEquals(EpgProgramme(at("202611170200"), at("202611170300"), "无信息"), recent.next)
        val lastSecond = EpgDisplaySchedule.recent(display.programmeList, last.endAt).now!!
        assertEquals(last.endAt, lastSecond.startAt)
        assertEquals(at("202611170000"), lastSecond.endAt)
        assertEquals("无信息", lastSecond.title)
        assertEquals(listOf(last), raw.programmeList.value)
        assertSame(raw, list.match(channel))
    }

    @Test fun missingTimeBeforeFirstBroadcastIsFilledWithoutOverlappingRealProgramme() {
        val list = EpgList(listOf(Epg(listOf("测试"), programmeList = EpgProgrammeList(listOf(first)))))
        val display = list.displayGuide(Channel(name = "测试"), at("202611110900"))
        val before = EpgDisplaySchedule.recent(display.programmeList, at("202611110932")).now!!
        assertEquals(EpgProgramme(at("202611110900"), at("202611111000"), "无信息"), before)
        assertSame(first, EpgDisplaySchedule.recent(display.programmeList, first.startAt).now)
        assertTrue(display.programmeList.zipWithNext().all { (a, b) -> a.endAt == b.startAt })
    }

    @Test fun edgeHoursAreClippedAndExtensionIsIdempotent() {
        val real = EpgProgramme(at("202611112115"), at("202611112145"), "真实节目")
        val hours = EpgDisplaySchedule.hourly(first.startAt, zone)
        val display = EpgDisplaySchedule.extendEdges(EpgProgrammeList(listOf(real)), hours)
        assertEquals(EpgProgramme(at("202611112100"), real.startAt, "无信息"),
            EpgDisplaySchedule.recent(display, at("202611112110")).now)
        assertEquals(EpgProgramme(real.endAt, at("202611112200"), "无信息"),
            EpgDisplaySchedule.recent(display, real.endAt).now)
        assertSame(real, EpgDisplaySchedule.recent(display, real.startAt).now)
        assertSame(display, EpgDisplaySchedule.extendEdges(display, hours))
    }

    @Test fun veryOldGuideAddsOnlyTheRollingWindowAndRollsForwardWithoutSerializingViews() {
        val channel = Channel(name = "测试")
        val raw = Epg(listOf(channel.name), programmeList = EpgProgrammeList(listOf(first)))
        val json = Json.encodeToString(raw)
        val list = EpgList(listOf(raw))
        val oldDisplay = list.displayGuide(channel, at("202612112359"))
        val newDisplay = list.displayGuide(channel, at("202612120000"))
        assertNotSame(oldDisplay, newDisplay)
        assertEquals(73, oldDisplay.programmeList.size)
        assertEquals(73, newDisplay.programmeList.size)
        assertEquals(at("202612140000"), newDisplay.programmeList.last().endAt)
        assertEquals("无信息", EpgDisplaySchedule.recent(newDisplay.programmeList, at("202612120000")).now!!.title)
        assertSame(first, newDisplay.programmeList.first())
        assertEquals(json, Json.encodeToString(raw))
    }

    @Test fun guidesCoveringTheWindowAreNotCopiedAndLongOverlapsDoNotProduceFalseTail() {
        val hours = EpgDisplaySchedule.hourly(first.startAt, zone)
        val long = first.copy(startAt = hours.first().startAt, endAt = hours.last().endAt + 3_600_000)
        val nested = first.copy(startAt = long.startAt + 60_000, endAt = long.startAt + 120_000)
        val raw = EpgProgrammeList(listOf(long, nested))
        assertSame(raw, EpgDisplaySchedule.extendEdges(raw, hours))
    }
}

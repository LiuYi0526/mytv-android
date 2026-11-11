package top.yogiczy.mytv.core.data.entities.epg

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
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
        val filled = guide.displayGuide(Channel(name = "测试"))
        assertEquals(3, filled.programmeList.size)
        assertSame(filled, guide.displayGuide(Channel(name = "测试")))
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
}

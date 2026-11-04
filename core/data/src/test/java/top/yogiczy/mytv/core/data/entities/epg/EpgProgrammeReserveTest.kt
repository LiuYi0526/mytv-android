package top.yogiczy.mytv.core.data.entities.epg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.channel.Channel

class EpgProgrammeReserveTest {
    private val channel = Channel(name = "Now新聞台")
    private val airing = EpgProgramme(1_000L, 1_801_000L, "Now深宵新聞")
    private val reserve = EpgProgrammeReserve(channel.name, airing.title, airing.startAt, airing.endAt)

    @Test fun exactAiringMatchesBothDisplayAndReservationToggle() {
        assertTrue(reserve.test(airing))
        assertTrue(reserve.test(channel, airing))
    }

    @Test fun sameTitleInAnotherTimeSlotIsNotReserved() {
        val later = airing.copy(startAt = airing.endAt, endAt = airing.endAt + 1_800_000L)
        assertFalse(reserve.test(later))
        assertFalse(reserve.test(channel, later))
    }

    @Test fun sameTitleAtSameClockTimeOnAnotherDayIsNotReserved() {
        val tomorrow = airing.copy(startAt = airing.startAt + 86_400_000L, endAt = airing.endAt + 86_400_000L)
        assertFalse(reserve.test(tomorrow))
        assertFalse(reserve.test(channel, tomorrow))
    }

    @Test fun anotherChannelDoesNotShareTheReservation() {
        assertFalse(reserve.test(channel.copy(name = "Another channel"), airing))
    }

    @Test fun changedTitleOrEndTimeDoesNotMatchTheStoredAiring() {
        assertFalse(reserve.test(airing.copy(title = "Another programme")))
        assertFalse(reserve.test(airing.copy(endAt = airing.endAt + 60_000L)))
    }

    @Test fun filteringReservationsToCurrentChannelKeepsIdenticalAiringsIndependent() {
        val anotherChannel = "Another channel"
        val reservations = EpgProgrammeReserveList(listOf(reserve))
        assertTrue(reservations.filter { it.channel == channel.name }.any { it.test(airing) })
        assertFalse(reservations.filter { it.channel == anotherChannel }.any { it.test(airing) })
    }
}

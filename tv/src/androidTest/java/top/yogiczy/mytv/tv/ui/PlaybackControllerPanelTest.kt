package top.yogiczy.mytv.tv.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme
import top.yogiczy.mytv.core.data.entities.epg.EpgProgrammeRecent
import top.yogiczy.mytv.core.data.media.PlaybackProgrammeNavigation
import top.yogiczy.mytv.core.data.media.PlaybackControllerTimeline
import top.yogiczy.mytv.core.data.media.PlaybackSeekSteps
import top.yogiczy.mytv.tv.ui.screensold.videoplayercontroller.VideoPlayerControllerScreen
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalTestApi::class)
class PlaybackControllerPanelTest {
    @get:Rule val compose = createComposeRule()
    private val start = 1_793_271_600_000L
    private val programme = EpgProgramme(title = "测试节目", description = "当前节目的简介", startAt = start, endAt = start + 600_000)
    private val seek = AtomicLong(Long.MIN_VALUE)
    private val pause = AtomicInteger()
    private val live = AtomicInteger()
    private val selectedProgramme = AtomicReference<EpgProgramme?>()
    private val previous = programme.copy(startAt = start - 600_000, endAt = start)
    private val next = programme.copy(startAt = programme.endAt, endAt = programme.endAt + 600_000)

    private fun show(replay: Boolean = false, absolute: Boolean = true, supported: Boolean = true,
        futureNext: Boolean = false, playing: Boolean = true, positionMs: Long = 120_000,
        replayProgramme: () -> EpgProgramme = { programme },
        seekSteps: PlaybackSeekSteps = PlaybackSeekSteps.DEFAULT,
        positionProvider: () -> Long = { if (absolute) start + positionMs else positionMs },
    ) {
        val visible = mutableStateOf(true)
        compose.setContent {
            MyTvTheme {
                if (visible.value) VideoPlayerControllerScreen(
                    seekSteps = seekSteps,
                    channelProvider = { Channel(name = "测试频道") },
                    recentEpgProgrammeProvider = { EpgProgrammeRecent(programme, programme.copy(title = "下一档", startAt = programme.endAt)) },
                    currentPlaybackEpgProgrammeProvider = { if (replay) replayProgramme() else null },
                    previousProgrammeProvider = { previous },
                    nextProgrammeProvider = { if (futureNext) next.copy(startAt = System.currentTimeMillis() + 60_000) else next },
                    canStartProgramme = { supported && it.startAt < System.currentTimeMillis() },
                    onProgrammePlayback = { selectedProgramme.set(it) },
                    videoPlayerCurrentPositionProvider = positionProvider,
                    videoPlayerDurationProvider = {
                        if (absolute) replayProgramme().let { it.startAt to it.endAt } else 0L to 600_000L
                    },
                    videoPlayerPositionIsAbsoluteProvider = { absolute },
                    videoPlayerProgrammeRangeAt = { position ->
                        listOf(previous, programme, next).firstOrNull { position in it.startAt until it.endAt }
                            ?.let { it.startAt to it.endAt }
                    },
                    isVideoPlayerPlayingProvider = { playing },
                    onVideoPlayerSeekTo = { seek.set(it) },
                    onVideoPlayerPause = { pause.incrementAndGet() },
                    onReturnToLive = { live.incrementAndGet(); visible.value = false },
                    showChannelLogo = false,
                )
            }
        }
    }

    private fun clock(time: Long) = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(time)

    private fun focusProgress() = compose.onNodeWithContentDescription("播放进度").apply {
        performSemanticsAction(SemanticsActions.RequestFocus) { it() }
    }

    @Test fun controllerInitiallyFocusesPauseWithAValidProgressBar() {
        show()
        compose.onNodeWithContentDescription("暂停播放").assertIsFocused()
        compose.onNodeWithContentDescription("播放进度").assertIsNotFocused()
    }

    @Test fun pausedControllerInitiallyFocusesPlay() {
        show(playing = false)
        compose.onNodeWithContentDescription("继续播放").assertIsFocused()
    }

    @Test fun playPauseUsesTheSameSizeAsOtherTransportButtons() {
        show()
        val pauseSize = compose.onNodeWithContentDescription("暂停播放").fetchSemanticsNode().size
        val backSize = compose.onNodeWithContentDescription("后退10秒").fetchSemanticsNode().size
        assertEquals(backSize, pauseSize)
    }

    @Test fun remoteDoesNotFocusUnavailableLiveButton() {
        show()
        compose.onNodeWithContentDescription("回到直播").assertIsNotEnabled()
        val restart = compose.onNodeWithContentDescription("从头观看")
        restart.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        restart.performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithContentDescription("回到直播").assertIsNotFocused()
    }

    @Test fun absoluteTimeShowsRealClockAndRemotePreview() {
        show()
        compose.onNodeWithText("测试节目").assertExists()
        compose.onNodeWithText("当前节目的简介").assertExists()
        compose.onNodeWithText("${clock(start + 120_000)} / ${clock(start + 600_000)}").assertExists()
        val bar = focusProgress()
        bar.assertIsFocused()
        bar.performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("${clock(start + 180_000)} / ${clock(start + 600_000)}").assertExists()
        compose.waitUntil(5_000) { seek.get() != Long.MIN_VALUE }
        assertEquals(start + 180_000, seek.get())
    }

    @Test fun relativeMediaStillUsesElapsedTime() {
        show(absolute = false)
        compose.onNodeWithText("02:00 / 10:00").assertExists()
    }

    private fun showGuideFallback(supported: Boolean) {
        val timeline = PlaybackControllerTimeline.resolve(12_000, 0, false, null, programme, supported, start + 120_000)
        compose.setContent {
            MyTvTheme {
                VideoPlayerControllerScreen(
                    videoPlayerCurrentPositionProvider = { timeline.position },
                    videoPlayerDurationProvider = { timeline.range },
                    videoPlayerPositionIsAbsoluteProvider = { timeline.absolute },
                    videoPlayerSeekEnabledProvider = { timeline.seekEnabled },
                    onVideoPlayerSeekTo = { seek.set(it) },
                    showChannelLogo = false,
                )
            }
        }
    }

    @Test fun guideFallbackShowsProgressAndSeeksInWallClockTime() {
        showGuideFallback(true)
        compose.onNodeWithText("${clock(start + 120_000)} / ${clock(programme.endAt)}").assertExists()
        focusProgress().assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionLeft); pressKey(Key.Enter) }
        assertEquals(start + 60_000, seek.get())
    }

    @Test fun guideWithoutReplayStillShowsProgressButDisablesSeeking() {
        showGuideFallback(false)
        compose.onNodeWithText("${clock(start + 120_000)} / ${clock(programme.endAt)}").assertExists()
        listOf("后退10秒", "前进10秒").forEach {
            compose.onNodeWithContentDescription(it).assertIsNotEnabled().performTouchInput { click() }
        }
        compose.onNodeWithContentDescription("暂停播放").assertDoesNotExist()
        compose.onNodeWithContentDescription("继续播放").assertIsFocused()
        assertEquals(Long.MIN_VALUE, seek.get())
    }

    @Test fun smallTransportButtonsKeepTenSecondStepsWithoutMoreMenu() {
        show()
        compose.onNodeWithContentDescription("更多定位").assertDoesNotExist()
        compose.onNodeWithContentDescription("前进10秒").performTouchInput { click() }
        compose.waitUntil(5_000) { seek.get() != Long.MIN_VALUE }
        assertEquals(start + 130_000, seek.get())
    }

    @Test fun buttonsShowAndApplyTheirIndependentConfiguredSteps() {
        show(replay = true, seekSteps = PlaybackSeekSteps(buttonRewindSeconds = 15, buttonForwardSeconds = 30))
        compose.onNodeWithContentDescription("后退15秒").performTouchInput { click() }
        compose.waitUntil(5_000) { seek.get() != Long.MIN_VALUE }
        assertEquals(start + 105_000, seek.get())

        seek.set(Long.MIN_VALUE)
        compose.onNodeWithContentDescription("前进30秒").performTouchInput { click() }
        compose.waitUntil(5_000) { seek.get() != Long.MIN_VALUE }
        assertEquals(start + 150_000, seek.get())
    }

    @Test fun rewindAtProgrammeStartSeeksTenSecondsIntoPreviousProgramme() {
        show(replay = true, positionMs = 0)
        compose.onNodeWithContentDescription("后退10秒").performTouchInput { click() }
        compose.waitUntil(5_000) { seek.get() != Long.MIN_VALUE }
        assertEquals(start - 10_000, seek.get())
    }

    @Test fun forwardCarriesRemainingSecondsIntoNextProgramme() {
        show(replay = true, positionMs = 595_000)
        compose.onNodeWithContentDescription("前进10秒").performTouchInput { click() }
        compose.waitUntil(5_000) { seek.get() != Long.MIN_VALUE }
        assertEquals(programme.endAt + 5_000, seek.get())
    }

    @Test fun rewindPreviewsPreviousProgrammeBeforeSeekAndKeepsItUntilPlaybackArrives() {
        val current = mutableStateOf(programme)
        val playhead = mutableLongStateOf(start + 1_000)
        show(replay = true, replayProgramme = { current.value }, positionProvider = { playhead.longValue })
        val target = start - 9_000
        compose.onNodeWithContentDescription("后退10秒").performTouchInput { click() }
        assertProgrammeProgress(target, previous)
        assertEquals(Long.MIN_VALUE, seek.get())

        compose.waitUntil(5_000) { seek.get() == target }
        assertProgrammeProgress(target, previous)
        compose.runOnIdle {
            current.value = previous
            playhead.longValue = target + 1_000
        }
        assertProgrammeProgress(target + 1_000, previous)
    }

    @Test fun progressKeyPreviewsNextProgrammeBeforeConfirmingSeek() {
        show(replay = true, positionMs = 595_000)
        focusProgress().performKeyInput { pressKey(Key.DirectionRight) }
        val target = next.startAt + 55_000
        assertProgrammeProgress(target, next)
        assertEquals(Long.MIN_VALUE, seek.get())
        compose.onNodeWithContentDescription("播放进度").performKeyInput { pressKey(Key.Enter) }
        assertEquals(target, seek.get())
    }

    private fun assertProgrammeProgress(position: Long, programme: EpgProgramme) {
        compose.onNodeWithText("${clock(position)} / ${clock(programme.endAt)}").assertExists()
        val fraction = ((position - programme.startAt).toDouble() / (programme.endAt - programme.startAt)).toFloat()
        compose.onNodeWithContentDescription("播放进度").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(fraction, 0f..1f)),
        )
    }

    @Test fun forwardStillCommitsWhenPlaybackNaturallyCrossesTheBoundaryDuringPreview() {
        val current = mutableStateOf(programme)
        show(replay = true, positionMs = 599_750, replayProgramme = { current.value })
        compose.onNodeWithContentDescription("前进10秒").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(Long.MIN_VALUE, seek.get())
            current.value = next
        }
        compose.waitUntil(5_000) { seek.get() != Long.MIN_VALUE }
        assertEquals(programme.endAt + 9_750, seek.get())
    }

    @Test fun relativePlaybackStillClampsTenSecondButtonToMediaEnd() {
        show(absolute = false, positionMs = 595_000)
        compose.onNodeWithContentDescription("前进10秒").performTouchInput { click() }
        compose.waitUntil(5_000) { seek.get() != Long.MIN_VALUE }
        assertEquals(600_000L, seek.get())
    }

    @Test fun pauseTouchOnlyFiresOnce() {
        show()
        compose.onNodeWithContentDescription("暂停播放").performTouchInput { click() }
        assertEquals(1, pause.get())
        compose.onNodeWithContentDescription("回到直播").assertIsNotEnabled()
    }

    @Test fun returnToLiveDisposesPendingReplaySeek() {
        show(replay = true)
        focusProgress().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithContentDescription("回到直播").performTouchInput { click() }
        Thread.sleep(1_200)
        assertEquals(1, live.get())
        assertEquals(Long.MIN_VALUE, seek.get())
    }

    @Test fun programmeButtonsSelectExactBroadcastRatherThanMinuteOffsets() {
        show(replay = true)
        compose.onNodeWithContentDescription("上一节目").performTouchInput { click() }
        assertEquals(previous, selectedProgramme.get())
        compose.onNodeWithContentDescription("下一节目").performTouchInput { click() }
        assertEquals(next, selectedProgramme.get())
        assertEquals(Long.MIN_VALUE, seek.get())
    }

    @Test fun restartCancelsPendingPreviewAndKeepsFocusOnProgress() {
        show(replay = true)
        focusProgress().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithContentDescription("从头观看").performTouchInput { click() }
        compose.onNodeWithContentDescription("播放进度").assertIsFocused()
        Thread.sleep(1_200)
        assertEquals(programme, selectedProgramme.get())
        assertEquals(Long.MIN_VALUE, seek.get())
    }

    @Test fun unsupportedReplayButtonsRemainDisabledForTouch() {
        show(supported = false)
        listOf("上一节目", "下一节目", "从头观看").forEach { label ->
            compose.onNodeWithContentDescription(label).assertIsNotEnabled().performTouchInput { click() }
        }
        assertEquals(null, selectedProgramme.get())
    }

    @Test fun futureProgrammeCannotBeSelected() {
        show(replay = true, futureNext = true)
        compose.onNodeWithContentDescription("下一节目").assertIsNotEnabled().performTouchInput { click() }
        assertEquals(null, selectedProgramme.get())
    }

    private fun showChangingProgrammes(startLive: Boolean = false): Pair<List<EpgProgramme>, MutableList<Long>> {
        val midnight = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).parse("20261111000000")!!.time
        val guide = (0..3).map { index ->
            EpgProgramme(midnight + (index - 3) * 3_600_000L, midnight + (index - 2) * 3_600_000L, "同名节目")
        }
        val liveProgramme = guide.last()
        val livePosition = liveProgramme.startAt + 1_000
        val schedule = if (startLive) guide + EpgProgramme(
            liveProgramme.endAt, liveProgramme.endAt + 3_600_000L, "未播出节目",
        ) else guide
        val current = mutableStateOf(guide.last())
        val isReplay = mutableStateOf(!startLive)
        val position = mutableLongStateOf(current.value.startAt + 1_000)
        val targets = mutableListOf<Long>()
        compose.setContent {
            MyTvTheme {
                VideoPlayerControllerScreen(
                    channelProvider = { Channel(name = "跨日频道") },
                    currentPlaybackEpgProgrammeProvider = { current.value.takeIf { isReplay.value } },
                    recentEpgProgrammeProvider = { EpgProgrammeRecent(current.value) },
                    previousProgrammeProvider = { PlaybackProgrammeNavigation.adjacent(schedule, current.value, -1) },
                    nextProgrammeProvider = { PlaybackProgrammeNavigation.adjacent(schedule, current.value, 1) },
                    canStartProgramme = { it.startAt < livePosition },
                    onProgrammePlayback = {
                        targets += it.startAt
                        current.value = it
                        isReplay.value = true
                        position.longValue = it.startAt + 1_000
                    },
                    videoPlayerCurrentPositionProvider = { position.longValue },
                    videoPlayerDurationProvider = { current.value.startAt to current.value.endAt },
                    videoPlayerPositionIsAbsoluteProvider = { true },
                    onVideoPlayerSeekTo = { seek.set(it); position.longValue = it },
                    onVideoPlayerPause = { pause.incrementAndGet() },
                    isVideoPlayerPlayingProvider = { true },
                    showChannelLogo = false,
                )
            }
        }
        return guide to targets
    }

    @Test fun consecutiveTouchesKeepMovingToEarlierProgrammesAcrossMidnight() {
        val (guide, targets) = showChangingProgrammes()
        repeat(3) { index ->
            compose.onNodeWithContentDescription("上一节目").performTouchInput { click() }
            compose.runOnIdle { assertEquals(guide[2 - index].startAt, targets.last()) }
        }
        compose.runOnIdle { assertEquals(listOf(guide[2].startAt, guide[1].startAt, guide[0].startAt), targets) }
        compose.onNodeWithContentDescription("上一节目").assertIsNotEnabled()
        repeat(3) { index ->
            compose.onNodeWithContentDescription("下一节目").performTouchInput { click() }
            compose.runOnIdle { assertEquals(guide[index + 1].startAt, targets.last()) }
        }
        compose.onNodeWithContentDescription("下一节目").assertIsNotEnabled()
    }

    @Test fun remoteConfirmKeepsFocusForConsecutiveProgrammeChanges() {
        val (guide, targets) = showChangingProgrammes()
        val button = compose.onNodeWithContentDescription("上一节目")
        button.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        repeat(2) { index ->
            button.performKeyInput { pressKey(Key.DirectionCenter) }
            compose.runOnIdle { assertEquals(guide[2 - index].startAt, targets.last()) }
            button.assertIsFocused()
        }
    }

    @Test fun remoteFocusReturnsToPauseWhenNextProgrammeBecomesUnavailable() {
        val (guide, targets) = showChangingProgrammes(startLive = true)
        val nextButton = compose.onNodeWithContentDescription("下一节目")
        nextButton.assertIsNotEnabled()
        compose.onNodeWithContentDescription("上一节目")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performKeyInput { pressKey(Key.DirectionCenter) }
        nextButton.assertIsEnabled()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(guide.takeLast(2).map { it.startAt }, targets) }
        nextButton.assertIsNotEnabled()
        compose.onNodeWithContentDescription("暂停播放").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(1, pause.get())
    }

    @Test fun tenSecondButtonsSeekWithinTheNewProgrammeAfterTappingPrevious() {
        val (guide, _) = showChangingProgrammes()
        compose.onNodeWithContentDescription("上一节目").performTouchInput { click() }
        val selected = guide[2]
        compose.onNodeWithContentDescription("前进10秒").assertIsEnabled().performTouchInput { click() }
        compose.onNodeWithText("${clock(selected.startAt + 11_000)} / ${clock(selected.endAt)}").assertExists()
        compose.waitUntil(5_000) { seek.get() != Long.MIN_VALUE }
        assertEquals(selected.startAt + 11_000, seek.get())

        seek.set(Long.MIN_VALUE)
        compose.onNodeWithContentDescription("后退10秒").assertIsEnabled().performTouchInput { click() }
        compose.waitUntil(5_000) { seek.get() != Long.MIN_VALUE }
        assertEquals(selected.startAt + 1_000, seek.get())
    }
}

package top.yogiczy.mytv.tv.ui

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.epg.Epg
import top.yogiczy.mytv.core.data.entities.epg.EpgList
import top.yogiczy.mytv.core.data.entities.epg.EpgList.Companion.recentProgramme
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme
import top.yogiczy.mytv.core.data.entities.epg.EpgProgrammeList
import top.yogiczy.mytv.core.data.entities.epg.EpgProgrammeRecent
import top.yogiczy.mytv.tv.ui.screen.channels.components.rememberEpgProgrammeRecent
import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercise real Compose state/effects without an Activity or device-specific Espresso hooks. */
class RecentEpgProgrammeStateTest {
    private val channel = Channel(name = "Channel", epgId = "test-channel")

    @Test fun coldStartPlaceholderChangesToRealGuideWithoutReenteringTheScreen() {
        val guide = mutableStateOf(EpgList())
        val provider = { guide.value.recentProgramme(channel) }
        val result = AtomicReference<EpgProgrammeRecent?>()
        RuntimeComposition().use { composition ->
            composition.start { val current = rememberEpgProgrammeRecent(provider); SideEffect { result.set(current) } }
            await { result.get()?.now?.title == "无信息" }
            composition.main { guide.value = guide("Current", "Next") }
            await { result.get()?.now?.title == "Current" && result.get()?.next?.title == "Next" }
        }
    }

    @Test fun loadedGuideRefreshAndRemovalBothUpdateTheSameCard() {
        val guide = mutableStateOf(guide("Before", "Before next"))
        val provider = { guide.value.recentProgramme(channel) }
        val result = AtomicReference<EpgProgrammeRecent?>()
        RuntimeComposition().use { composition ->
            composition.start { val current = rememberEpgProgrammeRecent(provider); SideEffect { result.set(current) } }
            await { result.get()?.now?.title == "Before" }
            composition.main { guide.value = guide("After", "After next") }
            await { result.get()?.now?.title == "After" && result.get()?.next?.title == "After next" }
            composition.main { guide.value = EpgList() }
            await { result.get()?.now?.title == "无信息" }
        }
    }

    @Test fun replacingProviderUsesItsLatestChannelAndDoesNotRetainTheOldResult() {
        val provider = mutableStateOf<() -> EpgProgrammeRecent?>({ EpgProgrammeRecent(now = programme("First")) })
        val result = AtomicReference<EpgProgrammeRecent?>()
        RuntimeComposition().use { composition ->
            composition.start { val current = rememberEpgProgrammeRecent(provider.value); SideEffect { result.set(current) } }
            await { result.get()?.now?.title == "First" }
            composition.main { provider.value = { EpgProgrammeRecent(now = programme("Second")) } }
            await { result.get()?.now?.title == "Second" }
            composition.main { provider.value = { null } }
            await { result.get() == null }
        }
    }

    @Test fun programmeBoundaryUpdatesWithoutAnyGuideMutation() {
        val boundary = System.currentTimeMillis() + 1_500
        val guide = EpgList(listOf(Epg(channelId = channel.epgId, programmeList = EpgProgrammeList(listOf(
            EpgProgramme(boundary - 60_000, boundary, "Ending"),
            EpgProgramme(boundary, boundary + 60_000, "Following"),
        )))))
        val provider = { guide.recentProgramme(channel) }
        val result = AtomicReference<EpgProgrammeRecent?>()
        RuntimeComposition().use { composition ->
            composition.start { val current = rememberEpgProgrammeRecent(provider); SideEffect { result.set(current) } }
            await { result.get()?.now?.title == "Ending" }
            await { result.get()?.now?.title == "Following" }
        }
    }

    @Test fun initiallyNullResultStillObservesLaterSnapshotUpdates() {
        val state = mutableStateOf<EpgProgrammeRecent?>(null)
        val provider = { state.value }
        val result = AtomicReference<EpgProgrammeRecent?>()
        RuntimeComposition().use { composition ->
            composition.start { val current = rememberEpgProgrammeRecent(provider); SideEffect { result.set(current) } }
            composition.main { state.value = EpgProgrammeRecent(now = programme("Loaded")) }
            await { result.get()?.now?.title == "Loaded" }
        }
    }

    private fun programme(title: String) = EpgProgramme(
        System.currentTimeMillis() - 60_000, System.currentTimeMillis() + 60_000, title)

    private fun guide(current: String, next: String): EpgList {
        val now = System.currentTimeMillis()
        return EpgList(listOf(Epg(channelId = channel.epgId, programmeList = EpgProgrammeList(listOf(
            EpgProgramme(now - 60_000, now + 60_000, current),
            EpgProgramme(now + 60_000, now + 120_000, next),
        )))))
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("Programme state did not update without navigation", condition())
    }

    private class RuntimeComposition : Closeable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val clock = BroadcastFrameClock()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + clock)
        private val recomposer = Recomposer(scope.coroutineContext)
        private val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun onClear() = Unit
        }, recomposer)

        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

        fun start(content: @Composable () -> Unit) = main {
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            scope.launch {
                while (isActive) {
                    Snapshot.sendApplyNotifications()
                    clock.sendFrame(System.nanoTime())
                    delay(16)
                }
            }
            composition.setContent(content)
        }

        override fun close() = main {
            composition.dispose()
            recomposer.cancel()
            scope.cancel()
        }
    }
}

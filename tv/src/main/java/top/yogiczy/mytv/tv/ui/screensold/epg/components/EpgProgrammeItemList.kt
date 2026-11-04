package top.yogiczy.mytv.tv.ui.screensold.epg.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.epg.Epg
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme.Companion.isLive
import top.yogiczy.mytv.core.data.entities.epg.EpgProgrammeList
import top.yogiczy.mytv.core.data.entities.epg.EpgProgrammeReserveList
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import top.yogiczy.mytv.tv.ui.utils.focusOnLaunchedSaveable
import top.yogiczy.mytv.tv.ui.utils.ifElse
import top.yogiczy.mytv.tv.ui.utils.saveRequestFocus
import kotlin.math.max

@Composable
fun EpgProgrammeItemList(
    modifier: Modifier = Modifier,
    epgProgrammeListProvider: () -> EpgProgrammeList = { EpgProgrammeList() },
    epgProgrammeReserveListProvider: () -> EpgProgrammeReserveList = { EpgProgrammeReserveList() },
    supportPlaybackProvider: () -> Boolean = { false },
    currentPlaybackProvider: () -> EpgProgramme? = { null },
    onPlayback: (EpgProgramme) -> Unit = {},
    onReserve: (EpgProgramme) -> Unit = {},
    focusOnInitialProgramme: Boolean = true,
    onUserAction: () -> Unit = {},
) {
    val epgProgrammeList = epgProgrammeListProvider()
    if (epgProgrammeList.isEmpty()) {
        // YYKM uses an empty-state view, never a fake programme with replay/reserve actions.
        Box(
            modifier = modifier.fillMaxHeight().fillMaxWidth()
                .ifElse(focusOnInitialProgramme, Modifier.focusOnLaunchedSaveable())
                .focusable(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text("暂无节目单", Modifier.padding(vertical = 20.dp))
        }
        return
    }
    val initialIndex = remember(epgProgrammeList) {
        val playbackIndex = epgProgrammeList.indexOf(currentPlaybackProvider())
        if (playbackIndex >= 0) playbackIndex
        else epgProgrammeList.indexOfFirst { it.isLive() }.coerceAtLeast(0)
    }
    val itemFocusRequesterList = remember(epgProgrammeList) {
        List(epgProgrammeList.size) { FocusRequester() }
    }
    var detailsProgramme by remember { mutableStateOf<EpgProgramme?>(null) }
    var detailsOrigin by remember { mutableStateOf<FocusRequester?>(null) }
    LaunchedEffect(detailsProgramme) {
        if (detailsProgramme == null && detailsOrigin != null) {
            // Let the popup dispose its focus scope before restoring the row.
            withFrameNanos { }
            detailsOrigin?.saveRequestFocus()
            detailsOrigin = null
        }
    }

    val listState = remember(epgProgrammeList) { LazyListState(max(0, initialIndex - 2)) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { _ -> onUserAction() }
    }

    Column(modifier = modifier.fillMaxHeight()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(
                epgProgrammeList,
                key = { _, programme -> programme.hashCode() },
            ) { index, programme ->
                EpgProgrammeItem(
                    modifier = Modifier
                        .focusRequester(itemFocusRequesterList[index])
                        .ifElse(
                            focusOnInitialProgramme && index == initialIndex,
                            Modifier.focusOnLaunchedSaveable(),
                        ),
                    epgProgrammeProvider = { programme },
                    supportPlaybackProvider = supportPlaybackProvider,
                    isPlaybackProvider = { currentPlaybackProvider() == programme },
                    hasReservedProvider = { epgProgrammeReserveListProvider().firstOrNull { it.programme == programme.title } != null },
                    onPlayback = { onPlayback(programme) },
                    onReserve = { onReserve(programme) },
                    onDetails = {
                        onUserAction()
                        detailsOrigin = itemFocusRequesterList[index]
                        detailsProgramme = programme
                    },
                    // Only the chosen initial programme may request focus. The live
                    // row must not compete with a programme currently being replayed.
                    focusOnLive = false,
                )
            }
        }
        if (epgProgrammeList.any { !it.description.isNullOrBlank() }) {
            Text(
                "长按确认 / 触摸长按查看完整简介",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = LocalContentColor.current.copy(alpha = 0.6f),
                maxLines = 1,
            )
        }
    }
    EpgProgrammeDetailsPopup(
        programme = detailsProgramme,
        onDismissRequest = {
            detailsProgramme = null
            onUserAction()
        },
        onUserAction = onUserAction,
    )
}

@Preview
@Composable
private fun EpgProgrammeItemListPreview() {
    MyTvTheme {
        EpgProgrammeItemList(
            epgProgrammeListProvider = { EpgProgrammeList(Epg.example(Channel.EXAMPLE).programmeList) }
        )
    }
}

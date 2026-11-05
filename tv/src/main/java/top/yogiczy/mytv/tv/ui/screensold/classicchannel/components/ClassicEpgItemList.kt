package top.yogiczy.mytv.tv.ui.screensold.classicchannel.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.collections.immutable.toPersistentList
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.epg.Epg
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme.Companion.isLive
import top.yogiczy.mytv.core.data.entities.epg.EpgProgrammeList
import top.yogiczy.mytv.core.data.entities.epg.EpgProgrammeReserveList
import top.yogiczy.mytv.tv.ui.screensold.epg.components.EpgDayItemList
import top.yogiczy.mytv.tv.ui.screensold.epg.components.EpgProgrammeItemList
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import top.yogiczy.mytv.tv.ui.tooling.PreviewWithLayoutGrids
import top.yogiczy.mytv.tv.ui.utils.gridColumns
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun ClassicEpgItemList(
    modifier: Modifier = Modifier,
    programmeListModifier: Modifier = Modifier,
    programmeListWidth: Dp = 6.gridColumns(),
    epgProvider: () -> Epg? = { null },
    channelNameProvider: () -> String = { "" },
    epgProgrammeReserveListProvider: () -> EpgProgrammeReserveList = { EpgProgrammeReserveList() },
    supportPlaybackProvider: () -> Boolean = { false },
    currentPlaybackEpgProgrammeProvider: () -> EpgProgramme? = { null },
    onEpgProgrammePlayback: (EpgProgramme) -> Unit = {},
    onLive: () -> Unit = {},
    onEpgProgrammeReserve: (EpgProgramme) -> Unit = {},
    onUserAction: () -> Unit = {},
) {
    val epg = epgProvider()
    val programmes = epg?.programmeList ?: EpgProgrammeList()
    val dateFormat = SimpleDateFormat("E MM-dd", Locale.getDefault())
    val programDayGroup = programmes.groupBy { dateFormat.format(it.startAt) }
    val initialProgramme = currentPlaybackEpgProgrammeProvider()
        ?.takeIf { it in programmes } ?: programmes.firstOrNull { it.isLive() }
    val today = dateFormat.format(System.currentTimeMillis())
    val initialDay = initialProgramme?.let { dateFormat.format(it.startAt) }
        ?: today.takeIf { it in programDayGroup }
        ?: programDayGroup.keys.firstOrNull().orEmpty()
    var currentDay by remember(epg?.channelList, epg?.channelId) {
        mutableStateOf(initialDay)
    }
    // Loading/refresh can replace an empty guide or remove the selected day.
    val displayedDay = currentDay.takeIf { it in programDayGroup } ?: initialDay
    // Only initial entry/guide loading may focus a programme automatically.
    // Browsing dates must keep focus in the date rail across list replacement.
    var focusOnInitialProgramme by remember { mutableStateOf(true) }

    Column(
        modifier = modifier
            // Bound the title as well as the rows, and reserve the date rail.
            .width(programmeListWidth + 24.dp + if (programDayGroup.size > 1) 90.dp else 0.dp)
            .fillMaxHeight()
            // The channel/EPG host owns the background; avoid stacking translucent layers.
            .padding(start = 12.dp, end = 12.dp),
    ) {
        Text(
            channelNameProvider().ifBlank { "节目单" },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EpgProgrammeItemList(
                modifier = Modifier.width(programmeListWidth).then(programmeListModifier),
                epgProgrammeListProvider = {
                    EpgProgrammeList(programDayGroup[displayedDay].orEmpty())
                },
                epgProgrammeReserveListProvider = epgProgrammeReserveListProvider,
                supportPlaybackProvider = supportPlaybackProvider,
                currentPlaybackProvider = currentPlaybackEpgProgrammeProvider,
                focusOnInitialProgramme = focusOnInitialProgramme,
                onPlayback = onEpgProgrammePlayback,
                onLive = onLive,
                onReserve = onEpgProgrammeReserve,
                onUserAction = onUserAction,
            )

            if (programDayGroup.size > 1) {
                EpgDayItemList(
                    modifier = Modifier.width(80.dp),
                    dayListProvider = { programDayGroup.keys.toPersistentList() },
                    currentDayProvider = { displayedDay },
                    onDaySelected = {
                        focusOnInitialProgramme = false
                        currentDay = it
                    },
                    onUserAction = onUserAction,
                )
            }
        }
    }
}

@Preview(device = "id:Android TV (720p)")
@Composable
private fun ClassicEpgItemListPreview() {
    MyTvTheme {
        PreviewWithLayoutGrids {
            ClassicEpgItemList(
                epgProvider = { Epg.example(Channel.EXAMPLE) },
            )
        }
    }
}

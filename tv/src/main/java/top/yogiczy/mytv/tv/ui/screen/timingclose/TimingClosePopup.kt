package top.yogiczy.mytv.tv.ui.screen.timingclose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import top.yogiczy.mytv.core.data.entities.timing.TimingCloseTimer
import top.yogiczy.mytv.tv.ui.material.Drawer
import top.yogiczy.mytv.tv.ui.material.DrawerPosition
import top.yogiczy.mytv.tv.ui.material.SimplePopup
import top.yogiczy.mytv.tv.ui.material.Snackbar
import top.yogiczy.mytv.tv.ui.theme.colors
import top.yogiczy.mytv.tv.ui.utils.clickableNoIndication
import top.yogiczy.mytv.tv.ui.utils.focusOnLaunchedSaveable
import top.yogiczy.mytv.tv.ui.utils.ifElse

@Composable
fun TimingClosePopup() {
    val viewModel = LocalTimingCloseViewModel.current
    val selectMinutes: (Int) -> Unit = { minutes ->
        viewModel.selectMinutes(minutes)
        Snackbar.show(if (minutes > 0) "将在 $minutes 分钟后退出应用" else "定时关闭已取消")
    }

    SimplePopup(
        visibleProvider = { viewModel.showPicker },
        onDismissRequest = { viewModel.showPicker = false },
    ) {
        val status by viewModel.status.collectAsState()
        Drawer(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            position = DrawerPosition.Center,
            header = { Text("定时关闭") },
            onDismissRequest = { viewModel.showPicker = false },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = if (status.isActive) "剩余 ${status.remainingMinutes} 分钟后退出应用" else "未开启 · 到时停止播放并退出应用",
                    style = MaterialTheme.typography.bodyMedium,
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.heightIn(max = 112.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                ) {
                    items(TimingCloseTimer.PRESET_MINUTES) { minutes ->
                        val selected = status.isActive && status.selectedMinutes == minutes
                        Card(
                            modifier = Modifier
                                .ifElse(
                                    minutes == if (status.isActive) status.selectedMinutes else 15,
                                    Modifier.focusOnLaunchedSaveable(),
                                )
                                .clickableNoIndication { selectMinutes(minutes) }
                                .semantics { this.selected = selected },
                            onClick = { selectMinutes(minutes) },
                            scale = CardDefaults.scale(focusedScale = 1f),
                            colors = CardDefaults.colors(
                                containerColor = MaterialTheme.colors.surfaceContainerHigh,
                                focusedContainerColor = MaterialTheme.colorScheme.onSurface,
                                focusedContentColor = MaterialTheme.colorScheme.surface,
                            ),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("$minutes 分钟", style = MaterialTheme.typography.bodyMedium)
                                if (selected) Icon(Icons.Default.Check, "当前定时", Modifier.size(18.dp))
                            }
                        }
                    }
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth().clickableNoIndication { selectMinutes(0) },
                    onClick = { selectMinutes(0) },
                ) { Text("取消定时") }
                Text(
                    "重新选择将从现在开始计时；退出应用后本次定时失效。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

package top.yogiczy.mytv.tv.ui.screensold.epg.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme
import top.yogiczy.mytv.tv.ui.material.Drawer
import top.yogiczy.mytv.tv.ui.material.DrawerPosition
import top.yogiczy.mytv.tv.ui.material.SimplePopup
import top.yogiczy.mytv.tv.ui.utils.focusOnLaunched
import top.yogiczy.mytv.tv.ui.utils.handleKeyEvents
import top.yogiczy.mytv.tv.ui.utils.saveRequestFocus
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
internal fun EpgProgrammeDetailsPopup(
    programme: EpgProgramme?,
    onDismissRequest: () -> Unit,
    onUserAction: () -> Unit,
) {
    val currentOnUserAction = rememberUpdatedState(onUserAction)
    LaunchedEffect(programme) {
        if (programme != null) {
            // Reading a long synopsis must not time out the underlying guide.
            while (true) {
                currentOnUserAction.value()
                delay(1_000)
            }
        }
    }
    SimplePopup(
        visibleProvider = { programme != null },
        onDismissRequest = onDismissRequest,
    ) {
        programme?.let {
            Drawer(
                modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth(),
                position = DrawerPosition.Center,
                header = { Text("节目详情") },
                onDismissRequest = onDismissRequest,
            ) {
                EpgProgrammeDetailsContent(it, onDismissRequest)
            }
        }
    }
}

@Composable
private fun EpgProgrammeDetailsContent(programme: EpgProgramme, onClose: () -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val closeFocus = remember { FocusRequester() }
    val step = with(LocalDensity.current) { 100.dp.toPx() }
    val formatter = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(programme.title, style = MaterialTheme.typography.titleLarge,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("${formatter.format(programme.startAt)} — ${formatter.format(programme.endAt)}",
            style = MaterialTheme.typography.labelLarge)
        Text(
            programme.description.orEmpty(),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                .focusOnLaunched(programme)
                .onPreviewKeyEvent { event ->
                    when (event.key) {
                        Key.DirectionDown, Key.DirectionUp -> {
                            if (event.type == KeyEventType.KeyDown) {
                                if (event.key == Key.DirectionDown && !scroll.canScrollForward) {
                                    closeFocus.saveRequestFocus()
                                } else {
                                    scope.launch {
                                        scroll.animateScrollBy(if (event.key == Key.DirectionDown) step else -step)
                                    }
                                }
                            }
                            true
                        }
                        else -> false
                    }
                }
                .verticalScroll(scroll)
                .semantics { contentDescription = "节目简介" }
                .focusable(),
        )
        OutlinedButton(
            onClick = {},
            modifier = Modifier.focusRequester(closeFocus).handleKeyEvents(onSelect = onClose),
        ) { Text("关闭详情") }
    }
}

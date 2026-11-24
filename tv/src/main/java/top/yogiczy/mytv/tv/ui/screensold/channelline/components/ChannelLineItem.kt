package top.yogiczy.mytv.tv.ui.screensold.channelline.components

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackException
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.RadioButton
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import top.yogiczy.mytv.core.data.entities.channel.ChannelLine
import top.yogiczy.mytv.core.data.network.ChannelLineProbe
import top.yogiczy.mytv.core.data.utils.ChannelPlayback
import top.yogiczy.mytv.core.data.utils.ChannelUtil
import top.yogiczy.mytv.core.util.utils.isIPv6
import top.yogiczy.mytv.tv.ui.material.Tag
import top.yogiczy.mytv.tv.ui.material.TagDefaults
import top.yogiczy.mytv.tv.ui.screen.settings.settingsVM
import top.yogiczy.mytv.tv.ui.screensold.channelline.StreamingChannelLineProbe
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import top.yogiczy.mytv.tv.ui.utils.Configs
import top.yogiczy.mytv.tv.ui.utils.focusOnLaunchedSaveable
import top.yogiczy.mytv.tv.ui.utils.handleKeyEvents
import top.yogiczy.mytv.tv.ui.utils.ifElse
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

private sealed interface LineProbeStatus {
    data class Measured(val millis: Long) : LineProbeStatus
    data object Unsupported : LineProbeStatus
    data class Failed(val label: String) : LineProbeStatus
}

@Composable
fun ChannelLineItem(
    modifier: Modifier = Modifier,
    lineProvider: () -> ChannelLine = { ChannelLine() },
    lineIdxProvider: () -> Int = { 0 },
    isSelectedProvider: () -> Boolean = { false },
    onSelected: () -> Unit = {},
) {
    val line = lineProvider()
    val lineIdx = lineIdxProvider()
    val isSelected = isSelectedProvider()

    val probeStatus = if (settingsVM.videoPlayerLineProbeEnabled) rememberProbeStatus(line) else null

    ListItem(
        modifier = modifier
            .ifElse(isSelected, Modifier.focusOnLaunchedSaveable())
            .handleKeyEvents(onSelect = onSelected),
        selected = false,
        onClick = {},
        headlineContent = { Text(line.name ?: "线路${lineIdx + 1}", maxLines = 1) },
        overlineContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (line.url.startsWith("webview://")) {
                    Tag("混合")
                    Tag(ChannelUtil.getHybridWebViewUrlProvider(line.url))
                } else {
                    Tag(if (line.url.isIPv6()) "IPv6" else "IPv4")

                    if (ChannelPlayback.isSupported(line)) Tag("回放")

                    when (probeStatus) {
                        is LineProbeStatus.Measured -> {
                            val label = "${probeStatus.millis} ms"
                            if (probeStatus.millis < 500) {
                                Tag(label, colors = TagDefaults.colors(containerColor = MaterialTheme.colorScheme.tertiary))
                            } else {
                                Tag(label)
                            }
                        }
                        LineProbeStatus.Unsupported -> Tag("暂不支持测速")
                        is LineProbeStatus.Failed -> Tag(
                            probeStatus.label,
                            colors = TagDefaults.colors(containerColor = MaterialTheme.colorScheme.error),
                        )
                        null -> Unit
                    }
                }
            }
        },
        supportingContent = { Text(line.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            RadioButton(selected = isSelected, onClick = {})
        },
    )
}

@Composable
private fun rememberProbeStatus(line: ChannelLine): LineProbeStatus? {
    val context = LocalContext.current
    var status by remember(line) { mutableStateOf<LineProbeStatus?>(null) }

    LaunchedEffect(line) {
        val requestLine = line.resolveHttpOptions()
        val url = requestLine.playableUrl
        status = try {
            val millis = if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
                ChannelLineProbe.Default.measure(url, requestLine.httpUserAgent, requestLine.httpRequestHeaders())
            } else {
                StreamingChannelLineProbe.measure(
                    context,
                    Uri.parse(url),
                    requestLine.httpUserAgent ?: Configs.videoPlayerUserAgent,
                    Configs.videoPlayerRtspTcp,
                )
            }
            millis?.let { LineProbeStatus.Measured(it) } ?: LineProbeStatus.Unsupported
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LineProbeStatus.Failed(e.probeFailureLabel())
        }
    }

    return status
}

private fun Exception.probeFailureLabel(): String {
    var connectionFailed = false
    for (cause in generateSequence<Throwable>(this) { it.cause }) {
        when {
            cause is ChannelLineProbe.HttpError -> return "HTTP ${cause.statusCode}"
            cause is ChannelLineProbe.EmptyResponse -> return "空响应"
            cause is SocketTimeoutException -> return "超时"
            // OkHttp's total call deadline uses InterruptedIOException rather than SocketTimeoutException.
            cause is InterruptedIOException && cause.message == "timeout" -> return "超时"
            cause is PlaybackException && cause.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                return "超时"
            cause is IOException ||
                (cause is PlaybackException && cause.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED) ->
                connectionFailed = true
        }
    }
    return if (connectionFailed) "连接失败" else "测速失败"
}

@Preview
@Composable
private fun ChannelLineItemPreview() {
    MyTvTheme {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ChannelLineItem(
                lineProvider = { ChannelLine.EXAMPLE },
                lineIdxProvider = { 0 },
                isSelectedProvider = { true },
            )

            ChannelLineItem(
                lineProvider = { ChannelLine("http://[2409:8087:5e01:34::20]:6610/ZTE_CMS/00000001000000060000000000000131/index.m3u8?IAS") },
                lineIdxProvider = { 0 },
            )

            ChannelLineItem(
                lineProvider = {
                    ChannelLine(
                        url = "webview://https://tv.cctv.com/live/cctv1/",
                    )
                },
                lineIdxProvider = { 0 },
                isSelectedProvider = { true },
            )
        }
    }
}

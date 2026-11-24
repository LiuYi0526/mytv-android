package top.yogiczy.mytv.tv.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.channel.ChannelLine
import top.yogiczy.mytv.core.data.utils.SP
import top.yogiczy.mytv.tv.ui.screen.settings.SettingsViewModel
import top.yogiczy.mytv.tv.ui.screensold.channelline.components.ChannelLineItem
import top.yogiczy.mytv.tv.ui.theme.MyTvTheme
import top.yogiczy.mytv.tv.ui.utils.Configs
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class ChannelLineProbeStatusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun linesShowDistinctNetworkAndResponseFailures() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val originalViewModel = SettingsViewModel.instance
        SP.init(instrumentation.context)
        val previousSetting = Configs.videoPlayerLineProbeEnabled
        Configs.videoPlayerLineProbeEnabled = true
        SettingsViewModel.instance = null
        val loopback = InetAddress.getByName("127.0.0.1")
        try {
            ServerSocket(0, 4, loopback).use { server ->
                val closedPort = ServerSocket(0, 1, loopback).use { it.localPort }
                serveProbes(server)
                val urls = listOf(
                    "http://127.0.0.1:${server.localPort}/timeout",
                    "http://127.0.0.1:$closedPort/refused",
                    "http://127.0.0.1:${server.localPort}/missing",
                    "http://127.0.0.1:${server.localPort}/empty",
                )
                compose.setContent {
                    MyTvTheme {
                        Column {
                            urls.forEachIndexed { index, url ->
                                ChannelLineItem(
                                    modifier = Modifier.testTag("probe-line-$index"),
                                    lineProvider = { ChannelLine(url) },
                                    lineIdxProvider = { index },
                                )
                            }
                        }
                    }
                }
                val labels = listOf("超时", "连接失败", "HTTP 404", "空响应")
                compose.waitUntil(timeoutMillis = 15_000) {
                    labels.withIndex().all { (index, label) ->
                        compose.onAllNodes(
                            hasText(label) and hasAnyAncestor(hasTestTag("probe-line-$index")),
                            useUnmergedTree = true,
                        ).fetchSemanticsNodes().size == 1
                    }
                }
            }
        } finally {
            SettingsViewModel.instance = originalViewModel
            Configs.videoPlayerLineProbeEnabled = previousSetting
            SP.init(instrumentation.targetContext)
        }
    }

    private fun serveProbes(server: ServerSocket) {
        thread(isDaemon = true, name = "Line probe test server") {
            repeat(3) {
                val socket = runCatching { server.accept() }.getOrNull() ?: return@thread
                thread(isDaemon = true) receiver@{
                    socket.use {
                        val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val request = input.readLine().orEmpty()
                        while (!input.readLine().isNullOrEmpty()) Unit
                        val status = when {
                            request.startsWith("GET /missing ") -> "404 Not Found"
                            request.startsWith("GET /empty ") -> "204 No Content"
                            else -> {
                                // Wait for the probe's own deadline to close the connection.
                                input.read()
                                return@receiver
                            }
                        }
                        socket.getOutputStream().write(
                            "HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                .toByteArray(Charsets.US_ASCII),
                        )
                    }
                }
            }
        }
    }
}

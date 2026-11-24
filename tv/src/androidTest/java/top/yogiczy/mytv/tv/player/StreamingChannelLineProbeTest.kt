package top.yogiczy.mytv.tv.player

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.yogiczy.mytv.tv.ui.screensold.channelline.StreamingChannelLineProbe
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

@RunWith(AndroidJUnit4::class)
class StreamingChannelLineProbeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test fun udpAndRtpWaitForADatagram() = runBlocking {
        for (scheme in listOf("udp", "rtp")) {
            val port = freePort()
            val sender = launch(Dispatchers.IO) {
                DatagramSocket().use { socket ->
                    val packet = DatagramPacket(byteArrayOf(0x80.toByte(), 33), 2, loopback, port)
                    repeat(30) {
                        delay(50)
                        socket.send(packet)
                    }
                }
            }
            try {
                val latency = measure("$scheme://127.0.0.1:$port")
                assertTrue(latency != null && latency > 0)
            } finally {
                sender.cancelAndJoin()
            }
        }
    }

    @Test fun noDatagramTimesOut() {
        assertThrows(SocketTimeoutException::class.java) {
            runBlocking { measure("udp://127.0.0.1:${freePort()}") }
        }
    }

    @Test fun unknownSchemeHasNoResult() = runBlocking {
        assertNull(measure("file:///test.ts"))
    }

    private fun freePort() = DatagramSocket(0, loopback).use { it.localPort }

    private suspend fun measure(url: String) =
        StreamingChannelLineProbe.measure(context, Uri.parse(url), "MyTV", false)
}

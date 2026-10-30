package top.yogiczy.mytv.tv.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.drm.ExoMediaDrm
import org.junit.Assert.*
import org.junit.Test
import top.yogiczy.mytv.tv.ui.screensold.videoplayer.player.WidevineLicenseCallback

@androidx.annotation.OptIn(UnstableApi::class)
class WidevineLicenseCallbackTest {
    @Test fun postsOpaqueChallengeToPlaylistProxyAndReturnsOpaqueLicense() {
        val challenge = byteArrayOf(0, -1, 12, -128, 0, 45)
        val license = byteArrayOf(-1, 0, -128, 9, 7)
        var captured: DataSpec? = null
        val factory = DataSource.Factory {
            val delegate = ByteArrayDataSource(license)
            object : DataSource by delegate {
                override fun open(dataSpec: DataSpec): Long {
                    captured = dataSpec
                    return delegate.open(dataSpec)
                }
            }
        }
        val url = "http://example.test/singtelcast/license?s=wv&token=a=b=="
        val callback = WidevineLicenseCallback.create(url, factory, mapOf("User-Agent" to "PlayerTest/1"))
        val response = callback.executeKeyRequest(C.WIDEVINE_UUID,
            ExoMediaDrm.KeyRequest(challenge, "https://upstream.test/license"))
        assertArrayEquals(license, response.data)
        assertEquals(DataSpec.HTTP_METHOD_POST, captured!!.httpMethod)
        assertEquals(url, captured!!.uri.toString())
        assertArrayEquals(challenge, captured!!.httpBody)
        assertEquals("application/octet-stream", captured!!.httpRequestHeaders["Content-Type"])
        assertEquals("PlayerTest/1", captured!!.httpRequestHeaders["User-Agent"])
    }

    @Test fun acceptsWidevineNamesButDoesNotTreatClearKeyAsWidevine() {
        listOf("com.widevine.alpha", " WIDEVINE ", "\"com.widevine.alpha\"").forEach {
            assertTrue(WidevineLicenseCallback.isSupported(it))
        }
        listOf(null, "clearkey", "org.w3.clearkey", "com.microsoft.playready").forEach {
            assertFalse(WidevineLicenseCallback.isSupported(it))
        }
    }

    @Test fun invalidLicenseLocationsAreRejectedBeforePlayback() {
        val factory = DataSource.Factory { ByteArrayDataSource(byteArrayOf(1)) }
        listOf(null, "", "file:///license", "001122:334455").forEach {
            assertTrue(runCatching { WidevineLicenseCallback.create(it, factory, emptyMap()) }.isFailure)
        }
    }
}

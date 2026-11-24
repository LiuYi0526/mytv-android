package top.yogiczy.mytv.tv.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.decode.DataSource
import coil.imageLoader
import coil.request.SuccessResult
import coil.size.Size
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.yogiczy.mytv.tv.ui.screen.channels.components.TrimTransparentLogo
import top.yogiczy.mytv.tv.ui.screen.channels.components.channelLogoRequest

@RunWith(AndroidJUnit4::class)
class ChannelLogoTrimTest {
    @Test
    fun trimsTransparentMarginsAndKeepsFaintEdges() = runBlocking {
        val input = Bitmap.createBitmap(120, 100, Bitmap.Config.ARGB_8888)
        Canvas(input).drawRect(25f, 30f, 95f, 70f, Paint().apply { color = Color.RED })
        input.setPixel(24, 29, Color.argb(1, 255, 255, 255))

        val output = TrimTransparentLogo.transform(input, Size.ORIGINAL)

        assertEquals(75, output.width)
        assertEquals(45, output.height)
        assertEquals(0, Color.alpha(output.getPixel(0, 0)))
        assertEquals(1, Color.alpha(output.getPixel(2, 2)))
        assertEquals(Color.RED, output.getPixel(3, 3))
    }

    @Test
    fun keepsOpaqueAndFullyTransparentImages() = runBlocking {
        val white = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val transparent = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888)

        assertSame(white, TrimTransparentLogo.transform(white, Size.ORIGINAL))
        assertSame(transparent, TrimTransparentLogo.transform(transparent, Size.ORIGINAL))
    }

    @Test
    fun phoenixLogoLoadsCroppedAndReusesMemoryCache() = runBlocking {
        val fixture = InstrumentationRegistry.getArguments().getString("phoenixLogoFixture")
        assumeTrue("Pass the Phoenix logo fixture URI to exercise the real sample", !fixture.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val request = channelLogoRequest(context, fixture)
        assertEquals(Size.ORIGINAL, request.sizeResolver.size())

        val first = context.imageLoader.execute(request)
        assertTrue("Logo request should succeed", first is SuccessResult)
        val logo = (first.drawable as BitmapDrawable).bitmap
        assertEquals(149, logo.width)
        assertEquals(99, logo.height)

        val cached = context.imageLoader.execute(request) as SuccessResult
        assertEquals(DataSource.MEMORY_CACHE, cached.dataSource)
    }
}

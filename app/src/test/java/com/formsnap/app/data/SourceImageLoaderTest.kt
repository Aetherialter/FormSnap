package com.formsnap.app.data

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.formsnap.app.data.source.SourceImageLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourceImageLoaderTest {
    private lateinit var provider: TestImageProvider
    private lateinit var loader: SourceImageLoader
    private val uri = "content://${TestImageProvider.AUTHORITY}/image"
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        provider = TestImageProvider.register(context)
        loader = SourceImageLoader(context.contentResolver)
    }
    private fun image() {
        val image = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.WHITE)
        for (y in 0 until 150) for (x in 0 until 150) image.setPixel(x, y, Color.RED)
        provider.original.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        image.recycle()
    }

    @Test fun `decode is bounded and original file is unchanged`() = runBlocking {
        image()
        val before = provider.original.readBytes()
        assertTrue("Fixture JPEG should contain encoded data", before.size > 1000)
        val fixture = android.graphics.BitmapFactory.decodeFile(provider.original.absolutePath)
        assertNotNull("Fixture JPEG should decode from its actual file", fixture)
        fixture.recycle()
        val bitmap = loader.load(uri, 400)
        assertEquals(400, bitmap.width)
        assertEquals(200, bitmap.height)
        bitmap.recycle()
        assertArrayEquals(before, provider.original.readBytes())
    }

    @Test fun `EXIF rotation gives upright dimensions for consistent provenance`() = runBlocking {
        image()
        ExifInterface(provider.original).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val before = provider.original.readBytes()
        val bitmap = loader.load(uri)
        assertEquals(400, bitmap.width)
        assertEquals(800, bitmap.height)
        assertTrue(Color.red(bitmap.getPixel(350, 50)) > 200)
        assertTrue(Color.green(bitmap.getPixel(350, 50)) < 80)
        bitmap.recycle()
        assertArrayEquals(before, provider.original.readBytes())
    }

    @Test fun `missing or invalid original fails instead of returning a blank bitmap`() = runBlocking {
        provider.original.writeText("not an image")
        assertTrue(runCatching { loader.load(uri) }.isFailure)
        provider.unavailable = true
        assertTrue(runCatching { loader.load(uri) }.isFailure)
    }
}

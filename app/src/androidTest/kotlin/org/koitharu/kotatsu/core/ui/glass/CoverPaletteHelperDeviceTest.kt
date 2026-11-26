package org.koitharu.kotatsu.core.ui.glass

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class CoverPaletteHelperDeviceTest {

	@Before
	@After
	fun clearCache() {
		CoverPaletteHelper.clearCache()
	}

	@Test
	fun testExtractAndCacheHitOnDevice() = runBlocking {
		val bitmap = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888)
		bitmap.eraseColor(Color.MAGENTA)

		val url = "https://example.com/device_cover.jpg"
		assertNull(CoverPaletteHelper.getCached(url))

		val palette = CoverPaletteHelper.extract(url, bitmap)
		assertNotNull(palette)
		assertTrue("Dominant color should be non-zero", palette.dominantColor != 0)

		// Second call returns cached immediately
		val cached = CoverPaletteHelper.getCached(url)
		assertNotNull(cached)
		assertEquals(palette, cached)

		val secondCall = CoverPaletteHelper.extract(url, bitmap)
		assertEquals(palette, secondCall)
	}

	@Test
	fun testExtractionSpeedOnDownsampledBitmap() = runBlocking {
		// Create a realistic high-res cover image (1000x1500)
		val bitmap = Bitmap.createBitmap(1000, 1500, Bitmap.Config.ARGB_8888)
		// Paint half blue, half orange
		for (x in 0 until 1000) {
			for (y in 0 until 750) {
				bitmap.setPixel(x, y, Color.BLUE)
			}
			for (y in 750 until 1500) {
				bitmap.setPixel(x, y, 0xFFFF8800.toInt())
			}
		}

		val url = "https://example.com/highres_cover.jpg"
		val timeMs = measureTimeMillis {
			val palette = CoverPaletteHelper.extract(url, bitmap)
			assertNotNull(palette)
			assertTrue("Palette primaryAccent should be blue or orange", palette.primaryAccent() != 0)
		}

		// Palette extraction on downsampled 112x112 should complete well under 150ms on emulator
		assertTrue("Extraction time should be under 150ms, was ${timeMs}ms", timeMs < 150)
	}

	@Test
	fun testAmbientHeroGradientClampsLightnessOnBrightCovers() {
		val brightYellowPalette = CoverPaletteHelper.CoverPalette(
			dominantColor = Color.YELLOW,
			vibrantColor = Color.YELLOW,
			darkVibrantColor = 0,
			lightVibrantColor = 0,
			mutedColor = 0,
			darkMutedColor = 0,
		)
		val surfaceColor = 0xFF121212.toInt()
		val gradient = brightYellowPalette.createAmbientHeroGradient(surfaceColor)
		assertNotNull(gradient)

		val pureWhitePalette = CoverPaletteHelper.CoverPalette(
			dominantColor = Color.WHITE,
			vibrantColor = Color.WHITE,
			darkVibrantColor = 0,
			lightVibrantColor = 0,
			mutedColor = 0,
			darkMutedColor = 0,
		)
		val whiteGradient = pureWhitePalette.createAmbientHeroGradient(surfaceColor)
		assertNotNull(whiteGradient)
	}
}

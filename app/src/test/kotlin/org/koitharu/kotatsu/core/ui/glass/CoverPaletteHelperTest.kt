package org.koitharu.kotatsu.core.ui.glass

import android.graphics.Color
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class CoverPaletteHelperTest {

	@Before
	@After
	fun setUpAndTearDown() {
		CoverPaletteHelper.clearCache()
	}

	@Test
	fun testCacheStoreAndRetrieve() {
		val url = "https://example.com/cover1.jpg"
		assertNull(CoverPaletteHelper.getCached(url))

		val palette = CoverPaletteHelper.CoverPalette(
			dominantColor = 0xFF112233.toInt(),
			vibrantColor = 0xFF445566.toInt(),
			darkVibrantColor = 0xFF778899.toInt(),
			lightVibrantColor = 0xFFAABBCC.toInt(),
			mutedColor = 0xFFDDEEFF.toInt(),
			darkMutedColor = 0xFF123456.toInt(),
		)

		CoverPaletteHelper.putCached(url, palette)
		val cached = CoverPaletteHelper.getCached(url)
		assertNotNull(cached)
		assertEquals(palette, cached)

		CoverPaletteHelper.clearCache()
		assertNull(CoverPaletteHelper.getCached(url))
	}

	@Test
	fun testPrimaryAccentFallbackHierarchy() {
		val vibrantPalette = CoverPaletteHelper.CoverPalette(
			dominantColor = Color.RED,
			vibrantColor = Color.GREEN,
			darkVibrantColor = Color.BLUE,
			lightVibrantColor = Color.CYAN,
			mutedColor = Color.YELLOW,
			darkMutedColor = Color.DKGRAY,
		)
		assertEquals(Color.GREEN, vibrantPalette.primaryAccent(Color.WHITE))

		val darkVibrantPalette = CoverPaletteHelper.CoverPalette(
			dominantColor = Color.RED,
			vibrantColor = 0,
			darkVibrantColor = Color.BLUE,
			lightVibrantColor = 0,
			mutedColor = 0,
			darkMutedColor = 0,
		)
		assertEquals(Color.BLUE, darkVibrantPalette.primaryAccent(Color.WHITE))

		val dominantPalette = CoverPaletteHelper.CoverPalette(
			dominantColor = Color.RED,
			vibrantColor = 0,
			darkVibrantColor = 0,
			lightVibrantColor = 0,
			mutedColor = 0,
			darkMutedColor = 0,
		)
		assertEquals(Color.RED, dominantPalette.primaryAccent(Color.WHITE))

		val mutedPalette = CoverPaletteHelper.CoverPalette(
			dominantColor = 0,
			vibrantColor = 0,
			darkVibrantColor = 0,
			lightVibrantColor = 0,
			mutedColor = Color.YELLOW,
			darkMutedColor = 0,
		)
		assertEquals(Color.YELLOW, mutedPalette.primaryAccent(Color.WHITE))

		val emptyPalette = CoverPaletteHelper.CoverPalette(
			dominantColor = 0,
			vibrantColor = 0,
			darkVibrantColor = 0,
			lightVibrantColor = 0,
			mutedColor = 0,
			darkMutedColor = 0,
		)
		assertEquals(Color.WHITE, emptyPalette.primaryAccent(Color.WHITE))
	}
}

package org.koitharu.kotatsu.reader

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.test.annotation.UiThreadTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.ReaderBackground
import org.koitharu.kotatsu.core.ui.glass.LivingWallpaperDrawable
import org.koitharu.kotatsu.reader.ui.config.ReaderSettings
import org.koitharu.kotatsu.core.model.ZoomMode
import org.koitharu.kotatsu.core.util.ext.isNightMode
import android.graphics.Bitmap

@RunWith(AndroidJUnit4::class)
class ReaderBackgroundDeviceTest {

	@Test
	fun testReaderBackgroundEntriesMatchArray() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val array = context.resources.getStringArray(R.array.reader_backgrounds)
		val entries = ReaderBackground.entries

		assertEquals("Array reader_backgrounds and ReaderBackground enum must have equal size", entries.size, array.size)
		assertEquals(6, entries.size)
		assertTrue(entries.contains(ReaderBackground.LIQUID_GLASS))
		assertEquals("Liquid Glass", array.last())
	}

	@Test
	@UiThreadTest
	fun testLiquidGlassResolvesToLivingWallpaper() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val drawable = ReaderBackground.LIQUID_GLASS.resolve(context)

		assertNotNull(drawable)
		assertTrue(
			"LIQUID_GLASS background must resolve to LivingWallpaperDrawable",
			drawable is LivingWallpaperDrawable,
		)
		assertEquals(!context.resources.isNightMode, ReaderBackground.LIQUID_GLASS.isLight(context))
	}

	@Test
	@UiThreadTest
	fun testWhiteAndBlackResolveCorrectly() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val whiteDrawable = ReaderBackground.WHITE.resolve(context)
		val blackDrawable = ReaderBackground.BLACK.resolve(context)

		assertNotNull(whiteDrawable)
		assertNotNull(blackDrawable)
		assertTrue(whiteDrawable is ColorDrawable)
		assertTrue(blackDrawable is ColorDrawable)
		assertEquals(Color.WHITE, (whiteDrawable as ColorDrawable).color)
		assertEquals(Color.BLACK, (blackDrawable as ColorDrawable).color)
		assertTrue(ReaderBackground.WHITE.isLight(context))
		assertFalse(ReaderBackground.BLACK.isLight(context))
	}

	@Test
	@UiThreadTest
	fun testReaderSettingsApplyBackground() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val testView = View(context)

		val liquidGlassSettings = ReaderSettings(
			zoomMode = ZoomMode.FIT_CENTER,
			background = ReaderBackground.LIQUID_GLASS,
			colorFilter = null,
			isReaderOptimizationEnabled = false,
			bitmapConfig = Bitmap.Config.RGB_565,
			isPagesNumbersEnabled = false,
			isPagesCropEnabledStandard = false,
			isPagesCropEnabledWebtoon = false,
		)
		liquidGlassSettings.applyBackground(testView)
		assertNull(
			"Child views in Liquid Glass mode must have null background so window nebula shows through seamlessly",
			testView.background,
		)

		val whiteSettings = ReaderSettings(
			zoomMode = ZoomMode.FIT_CENTER,
			background = ReaderBackground.WHITE,
			colorFilter = null,
			isReaderOptimizationEnabled = false,
			bitmapConfig = Bitmap.Config.RGB_565,
			isPagesNumbersEnabled = false,
			isPagesCropEnabledStandard = false,
			isPagesCropEnabledWebtoon = false,
		)
		whiteSettings.applyBackground(testView)
		assertNotNull(testView.background)
		assertTrue(testView.background is ColorDrawable)
		assertEquals(Color.WHITE, (testView.background as ColorDrawable).color)
	}
}

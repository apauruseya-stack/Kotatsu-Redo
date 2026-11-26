package org.koitharu.kotatsu.core.ui.glass

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlassPreferenceGroupDecorationDeviceTest {

	private val context: Context get() = ApplicationProvider.getApplicationContext()

	@Test
	fun testDecorationInstantiationAndTokens() {
		val decoration = GlassPreferenceGroupDecoration(context)
		assertNotNull(decoration)

		val density = context.resources.displayMetrics.density
		assertTrue("Density should be positive", density > 0f)
	}

	@Test
	fun testSquircleGlassIconBadgeCreation() {
		val density = context.resources.displayMetrics.density
		val glassDrawable = GlassDrawable.create(
			context = context,
			cornerRadiusDp = 12f,
			fillAlpha = 0.10f,
			strokeAlpha = 0.22f,
			rimAlpha = 0.60f,
		)
		assertNotNull(glassDrawable)
		assertEquals(12f * density, glassDrawable.cornerRadius, 0.01f)
	}
}

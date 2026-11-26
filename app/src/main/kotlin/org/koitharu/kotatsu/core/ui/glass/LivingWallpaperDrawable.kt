package org.koitharu.kotatsu.core.ui.glass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils

/**
 * Living Wallpaper backdrop for the Liquid Glass design language.
 * Faithfully reproduces the "Nebula" wallpaper from the prototype:
 * Base `#05060a` with four atmospheric radial gradient blobs and a subtle depth vignette.
 * Uses high-precision dithering to prevent color banding on all panels.
 */
class LivingWallpaperDrawable(val isNight: Boolean = true) : Drawable() {

	private val bgPaint = Paint().apply {
		isDither = true
		color = if (isNight) Color.parseColor("#05060A") else Color.parseColor("#F5F3FA")
	}

	private val blobPaint1 = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
	private val blobPaint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
	private val blobPaint3 = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
	private val blobPaint4 = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
	private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }

	override fun onBoundsChange(bounds: Rect) {
		super.onBoundsChange(bounds)
		val w = bounds.width().toFloat()
		val h = bounds.height().toFloat()
		if (w <= 0f || h <= 0f) return

		// Blob 1: --wall-a, left -18%, top -12%, size 75% width
		val r1 = (w * 0.75f).coerceAtLeast(10f)
		val hex1 = if (isNight) "#5B2BD8" else "#C4B5FD"
		val alpha1 = if (isNight) 0.85f else 0.55f
		val c1 = ColorUtils.setAlphaComponent(Color.parseColor(hex1), (255 * alpha1).toInt())
		blobPaint1.shader = RadialGradient(
			-w * 0.18f + r1 * 0.3f,
			-h * 0.12f + r1 * 0.3f,
			r1,
			intArrayOf(c1, c1, Color.TRANSPARENT),
			floatArrayOf(0f, 0.30f, 0.70f),
			Shader.TileMode.CLAMP,
		)

		// Blob 2: --wall-b, right -20% (center 120%), top 22%, size 70% width
		val r2 = (w * 0.70f).coerceAtLeast(10f)
		val hex2 = if (isNight) "#0F3BD8" else "#BAE6FD"
		val alpha2 = if (isNight) 0.85f else 0.50f
		val c2 = ColorUtils.setAlphaComponent(Color.parseColor(hex2), (255 * alpha2).toInt())
		blobPaint2.shader = RadialGradient(
			w * 1.20f - r2 * 0.4f,
			h * 0.22f + r2 * 0.4f,
			r2,
			intArrayOf(c2, c2, Color.TRANSPARENT),
			floatArrayOf(0f, 0.40f, 0.70f),
			Shader.TileMode.CLAMP,
		)

		// Blob 3: --wall-c, left -10%, bottom -25% (center 125%), size 80% width
		val r3 = (w * 0.80f).coerceAtLeast(10f)
		val hex3 = if (isNight) "#B04CD8" else "#E9D5FF"
		val alpha3 = if (isNight) 0.85f else 0.50f
		val c3 = ColorUtils.setAlphaComponent(Color.parseColor(hex3), (255 * alpha3).toInt())
		blobPaint3.shader = RadialGradient(
			-w * 0.10f + r3 * 0.5f,
			h * 1.25f - r3 * 0.5f,
			r3,
			intArrayOf(c3, c3, Color.TRANSPARENT),
			floatArrayOf(0f, 0.35f, 0.70f),
			Shader.TileMode.CLAMP,
		)

		// Blob 4: accent rose, right -6% (center 106%), bottom 2%, size 44% width
		val r4 = (w * 0.44f).coerceAtLeast(10f)
		val hex4 = if (isNight) "#FF5EA8" else "#FBCFE8"
		val alpha4 = if (isNight) 0.50f else 0.45f
		val c4 = ColorUtils.setAlphaComponent(Color.parseColor(hex4), (255 * alpha4).toInt())
		blobPaint4.shader = RadialGradient(
			w * 1.06f - r4 * 0.5f,
			h * 0.98f - r4 * 0.5f,
			r4,
			intArrayOf(c4, Color.TRANSPARENT),
			floatArrayOf(0f, 0.70f),
			Shader.TileMode.CLAMP,
		)

		// Vignette
		val vignetteRadius = (maxOf(w, h) * 1.1f).coerceAtLeast(10f)
		val vigAlpha = if (isNight) (255 * 0.55f).toInt() else (255 * 0.05f).toInt()
		vignettePaint.shader = RadialGradient(
			w * 0.5f,
			0f,
			vignetteRadius,
			intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, ColorUtils.setAlphaComponent(Color.BLACK, vigAlpha)),
			floatArrayOf(0f, 0.40f, 1.0f),
			Shader.TileMode.CLAMP,
		)
	}

	override fun draw(canvas: Canvas) {
		val b = bounds
		if (b.isEmpty) return
		canvas.drawRect(b, bgPaint)
		canvas.drawRect(b, blobPaint1)
		canvas.drawRect(b, blobPaint2)
		canvas.drawRect(b, blobPaint3)
		canvas.drawRect(b, blobPaint4)
		canvas.drawRect(b, vignettePaint)
	}

	override fun setAlpha(alpha: Int) {
		bgPaint.alpha = alpha
		blobPaint1.alpha = (alpha * 0.85f).toInt()
		blobPaint2.alpha = (alpha * 0.85f).toInt()
		blobPaint3.alpha = (alpha * 0.85f).toInt()
		blobPaint4.alpha = (alpha * 0.50f).toInt()
		vignettePaint.alpha = alpha
		invalidateSelf()
	}

	override fun setColorFilter(colorFilter: ColorFilter?) {
		bgPaint.colorFilter = colorFilter
		blobPaint1.colorFilter = colorFilter
		blobPaint2.colorFilter = colorFilter
		blobPaint3.colorFilter = colorFilter
		blobPaint4.colorFilter = colorFilter
		vignettePaint.colorFilter = colorFilter
		invalidateSelf()
	}

	@Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT", "android.graphics.PixelFormat"))
	override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

	companion object {
		fun createNebula(isNight: Boolean = true): LivingWallpaperDrawable = LivingWallpaperDrawable(isNight)
	}
}

package org.koitharu.kotatsu.core.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils

/**
 * Reusable Glass Drawable that renders the Liquid Glass material specification:
 * - Translucent gradient fill (with subtle hue tint)
 * - Perimeter specular stroke (1dp)
 * - Top-edge specular rim highlight sliver (1.5dp, fading towards sides)
 * Zero allocations in draw(). Dithering enabled to prevent color banding.
 */
class GlassDrawable(
	var cornerRadius: Float = 0f,
	var fillAlpha: Float = 0.055f,
	var strokeAlpha: Float = 0.20f,
	var rimAlpha: Float = 0.75f,
	var tintColor: Int = Color.WHITE,
	var strokeWidthDp: Float = 1.0f,
) : Drawable() {

	private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		isDither = true
		style = Paint.Style.FILL
	}

	private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		isDither = true
		style = Paint.Style.STROKE
	}

	private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		isDither = true
		style = Paint.Style.STROKE
	}

	private val fillRect = RectF()
	private val strokeRect = RectF()
	private val rimPath = Path()
	private var density = 1f

	fun setDensity(density: Float) {
		this.density = density
		strokePaint.strokeWidth = strokeWidthDp * density
		rimPaint.strokeWidth = 1.5f * density
		invalidateSelf()
	}

	override fun onBoundsChange(bounds: Rect) {
		super.onBoundsChange(bounds)
		val w = bounds.width().toFloat()
		val h = bounds.height().toFloat()
		if (w <= 0f || h <= 0f) return

		val strokeW = strokePaint.strokeWidth
		val halfStroke = strokeW / 2f

		fillRect.set(bounds)
		strokeRect.set(
			bounds.left + halfStroke,
			bounds.top + halfStroke,
			bounds.right - halfStroke,
			bounds.bottom - halfStroke,
		)

		// 1. Fill Shader: top subtle sheen to bottom translucent
		val fillTop = ColorUtils.setAlphaComponent(tintColor, (fillAlpha * 255f * 1.5f).toInt().coerceIn(0, 255))
		val fillBottom = ColorUtils.setAlphaComponent(tintColor, (fillAlpha * 255f * 0.4f).toInt().coerceIn(0, 255))
		fillPaint.shader = LinearGradient(
			0f, bounds.top.toFloat(),
			0f, bounds.bottom.toFloat(),
			fillTop, fillBottom,
			Shader.TileMode.CLAMP,
		)

		// 2. Stroke Shader: light catching top edge to softer bottom edge
		val strokeTop = ColorUtils.setAlphaComponent(Color.WHITE, (strokeAlpha * 255f * 1.4f).toInt().coerceIn(0, 255))
		val strokeBottom = ColorUtils.setAlphaComponent(Color.WHITE, (strokeAlpha * 255f * 0.4f).toInt().coerceIn(0, 255))
		strokePaint.shader = LinearGradient(
			0f, bounds.top.toFloat(),
			0f, bounds.bottom.toFloat(),
			strokeTop, strokeBottom,
			Shader.TileMode.CLAMP,
		)

		// 3. Specular Rim Highlight: concentrated at the top edge, fading to zero at the sides
		val rimHighlight = ColorUtils.setAlphaComponent(Color.WHITE, (rimAlpha * 255f).toInt().coerceIn(0, 255))
		rimPaint.shader = LinearGradient(
			bounds.left.toFloat(), bounds.top.toFloat(),
			bounds.right.toFloat(), bounds.top.toFloat(),
			intArrayOf(Color.TRANSPARENT, rimHighlight, rimHighlight, Color.TRANSPARENT),
			floatArrayOf(0f, 0.25f, 0.75f, 1f),
			Shader.TileMode.CLAMP,
		)

		rimPath.reset()
		val r = (cornerRadius - halfStroke).coerceAtLeast(0f)
		rimPath.moveTo(strokeRect.left, strokeRect.top + r)
		rimPath.arcTo(strokeRect.left, strokeRect.top, strokeRect.left + 2 * r, strokeRect.top + 2 * r, 180f, 90f, false)
		rimPath.lineTo(strokeRect.right - r, strokeRect.top)
		rimPath.arcTo(strokeRect.right - 2 * r, strokeRect.top, strokeRect.right, strokeRect.top + 2 * r, 270f, 90f, false)
	}

	override fun draw(canvas: Canvas) {
		val r = cornerRadius
		val strokeR = (cornerRadius - strokePaint.strokeWidth / 2f).coerceAtLeast(0f)

		canvas.drawRoundRect(fillRect, r, r, fillPaint)
		canvas.drawRoundRect(strokeRect, strokeR, strokeR, strokePaint)
		canvas.drawPath(rimPath, rimPaint)
	}

	override fun setAlpha(alpha: Int) {
		fillPaint.alpha = alpha
		strokePaint.alpha = alpha
		rimPaint.alpha = alpha
		invalidateSelf()
	}

	override fun setColorFilter(colorFilter: ColorFilter?) {
		fillPaint.colorFilter = colorFilter
		strokePaint.colorFilter = colorFilter
		rimPaint.colorFilter = colorFilter
		invalidateSelf()
	}

	@Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT", "android.graphics.PixelFormat"))
	override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

	companion object {
		fun create(
			context: Context,
			cornerRadiusDp: Float,
			fillAlpha: Float = 0.055f,
			strokeAlpha: Float = 0.20f,
			rimAlpha: Float = 0.75f,
			tintColor: Int = Color.WHITE,
		): GlassDrawable {
			val density = context.resources.displayMetrics.density
			return GlassDrawable(
				cornerRadius = cornerRadiusDp * density,
				fillAlpha = fillAlpha,
				strokeAlpha = strokeAlpha,
				rimAlpha = rimAlpha,
				tintColor = tintColor,
			).apply {
				setDensity(density)
			}
		}
	}
}

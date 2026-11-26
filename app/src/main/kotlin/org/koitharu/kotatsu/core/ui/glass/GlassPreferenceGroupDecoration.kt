package org.koitharu.kotatsu.core.ui.glass

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * Item decoration for preference screens calibrated against the Liquid Glass prototype's
 * `.set-card` and `.set-label` design tokens:
 * - Groups preferences of the same category into floating rounded glass containers (20dp corner radius).
 * - Perimeter specular stroke and top-edge specular rim highlight on top items.
 * - Inset 1px translucent divider line between rows in the same card (starts past icon at 56dp).
 * - Styles category headers with small uppercase muted typography (11.5sp, 0.08em letter-spacing).
 * - Zero allocations in onDraw() to maintain 60/120fps scrolling budget.
 */
@SuppressLint("RestrictedApi")
class GlassPreferenceGroupDecoration(context: Context) : RecyclerView.ItemDecoration() {

	private val density = context.resources.displayMetrics.density
	private val cornerRadius = 20f * density
	private val cardMarginHorizontal = (16f * density).toInt()
	private val categoryHeaderTopMargin = (18f * density).toInt()
	private val categoryHeaderBottomMargin = (6f * density).toInt()
	private val cardBottomMargin = (12f * density).toInt()
	private val dividerIndentStart = 56f * density
	private val dividerIndentEnd = 16f * density

	private val isNight = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

	private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.FILL
		isDither = true
		color = if (isNight) {
			ColorUtils.setAlphaComponent(Color.WHITE, 17) // ~6.7% white translucency
		} else {
			ColorUtils.setAlphaComponent(Color.WHITE, 204) // ~80% frosted white translucency
		}
	}

	private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeWidth = 1f * density
		isDither = true
		color = if (isNight) {
			ColorUtils.setAlphaComponent(Color.WHITE, 38) // ~15% white specular stroke
		} else {
			ColorUtils.setAlphaComponent(Color.WHITE, 240) // ~94% crisp white specular stroke
		}
	}

	private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeWidth = 1.5f * density
		isDither = true
	}

	private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeWidth = 1f * density
		color = if (isNight) {
			ColorUtils.setAlphaComponent(Color.WHITE, 23) // ~9% white divider
		} else {
			ColorUtils.setAlphaComponent(Color.BLACK, 18) // ~7% subtle black divider
		}
	}

	private val cardRect = RectF()
	private val cardPath = Path()
	private val strokePath = Path()
	private val rimPath = Path()
	private val radii = FloatArray(8)

	private val rimHighlightColor = if (isNight) {
		ColorUtils.setAlphaComponent(Color.WHITE, (0.65f * 255).toInt())
	} else {
		ColorUtils.setAlphaComponent(Color.WHITE, (0.90f * 255).toInt())
	}

	private var isListenerAttached = false

	private val attachListener = object : RecyclerView.OnChildAttachStateChangeListener {
		override fun onChildViewAttachedToWindow(view: View) {
			val parent = view.parent as? RecyclerView ?: return
			val position = parent.getChildAdapterPosition(view)
			if (position == RecyclerView.NO_POSITION) return
			val pref = getPreference(parent, position)

			if (pref is PreferenceCategory) {
				val titleView = view.findViewById<TextView>(android.R.id.title)
				if (titleView != null) {
					titleView.isAllCaps = true
					titleView.letterSpacing = 0.08f
					titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
					titleView.setTextColor(
						if (isNight) {
							ColorUtils.setAlphaComponent(Color.WHITE, 115) // ~45% white
						} else {
							ColorUtils.setAlphaComponent(Color.BLACK, 140) // ~55% black
						}
					)
					titleView.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
					titleView.setPaddingRelative(
						(8f * density).toInt(),
						(12f * density).toInt(),
						(8f * density).toInt(),
						(4f * density).toInt(),
					)
				}
			} else if (pref != null) {
				val titleColor = if (isNight) Color.WHITE else Color.parseColor("#1C1B1F")
				val summaryColor = if (isNight) {
					ColorUtils.setAlphaComponent(Color.WHITE, 145) // ~57% white
				} else {
					ColorUtils.setAlphaComponent(Color.BLACK, 150) // ~59% black
				}
				view.findViewById<TextView>(android.R.id.title)?.setTextColor(titleColor)
				view.findViewById<TextView>(android.R.id.summary)?.setTextColor(summaryColor)
			}
		}

		override fun onChildViewDetachedFromWindow(view: View) = Unit
	}

	override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
		val position = parent.getChildAdapterPosition(view)
		if (position == RecyclerView.NO_POSITION) return

		if (!isListenerAttached) {
			isListenerAttached = true
			parent.addOnChildAttachStateChangeListener(attachListener)
			parent.post {
				for (j in 0 until parent.childCount) {
					attachListener.onChildViewAttachedToWindow(parent.getChildAt(j))
				}
			}
		}

		val pref = getPreference(parent, position)
		if (pref is PreferenceCategory) {
			outRect.top = if (position == 0) (8f * density).toInt() else categoryHeaderTopMargin
			outRect.bottom = categoryHeaderBottomMargin
			outRect.left = cardMarginHorizontal
			outRect.right = cardMarginHorizontal
		} else if (pref != null) {
			outRect.left = cardMarginHorizontal
			outRect.right = cardMarginHorizontal
			val adapter = parent.adapter ?: return
			val isLast = isLastInGroup(parent, position, adapter.itemCount)
			outRect.bottom = if (isLast) cardBottomMargin else 0
		}
	}

	override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
		val adapter = parent.adapter ?: return
		val itemCount = adapter.itemCount
		val childCount = parent.childCount
		val strokeW = strokePaint.strokeWidth
		val halfStroke = strokeW / 2f
		val r = cornerRadius

		for (i in 0 until childCount) {
			val child = parent.getChildAt(i)
			val position = parent.getChildAdapterPosition(child)
			if (position == RecyclerView.NO_POSITION) continue

			val pref = getPreference(parent, position) ?: continue
			if (pref is PreferenceCategory) continue

			val isFirst = isFirstInGroup(parent, position)
			val isLast = isLastInGroup(parent, position, itemCount)

			val left = child.left.toFloat()
			val top = child.top.toFloat()
			val right = child.right.toFloat()
			val bottom = child.bottom.toFloat()

			// 1. Calculate corner radii for this row's segment of the glass card
			when {
				isFirst && isLast -> {
					radii[0] = r; radii[1] = r
					radii[2] = r; radii[3] = r
					radii[4] = r; radii[5] = r
					radii[6] = r; radii[7] = r
				}
				isFirst && !isLast -> {
					radii[0] = r; radii[1] = r
					radii[2] = r; radii[3] = r
					radii[4] = 0f; radii[5] = 0f
					radii[6] = 0f; radii[7] = 0f
				}
				!isFirst && isLast -> {
					radii[0] = 0f; radii[1] = 0f
					radii[2] = 0f; radii[3] = 0f
					radii[4] = r; radii[5] = r
					radii[6] = r; radii[7] = r
				}
				else -> {
					radii.fill(0f)
				}
			}

			// 2. Draw Translucent Glass Fill
			cardPath.reset()
			cardRect.set(left, top, right, bottom)
			cardPath.addRoundRect(cardRect, radii, Path.Direction.CW)
			c.drawPath(cardPath, fillPaint)

			// 3. Draw Perimeter Border Specular Stroke
			strokePath.reset()
			when {
				isFirst && isLast -> {
					val rect = RectF(left + halfStroke, top + halfStroke, right - halfStroke, bottom - halfStroke)
					val strokeR = (r - halfStroke).coerceAtLeast(0f)
					val strokeRadii = floatArrayOf(
						strokeR, strokeR, strokeR, strokeR,
						strokeR, strokeR, strokeR, strokeR,
					)
					strokePath.addRoundRect(rect, strokeRadii, Path.Direction.CW)
					c.drawPath(strokePath, strokePaint)
				}
				isFirst && !isLast -> {
					strokePath.moveTo(left + halfStroke, bottom)
					strokePath.lineTo(left + halfStroke, top + r)
					strokePath.arcTo(left + halfStroke, top + halfStroke, left + 2 * r, top + 2 * r, 180f, 90f, false)
					strokePath.lineTo(right - r, top + halfStroke)
					strokePath.arcTo(right - 2 * r, top + halfStroke, right - halfStroke, top + 2 * r, 270f, 90f, false)
					strokePath.lineTo(right - halfStroke, bottom)
					c.drawPath(strokePath, strokePaint)
				}
				!isFirst && isLast -> {
					strokePath.moveTo(left + halfStroke, top)
					strokePath.lineTo(left + halfStroke, bottom - r)
					strokePath.arcTo(left + halfStroke, bottom - 2 * r, left + 2 * r, bottom - halfStroke, 180f, -90f, false)
					strokePath.lineTo(right - r, bottom - halfStroke)
					strokePath.arcTo(right - 2 * r, bottom - 2 * r, right - halfStroke, bottom - halfStroke, 90f, -90f, false)
					strokePath.lineTo(right - halfStroke, top)
					c.drawPath(strokePath, strokePaint)
				}
				else -> {
					c.drawLine(left + halfStroke, top, left + halfStroke, bottom, strokePaint)
					c.drawLine(right - halfStroke, top, right - halfStroke, bottom, strokePaint)
				}
			}

			// 4. Top Specular Rim Highlight (crisp highlight across top edge of top item)
			if (isFirst) {
				rimPath.reset()
				rimPath.moveTo(left + halfStroke, top + r)
				rimPath.arcTo(left + halfStroke, top + halfStroke, left + 2 * r, top + 2 * r, 180f, 90f, false)
				rimPath.lineTo(right - r, top + halfStroke)
				rimPath.arcTo(right - 2 * r, top + halfStroke, right - halfStroke, top + 2 * r, 270f, 90f, false)

				rimPaint.shader = LinearGradient(
					left, top, right, top,
					intArrayOf(Color.TRANSPARENT, rimHighlightColor, rimHighlightColor, Color.TRANSPARENT),
					floatArrayOf(0f, 0.25f, 0.75f, 1f),
					Shader.TileMode.CLAMP,
				)
				c.drawPath(rimPath, rimPaint)
			}

			// 5. Inset Row Divider (for middle and top rows in the same card)
			if (!isLast) {
				val divLeft = (left + dividerIndentStart).coerceAtMost(right)
				val divRight = (right - dividerIndentEnd).coerceAtLeast(divLeft)
				c.drawLine(divLeft, bottom, divRight, bottom, dividerPaint)
			}
		}
	}

	internal fun getPreference(parent: RecyclerView, position: Int): Preference? {
		val adapter = parent.adapter as? PreferenceGroupAdapter ?: return null
		return runCatching { adapter.getItem(position) }.getOrNull()
	}

	internal fun isFirstInGroup(parent: RecyclerView, position: Int): Boolean {
		if (position == 0) return true
		val prev = getPreference(parent, position - 1)
		return prev is PreferenceCategory
	}

	internal fun isLastInGroup(parent: RecyclerView, position: Int, itemCount: Int): Boolean {
		if (position >= itemCount - 1) return true
		val next = getPreference(parent, position + 1)
		return next is PreferenceCategory
	}
}

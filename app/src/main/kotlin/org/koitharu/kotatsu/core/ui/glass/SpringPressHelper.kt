package org.koitharu.kotatsu.core.ui.glass

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.os.Build
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * Spring press touch feedback helper calibrated directly against the Liquid Glass prototype's
 * `cubic-bezier(.34, 1.56, .5, 1)` physics curve:
 * - Press down: fast, critically damped (dampingRatio = 1.0f), NO downward overshoot.
 * - Release: bouncy spring back (dampingRatio = 0.60f, stiffness = 220f) with ~10% overshoot.
 * - Accessibility & TalkBack compliant: returns `false` from onTouchListener so it never consumes
 *   touch events, preserves native Material ripples, retains OnLongClickListener, and lets native
 *   performClick handle accessibility actions cleanly.
 */
object SpringPressHelper {

	fun areAnimationsEnabled(view: View): Boolean {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled()) {
			return false
		}
		val scale = runCatching {
			Settings.Global.getFloat(view.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
		}.getOrDefault(1f)
		return scale > 0f
	}

	@SuppressLint("ClickableViewAccessibility")
	fun attach(
		view: View,
		targetScale: Float = 0.94f,
		onClick: (() -> Unit)? = null,
	) {
		if (onClick != null) {
			view.setOnClickListener { onClick() }
		}

		val springX = SpringAnimation(view, DynamicAnimation.SCALE_X)
		val springY = SpringAnimation(view, DynamicAnimation.SCALE_Y)

		view.setOnTouchListener { v, event ->
			val enabled = areAnimationsEnabled(v)

			when (event.actionMasked) {
				MotionEvent.ACTION_DOWN -> {
					if (!enabled) {
						springX.cancel()
						springY.cancel()
						v.scaleX = targetScale
						v.scaleY = targetScale
					} else {
						val force = SpringForce(targetScale).apply {
							dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
							stiffness = 280f
						}
						springX.spring = force
						springY.spring = force
						springX.start()
						springY.start()
					}
				}

				MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
					if (!enabled) {
						springX.cancel()
						springY.cancel()
						v.scaleX = 1f
						v.scaleY = 1f
					} else {
						val force = SpringForce(1f).apply {
							dampingRatio = 0.60f
							stiffness = 220f
						}
						springX.spring = force
						springY.spring = force
						springX.start()
						springY.start()
					}
				}
			}
			// Always return false so the native View.onTouchEvent handles clicks, ripples,
			// long presses, and TalkBack accessibility actions uninterrupted.
			false
		}
	}
}

fun View.addSpringPress(targetScale: Float = 0.94f, onClick: (() -> Unit)? = null) {
	SpringPressHelper.attach(this, targetScale, onClick)
}

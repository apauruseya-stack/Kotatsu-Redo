package org.koitharu.kotatsu.reader

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.annotation.UiThreadTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.reader.domain.TapGridArea
import org.koitharu.kotatsu.reader.ui.tapgrid.TapGridDispatcher

@RunWith(AndroidJUnit4::class)
class ReaderGesturePassthroughDeviceTest {

	private class TestGridTouchListener : TapGridDispatcher.OnGridTouchListener {
		var lastTouchedArea: TapGridArea? = null
		var lastLongTouchedArea: TapGridArea? = null
		var onProcessTouchCallCount = 0

		// Simulates ReaderActivity.onProcessTouch logic
		// Top bar: y in 0..150, Bottom bar: y in 2250..2400
		override fun onProcessTouch(rawX: Int, rawY: Int): Boolean {
			onProcessTouchCallCount++
			if (rawY in 0..150 || rawY in 2250..2400) {
				return false
			}
			return true
		}

		override fun onGridTouch(area: TapGridArea): Boolean {
			lastTouchedArea = area
			return true
		}

		override fun onGridLongTouch(area: TapGridArea) {
			lastLongTouchedArea = area
		}
	}

	private fun createFakeRootView(): View {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		return View(context).apply {
			measure(
				View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY),
			)
			layout(0, 0, 1080, 2400)
		}
	}

	@Test
	@UiThreadTest
	fun testCenterPageTouchPassesToGrid() {
		val fakeRootView = createFakeRootView()
		val listener = TestGridTouchListener()
		val dispatcher = TapGridDispatcher(fakeRootView, listener)

		val downTime = SystemClock.uptimeMillis()
		val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 540f, 1200f, 0)
		val upEvent = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, 540f, 1200f, 0)

		dispatcher.dispatchTouchEvent(downEvent)
		dispatcher.dispatchTouchEvent(upEvent)
		dispatcher.onSingleTapConfirmed(upEvent)

		assertEquals(1, listener.onProcessTouchCallCount)
		assertEquals(TapGridArea.CENTER, listener.lastTouchedArea)

		downEvent.recycle()
		upEvent.recycle()
	}

	@Test
	@UiThreadTest
	fun testEdgeZonesForPageTurningInAllModes() {
		val fakeRootView = createFakeRootView()

		// Left edge zone (e.g. previous/next in standard/reversed modes)
		val leftListener = TestGridTouchListener()
		val leftDispatcher = TapGridDispatcher(fakeRootView, leftListener)
		val t1 = SystemClock.uptimeMillis()
		val leftDown = MotionEvent.obtain(t1, t1, MotionEvent.ACTION_DOWN, 100f, 1200f, 0)
		val leftUp = MotionEvent.obtain(t1, t1 + 50, MotionEvent.ACTION_UP, 100f, 1200f, 0)
		leftDispatcher.dispatchTouchEvent(leftDown)
		leftDispatcher.dispatchTouchEvent(leftUp)
		leftDispatcher.onSingleTapConfirmed(leftUp)
		assertEquals(TapGridArea.CENTER_LEFT, leftListener.lastTouchedArea)
		leftDown.recycle()
		leftUp.recycle()

		// Right edge zone
		val rightListener = TestGridTouchListener()
		val rightDispatcher = TapGridDispatcher(fakeRootView, rightListener)
		val t2 = SystemClock.uptimeMillis()
		val rightDown = MotionEvent.obtain(t2, t2, MotionEvent.ACTION_DOWN, 980f, 1200f, 0)
		val rightUp = MotionEvent.obtain(t2, t2 + 50, MotionEvent.ACTION_UP, 980f, 1200f, 0)
		rightDispatcher.dispatchTouchEvent(rightDown)
		rightDispatcher.dispatchTouchEvent(rightUp)
		rightDispatcher.onSingleTapConfirmed(rightUp)
		assertEquals(TapGridArea.CENTER_RIGHT, rightListener.lastTouchedArea)
		rightDown.recycle()
		rightUp.recycle()
	}

	@Test
	@UiThreadTest
	fun testTopBarAndBottomBarTouchesDoNotInterceptPageGrid() {
		val fakeRootView = createFakeRootView()
		val listener = TestGridTouchListener()
		val dispatcher = TapGridDispatcher(fakeRootView, listener)

		// Touch inside top appbar area (y = 80)
		val downTime = SystemClock.uptimeMillis()
		val topDown = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 540f, 80f, 0)
		val topUp = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, 540f, 80f, 0)

		dispatcher.dispatchTouchEvent(topDown)
		dispatcher.dispatchTouchEvent(topUp)
		dispatcher.onSingleTapConfirmed(topUp)

		assertNull(
			"Top bar touch must be rejected by onProcessTouch and NOT fire grid actions",
			listener.lastTouchedArea,
		)
		topDown.recycle()
		topUp.recycle()

		// Touch inside bottom docked toolbar area (y = 2300)
		val bottomDown = MotionEvent.obtain(downTime + 100, downTime + 100, MotionEvent.ACTION_DOWN, 540f, 2300f, 0)
		val bottomUp = MotionEvent.obtain(downTime + 100, downTime + 150, MotionEvent.ACTION_UP, 540f, 2300f, 0)

		dispatcher.dispatchTouchEvent(bottomDown)
		dispatcher.dispatchTouchEvent(bottomUp)
		dispatcher.onSingleTapConfirmed(bottomUp)

		assertNull(
			"Bottom docked toolbar touch must be rejected by onProcessTouch and NOT fire grid actions",
			listener.lastTouchedArea,
		)
		bottomDown.recycle()
		bottomUp.recycle()
	}

	@Test
	@UiThreadTest
	fun testSwipesStartingOnBarNeverTriggerTapGrid() {
		val fakeRootView = createFakeRootView()
		val listener = TestGridTouchListener()
		val dispatcher = TapGridDispatcher(fakeRootView, listener)

		// A swipe gesture that begins inside the bottom toolbar and drags up into the page area
		val t = SystemClock.uptimeMillis()
		val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 540f, 2320f, 0)
		val move1 = MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_MOVE, 540f, 1800f, 0)
		val move2 = MotionEvent.obtain(t, t + 80, MotionEvent.ACTION_MOVE, 540f, 1200f, 0)
		val up = MotionEvent.obtain(t, t + 120, MotionEvent.ACTION_UP, 540f, 1200f, 0)

		dispatcher.dispatchTouchEvent(down)
		dispatcher.dispatchTouchEvent(move1)
		dispatcher.dispatchTouchEvent(move2)
		dispatcher.dispatchTouchEvent(up)
		dispatcher.onSingleTapConfirmed(up)

		assertNull(
			"Swipe starting on bottom bar must never trigger tap grid actions",
			listener.lastTouchedArea,
		)

		down.recycle()
		move1.recycle()
		move2.recycle()
		up.recycle()
	}

	@Test
	@UiThreadTest
	fun testLongPressInViewportFiresGridLongTouch() {
		val fakeRootView = createFakeRootView()
		val listener = TestGridTouchListener()
		val dispatcher = TapGridDispatcher(fakeRootView, listener)

		val t = SystemClock.uptimeMillis()
		val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 540f, 1200f, 0)
		dispatcher.dispatchTouchEvent(down)
		dispatcher.onLongPress(down)

		assertEquals(TapGridArea.CENTER, listener.lastLongTouchedArea)

		down.recycle()
	}
}

package org.koitharu.kotatsu.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.model.TestMangaSource
import org.koitharu.kotatsu.parsers.model.MangaChapter

class ShrinkGuardTest {

	@Test
	fun emptyList_alwaysRejected() {
		val decision = ShrinkGuard.evaluate(
			cachedCount = 50,
			newChapters = emptyList(),
			pendingShrinkCount = null,
			pendingShrinkHash = null,
		)
		assertEquals(ShrinkGuard.ShrinkDecision.RejectEmpty, decision)
	}

	@Test
	fun normalCount_acceptedImmediately() {
		val chapters = generateChapters(1..100)
		val decision = ShrinkGuard.evaluate(
			cachedCount = 100,
			newChapters = chapters,
			pendingShrinkCount = 50,
			pendingShrinkHash = 12345L,
		)
		assertEquals(ShrinkGuard.ShrinkDecision.AcceptNormal, decision)
	}

	@Test
	fun smallListShrink_belowThreshold_acceptedNormally() {
		// Cached has 8 chapters, new has 5 (below MIN_CHAPTERS_FOR_SHRINK_CHECK of 10)
		val chapters = generateChapters(1..5)
		val decision = ShrinkGuard.evaluate(
			cachedCount = 8,
			newChapters = chapters,
			pendingShrinkCount = null,
			pendingShrinkHash = null,
		)
		assertEquals(ShrinkGuard.ShrinkDecision.AcceptNormal, decision)
	}

	@Test
	fun significantShrink_firstCheckReturnsPending_secondCheckConfirms() {
		// 100 cached chapters shrinks to 50 chapters
		val newChapters = generateChapters(1..50)
		val firstDecision = ShrinkGuard.evaluate(
			cachedCount = 100,
			newChapters = newChapters,
			pendingShrinkCount = null,
			pendingShrinkHash = null,
		)
		assertTrue(firstDecision is ShrinkGuard.ShrinkDecision.PendingConfirmation)
		val pending = firstDecision as ShrinkGuard.ShrinkDecision.PendingConfirmation
		assertEquals(50, pending.newCount)

		// 2nd check returns same 50 chapters: confirms shrink
		val secondDecision = ShrinkGuard.evaluate(
			cachedCount = 100,
			newChapters = newChapters,
			pendingShrinkCount = pending.newCount,
			pendingShrinkHash = pending.hash,
		)
		assertEquals(ShrinkGuard.ShrinkDecision.ConfirmedShrink, secondDecision)
	}

	@Test
	fun volumeNumberHash_isOrderIndependent() {
		val listAsc = generateChapters(1..30)
		val listDesc = listAsc.reversed()
		val hashAsc = ShrinkGuard.computeVolumeNumberHash(listAsc)
		val hashDesc = ShrinkGuard.computeVolumeNumberHash(listDesc)

		assertEquals(hashAsc, hashDesc)
	}

	private fun generateChapters(range: IntRange) = range.map { num ->
		MangaChapter(
			id = num.toLong(),
			title = "Chapter $num",
			number = num.toFloat(),
			volume = 0,
			url = "/ch/$num",
			scanlator = null,
			uploadDate = 0L,
			branch = "default",
			source = TestMangaSource,
		)
	}
}

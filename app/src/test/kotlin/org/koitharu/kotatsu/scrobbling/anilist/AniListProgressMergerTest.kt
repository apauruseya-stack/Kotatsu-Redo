package org.koitharu.kotatsu.scrobbling.anilist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.model.MangaHistory
import org.koitharu.kotatsu.core.model.TestMangaSource
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.scrobbling.anilist.domain.AniListProgressMerger
import org.koitharu.kotatsu.scrobbling.anilist.domain.AniListProgressMerger.MergeResult
import java.time.Instant

class AniListProgressMergerTest {

	private fun chapter(
		id: Long,
		number: Float,
		branch: String? = "default",
		title: String? = "Chapter $number",
	) = MangaChapter(
		id = id,
		title = title,
		number = number,
		volume = 0,
		url = "/chapter/$id",
		scanlator = null,
		uploadDate = 0L,
		branch = branch,
		source = TestMangaSource,
	)

	private fun generateChapters(range: IntRange, branch: String? = "default") = range.map { num ->
		chapter(id = num.toLong(), number = num.toFloat(), branch = branch)
	}

	private fun history(
		chapterId: Long,
		page: Int = 0,
		scroll: Int = 0,
		percent: Float = 0f,
	) = MangaHistory(
		createdAt = Instant.now(),
		updatedAt = Instant.now(),
		chapterId = chapterId,
		page = page,
		scroll = scroll,
		percent = percent,
		chaptersCount = 100,
	)

	@Test
	fun remoteAhead_advancesToNextChapterUnstarted() {
		val chapters = generateChapters(1..150)
		// User local progress at Ch. 120 (currently reading Ch. 120, page 1)
		val localHistory = history(chapterId = 120L, page = 1, percent = 0.80f)

		// AniList says user read up to 125 on web
		val result = AniListProgressMerger.merge(
			remoteProgress = 125,
			remoteStatus = "CURRENT",
			currentHistory = localHistory,
			cachedChapters = chapters,
		)

		assertTrue(result is MergeResult.Advance)
		val advance = result as MergeResult.Advance
		// Read up to 125 -> next chapter to read is 126
		assertEquals(126L, advance.targetChapter.id)
		assertEquals(126f, advance.targetChapter.number)
		assertFalse(advance.isClamped)
		assertEquals(120, advance.localProgress)
		assertEquals(125, advance.remoteProgress)
	}

	@Test
	fun remoteAhead_completedStatus_pointsToLastChapterWithPercent1() {
		val chapters = generateChapters(1..143)
		val localHistory = history(chapterId = 100L, page = 1)

		val result = AniListProgressMerger.merge(
			remoteProgress = 143,
			remoteStatus = "COMPLETED",
			currentHistory = localHistory,
			cachedChapters = chapters,
		)

		assertTrue(result is MergeResult.Advance)
		val advance = result as MergeResult.Advance
		assertEquals(143L, advance.targetChapter.id)
		assertEquals(1.0f, advance.percent, 0.001f)
		assertFalse(advance.isClamped)
	}

	@Test
	fun remoteEqual_returnsNoOp() {
		val chapters = generateChapters(1..100)
		val localHistory = history(chapterId = 50L, page = 2)

		val result = AniListProgressMerger.merge(
			remoteProgress = 50,
			remoteStatus = "CURRENT",
			currentHistory = localHistory,
			cachedChapters = chapters,
		)

		assertEquals(MergeResult.NoOp, result)
	}

	@Test
	fun remoteBehind_returnsNoOp_neverRegresses() {
		val chapters = generateChapters(1..100)
		// Local at Ch. 50
		val localHistory = history(chapterId = 50L, page = 5)

		// Remote says 45 (e.g. stale or offline scrobble)
		val result = AniListProgressMerger.merge(
			remoteProgress = 45,
			remoteStatus = "CURRENT",
			currentHistory = localHistory,
			cachedChapters = chapters,
		)

		assertEquals(MergeResult.NoOp, result)
	}

	@Test
	fun remoteFarBeyond_flagsMismatch_neverClamps() {
		val chapters = generateChapters(1..100)
		val localHistory = history(chapterId = 10L, page = 1)

		// Remote says 200 (> 100 * 1.5 = 150) -> suspected wrong title / bad link
		val result = AniListProgressMerger.merge(
			remoteProgress = 200,
			remoteStatus = "CURRENT",
			currentHistory = localHistory,
			cachedChapters = chapters,
		)

		assertTrue(result is MergeResult.FlaggedMismatch)
		val flagged = result as MergeResult.FlaggedMismatch
		assertEquals(200, flagged.remoteProgress)
		assertEquals(100f, flagged.latestChapterNumber)
	}

	@Test
	fun remoteSlightlyAhead_clampsToLatestAvailableChapter() {
		val chapters = generateChapters(1..100)
		val localHistory = history(chapterId = 95L, page = 1)

		// Remote says 103 (<= 100 * 1.5 = 150) -> source is just a few chapters behind
		val result = AniListProgressMerger.merge(
			remoteProgress = 103,
			remoteStatus = "CURRENT",
			currentHistory = localHistory,
			cachedChapters = chapters,
		)

		assertTrue(result is MergeResult.Advance)
		val advance = result as MergeResult.Advance
		assertTrue(advance.isClamped)
		assertEquals(100L, advance.targetChapter.id)
		assertEquals(1.0f, advance.percent, 0.001f)
	}

	@Test
	fun clampedTitleAdvances_whenSourceCatchesUp() {
		val chaptersV1 = generateChapters(1..100)
		val localHistory = history(chapterId = 95L, page = 1)

		// First sync: remote is 103, clamped to 100
		val result1 = AniListProgressMerger.merge(
			remoteProgress = 103,
			remoteStatus = "CURRENT",
			currentHistory = localHistory,
			cachedChapters = chaptersV1,
		)
		assertTrue((result1 as MergeResult.Advance).isClamped)

		// Local history updated to the clamped position (Ch. 100, percent 1.0f -> localProgress = 100)
		val clampedHistory = history(chapterId = 100L, page = 0, scroll = 0, percent = 1.0f)

		// Source catches up! Now has 110 chapters:
		val chaptersV2 = generateChapters(1..110)

		// Second sync: re-evaluates remote 103 against updated chapter list
		val result2 = AniListProgressMerger.merge(
			remoteProgress = 103,
			remoteStatus = "CURRENT",
			currentHistory = clampedHistory,
			cachedChapters = chaptersV2,
		)

		assertTrue(result2 is MergeResult.Advance)
		val advance2 = result2 as MergeResult.Advance
		assertFalse(advance2.isClamped)
		// Remote 103 -> next chapter to read is 104!
		assertEquals(104L, advance2.targetChapter.id)
	}

	@Test
	fun noChaptersCached_returnsNeedChapters() {
		val result = AniListProgressMerger.merge(
			remoteProgress = 50,
			remoteStatus = "CURRENT",
			currentHistory = null,
			cachedChapters = emptyList(),
		)

		assertEquals(MergeResult.NeedChapters, result)
	}

	@Test
	fun progressZero_returnsNoOp() {
		val chapters = generateChapters(1..100)
		val result = AniListProgressMerger.merge(
			remoteProgress = 0,
			remoteStatus = "PLANNING",
			currentHistory = null,
			cachedChapters = chapters,
		)

		assertEquals(MergeResult.NoOp, result)
	}

	@Test
	fun decimalChapters_handledCorrectly() {
		val chapters = listOf(
			chapter(1L, 10f),
			chapter(2L, 10.5f),
			chapter(3L, 11f),
			chapter(4L, 12f),
		)
		val localHistory = history(chapterId = 1L, page = 1) // read Ch. 10

		// Remote read up to 10 -> local is 10 -> NoOp
		val resultSame = AniListProgressMerger.merge(10, "CURRENT", localHistory, chapters)
		assertEquals(MergeResult.NoOp, resultSame)

		// Remote read up to 11
		val result11 = AniListProgressMerger.merge(11, "CURRENT", localHistory, chapters)
		assertTrue(result11 is MergeResult.Advance)
		// Next after 11 is Ch. 12
		assertEquals(4L, (result11 as MergeResult.Advance).targetChapter.id)
	}

	@Test
	fun roundTripPingPongPrevention_readLocally_scrobbled_pulled_noChange() {
		val chapters = generateChapters(1..150)

		// 1. User reads Ch. 120 in Kotatsu (page 1)
		val readHistory = history(chapterId = 120L, page = 1, percent = 0.80f)

		// Kotatsu scrobbler convention pushes: chapter.number.toInt() = 120
		val scrobbledProgress = 120

		// 2. Later, progress sync pulls AniList state where progress = 120
		val pullResult = AniListProgressMerger.merge(
			remoteProgress = scrobbledProgress,
			remoteStatus = "CURRENT",
			currentHistory = readHistory,
			cachedChapters = chapters,
		)

		// Must be NoOp! No echo loop, no bounce, no ping-pong!
		assertEquals(MergeResult.NoOp, pullResult)
	}

	@Test
	fun openAtPage0_scrobblesChapter_pullProducesNoJump() {
		val chapters = generateChapters(1..150)

		// 1. User opens Ch. 120 (page 0, scroll 0)
		val openedHistory = history(chapterId = 120L, page = 0, scroll = 0, percent = 0f)

		// Kotatsu scrobbler scrobbles immediately on open: chapter.number.toInt() = 120
		val scrobbledProgress = 120

		// Local progress MUST evaluate to 120 (matching what was scrobbled on open!)
		val localProgress = AniListProgressMerger.calculateLocalProgress(openedHistory, chapters)
		assertEquals(120, localProgress)

		// 2. Next pull from AniList sees remoteProgress = 120
		val pullResult = AniListProgressMerger.merge(
			remoteProgress = scrobbledProgress,
			remoteStatus = "CURRENT",
			currentHistory = openedHistory,
			cachedChapters = chapters,
		)

		// Must be NoOp: stays at Ch. 120 page 0! No jumping to Ch. 121!
		assertEquals(MergeResult.NoOp, pullResult)
	}

	@Test
	fun thirtyChapterBinge_appliesWithSourceLatestAsLimit() {
		// Source has 100 chapters
		val chapters = generateChapters(1..100)
		// Local history was at Ch. 10
		val localHistory = history(chapterId = 10L, page = 1)

		// User binges 30 chapters on another site: AniList remote progress is now 40
		val result = AniListProgressMerger.merge(
			remoteProgress = 40,
			remoteStatus = "CURRENT",
			currentHistory = localHistory,
			cachedChapters = chapters,
		)

		// 30 chapter binge is completely legitimate and well within source limits (40 <= 100)
		assertTrue(result is MergeResult.Advance)
		val advance = result as MergeResult.Advance
		assertFalse(advance.isClamped)
		// Next chapter to read is Ch. 41
		assertEquals(41L, advance.targetChapter.id)
		assertEquals(40, advance.remoteProgress)
	}

	@Test
	fun flaggedMismatch_canBeForceAppliedAnyway() {
		// Source has 40 chapters
		val chapters = generateChapters(1..40)
		val localHistory = history(chapterId = 10L, page = 1)

		// Remote says 100 (> 40 * 1.5 = 60). Normal merge flags it as mismatch
		val normalResult = AniListProgressMerger.merge(
			remoteProgress = 100,
			remoteStatus = "CURRENT",
			currentHistory = localHistory,
			cachedChapters = chapters,
			allowMismatch = false,
		)
		assertTrue(normalResult is MergeResult.FlaggedMismatch)

		// User clicks "Apply anyway" in sync log dialog (allowMismatch = true)
		val forcedResult = AniListProgressMerger.merge(
			remoteProgress = 100,
			remoteStatus = "CURRENT",
			currentHistory = localHistory,
			cachedChapters = chapters,
			allowMismatch = true,
		)
		assertTrue(forcedResult is MergeResult.Advance)
		val advance = forcedResult as MergeResult.Advance
		assertTrue(advance.isClamped)
		assertEquals(40L, advance.targetChapter.id)
	}

	@Test
	fun latestChapterFractional_pointFive_marksAsReadAndAdvancesToLatest() {
		// Series has Ch. 1..140, Ch. 141, and Ch. 141.5 (e.g. final extra/side story)
		val chapters = listOf(
			chapter(140L, 140f),
			chapter(141L, 141f),
			chapter(142L, 141.5f, title = "Ch. 141.5 - Side Story"),
		)
		val localHistory = history(chapterId = 140L, page = 1) // read up to 140

		// AniList progress is integer 141 (AniList user read up to 141)
		val result = AniListProgressMerger.merge(
			remoteProgress = 141,
			remoteStatus = "CURRENT",
			currentHistory = localHistory,
			cachedChapters = chapters,
		)

		// When the latest chapter is 141.5, reaching 141 must complete and mark the series up to the latest chapter
		assertTrue(result is MergeResult.Advance)
		val advance = result as MergeResult.Advance
		assertEquals(142L, advance.targetChapter.id)
		assertEquals(141.5f, advance.targetChapter.number)
		assertEquals(1.0f, advance.percent, 0.001f)
		assertFalse(advance.isClamped)
	}
}

package org.koitharu.kotatsu.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.model.MangaHistory
import org.koitharu.kotatsu.core.model.TestMangaSource
import org.koitharu.kotatsu.parsers.model.MangaChapter
import java.time.Instant

class ChapterCounterTest {

	@Test
	fun currentChapterNotStarted_countsCurrentAndSubsequent() {
		// Import from AniList pointing to Ch. 81 (page 0, scroll 0) of 105 chapters.
		// Whole-manga percent = 0.77f
		val chapters = generateChapters(1..105)
		val history = history(chapterId = 81L, page = 0, scroll = 0, percent = 0.77f)

		val result = ChapterCounter.computeNewChapters(chapters, history)

		assertEquals(25, result.count)
		assertEquals(81f, result.firstNewChapter?.number)
		assertEquals(105f, result.latestChapter?.number)
		assertEquals("Ch. 81–105", result.rangeText)
	}

	@Test
	fun currentChapterPartiallyRead_countsOnlySubsequent() {
		// User opened Ch. 81 and is on page 5 with whole-manga percent = 0.77f
		val chapters = generateChapters(1..105)
		val history = history(chapterId = 81L, page = 5, scroll = 0, percent = 0.77f)

		val result = ChapterCounter.computeNewChapters(chapters, history)

		assertEquals(24, result.count)
		assertEquals(82f, result.firstNewChapter?.number)
		assertEquals(105f, result.latestChapter?.number)
		assertEquals("Ch. 82–105", result.rangeText)
	}

	@Test
	fun caughtUpOnLatestChapter_returnsZero() {
		val chapters = generateChapters(1..105)
		val history = history(chapterId = 105L, page = 1, scroll = 0, percent = 1.0f)

		val result = ChapterCounter.computeNewChapters(chapters, history)

		assertEquals(0, result.count)
		assertNull(result.firstNewChapter)
		assertNull(result.latestChapter)
		assertNull(result.rangeText)
		assertTrue(result.chaptersAfterPosition.isEmpty())
	}

	@Test
	fun volumeReset_treatsSameNumberInDifferentVolumesAsDistinct() {
		val chapters = listOf(
			chapter(1L, 1f, volume = 1),
			chapter(2L, 2f, volume = 1),
			chapter(3L, 1f, volume = 2), // Vol 2 Ch 1
			chapter(4L, 2f, volume = 2), // Vol 2 Ch 2
		)
		val history = history(chapterId = 1L, page = 1) // Read Vol 1 Ch 1

		val result = ChapterCounter.computeNewChapters(chapters, history)

		assertEquals(3, result.count)
		val expectedIds = listOf(2L, 3L, 4L)
		assertEquals(expectedIds, result.chaptersAfterPosition.map { it.id })
	}

	@Test
	fun mixedGroups_oneWithVolumeOneWithout_mergesAsSingleChapter() {
		// Aggregator scenario: Group A sets Volume 3 for Ch. 121, Group B sets Volume 0 for Ch. 121
		val chapters = listOf(
			chapter(120L, 120f, volume = 3),
			chapter(1211L, 121f, volume = 3, scanlator = "Group A"),
			chapter(1212L, 121f, volume = 0, scanlator = "Group B"),
			chapter(122L, 122f, volume = 3),
		)
		val history = history(chapterId = 120L, page = 1)

		val result = ChapterCounter.computeNewChapters(chapters, history)

		assertEquals(2, result.count)
		assertEquals(121f, result.firstNewChapter?.number)
		assertEquals(122f, result.latestChapter?.number)
		assertEquals("Ch. 121–122", result.rangeText)
	}

	@Test
	fun duplicateGroups_dedupesByVolumeAndNumber() {
		val chapters = listOf(
			chapter(120L, 120f),
			chapter(1211L, 121f, scanlator = "Group A"),
			chapter(1212L, 121f, scanlator = "Group B"),
			chapter(122L, 122f, scanlator = "Group A"),
		)
		val history = history(chapterId = 120L, page = 1)

		val result = ChapterCounter.computeNewChapters(chapters, history)

		assertEquals(2, result.count)
		assertEquals(121f, result.firstNewChapter?.number)
		assertEquals(122f, result.latestChapter?.number)
		assertEquals("Ch. 121–122", result.rangeText)
	}

	@Test
	fun conservativeDescendingCheck_doesNotReverseListWithExtrasAtEnd() {
		// 20 ascending chapters plus two extra chapters at the end
		val list = generateChapters(1..20) + listOf(
			chapter(201L, 20.1f),
			chapter(202L, 20.2f),
		)
		val history = history(chapterId = 10L, page = 1)

		val result = ChapterCounter.computeNewChapters(list, history)

		// Must not be reversed: chapters after Ch. 10 should be Ch. 11..20.2 (12 chapters)
		assertEquals(12, result.count)
		assertEquals(11f, result.firstNewChapter?.number)
		assertEquals(20.2f, result.latestChapter?.number)
	}

	@Test
	fun conservativeDescendingCheck_reversesTrueDescendingList() {
		// Parser returns 20 descending chapters: Ch. 20 down to Ch. 1
		val list = generateChapters(1..20).reversed()
		val history = history(chapterId = 10L, page = 1)

		val result = ChapterCounter.computeNewChapters(list, history)

		// Reversed to reading order: chapters after Ch. 10 should be Ch. 11..20 (10 chapters)
		assertEquals(10, result.count)
		assertEquals(11f, result.firstNewChapter?.number)
		assertEquals(20f, result.latestChapter?.number)
	}

	@Test
	fun nullSnapshotAfterUpgradeOrRestore_createsBaselineToPersist() {
		// Existing user with history at Ch. 81 of 105 chapters, but no anchor row yet (null snapshot)
		val chapters = generateChapters(1..105)
		val history = history(chapterId = 81L, page = 0, percent = 0.77f)

		val result = ChapterCounter.computeNewChapters(chapters, history, anchor = null)

		// Does not flood the user: baselineToPersist is generated for caller to save
		assertNotNull(result.baselineToPersist)
		assertEquals(105f, result.baselineToPersist?.latestNumberAtLastRead)
		assertEquals(81L, result.baselineToPersist?.lastChapterId)
	}

	@Test
	fun rereadOldChapter_snapshotDoesNotRegress() {
		// User previously read up to Ch. 100 when latest was Ch. 110.
		// Today, user rereads Ch. 10.
		val chapters = generateChapters(1..110)
		val rereadHistory = history(chapterId = 10L, page = 5)
		// Anchor preserves the forward progress snapshot (latestNumberAtLastRead = 110f)
		val anchor = TrackingAnchor(
			lastReadNumber = 100f,
			latestNumberAtLastRead = 110f,
		)

		val result = ChapterCounter.computeNewChapters(chapters, rereadHistory, anchor)

		// Rereading Ch. 10 does not cause new releases to appear if no new chapters were published
		assertEquals(100, result.count) // Unread chapters after Ch. 10: 11..110 = 100
		assertFalse(result.hasNewReleases) // Still 0 new releases beyond Ch. 110
		assertEquals(0, result.newReleasesCount)
	}

	@Test
	fun sideStoryNumbering_snapshotLocatedByListPosition() {
		// Sequence: Ch. 1, Ch. 2, Ch. 2.5 (side story published later and inserted at end of list)
		val chapters = listOf(
			chapter(1L, 1f),
			chapter(2L, 2f),
			chapter(25L, 2.5f, title = "Side story"),
		)
		val history = history(chapterId = 1L, page = 1)
		// Snapshot was taken when only Ch. 1 and Ch. 2 existed
		val anchor = TrackingAnchor(
			lastReadNumber = 1f,
			latestNumberAtLastRead = 2f,
		)

		val result = ChapterCounter.computeNewChapters(chapters, history, anchor)

		// Located in list order: Ch. 2.5 is positioned after Ch. 2 in list order
		assertEquals(2, result.count) // Ch. 2 and Ch. 2.5 unread
		assertTrue(result.hasNewReleases)
		assertEquals(1, result.newReleasesCount) // Ch. 2.5 is a new release
		assertEquals(25L, result.latestChapter?.id)
	}

	@Test
	fun dismissUntilNextChapter_hidesTitleThenReappearsWhenNewChapterDrops() {
		// User at Ch. 80. Latest was Ch. 105.
		val chapters = generateChapters(1..105)
		val history = history(chapterId = 80L, page = 1)
		// "Dismiss until next chapter" sets snapshot to the current latest (Ch. 105)
		val dismissedAnchor = TrackingAnchor(
			lastReadNumber = 80f,
			latestNumberAtLastRead = 105f,
		)

		val resultBefore = ChapterCounter.computeNewChapters(chapters, history, dismissedAnchor)
		// Total unread count remains honest (25), but hasNewReleases is false (hidden from New Rail)
		assertEquals(25, resultBefore.count)
		assertFalse(resultBefore.hasNewReleases)
		assertEquals(0, resultBefore.newReleasesCount)

		// Later, Ch. 106 drops
		val chaptersWith106 = chapters + chapter(106L, 106f)
		val resultAfter = ChapterCounter.computeNewChapters(chaptersWith106, history, dismissedAnchor)

		// Title reappears on New Rail with 1 new release
		assertEquals(26, resultAfter.count)
		assertTrue(resultAfter.hasNewReleases)
		assertEquals(1, resultAfter.newReleasesCount)
		assertEquals("Ch. 81–106", resultAfter.rangeText)
	}

	@Test
	fun backlogVsNewReleases_distinguishesBacklogFromNewDrops() {
		val chapters = generateChapters(1..105)
		val history = history(chapterId = 81L, page = 0, percent = 0.77f)
		val anchorWithSnapshot = TrackingAnchor(
			latestVolumeAtLastRead = 0,
			latestNumberAtLastRead = 105f,
		)

		val resultBefore = ChapterCounter.computeNewChapters(chapters, history, anchorWithSnapshot)
		assertEquals(25, resultBefore.count)
		assertFalse(resultBefore.hasNewReleases)
		assertEquals(0, resultBefore.newReleasesCount)

		val chaptersWithNew = chapters + chapter(106L, 106f) + chapter(107L, 107f)
		val resultAfter = ChapterCounter.computeNewChapters(chaptersWithNew, history, anchorWithSnapshot)
		assertEquals(27, resultAfter.count)
		assertTrue(resultAfter.hasNewReleases)
		assertEquals(2, resultAfter.newReleasesCount)
		assertEquals("Ch. 81–107", resultAfter.rangeText)
	}

	@Test
	fun unreadTitleWithoutBaseline_silentlyBaselinesAtLatest() {
		val chapters = generateChapters(1..50)

		val result = ChapterCounter.computeNewChapters(chapters, history = null, anchor = null)

		assertEquals(0, result.count)
		assertNull(result.latestChapter)
		assertNotNull(result.baselineToPersist)
		assertEquals(50f, result.baselineToPersist?.baselineNumber)
		assertEquals(50L, result.baselineToPersist?.lastChapterId)
	}

	@Test
	fun decimalsAndSpecials_countedCorrectly() {
		val chapters = listOf(
			chapter(119L, 119f),
			chapter(1195L, 119.5f),
			chapter(120L, 120f),
		)
		val history = history(chapterId = 119L, page = 1)

		val result = ChapterCounter.computeNewChapters(chapters, history)

		assertEquals(2, result.count)
		assertEquals(119.5f, result.firstNewChapter?.number)
		assertEquals(120f, result.latestChapter?.number)
		assertEquals("Ch. 119.5–120", result.rangeText)
	}

	@Test
	fun branchFallback_usesHistoryBranchWhenAvailable() {
		val chapters = listOf(
			chapter(1L, 1f, branch = "en"),
			chapter(2L, 2f, branch = "en"),
			chapter(3L, 3f, branch = "en"),
			chapter(101L, 1f, branch = "ru"),
			chapter(102L, 2f, branch = "ru"),
			chapter(103L, 3f, branch = "ru"),
			chapter(104L, 4f, branch = "ru"),
		)
		val history = history(chapterId = 2L, page = 1)

		val result = ChapterCounter.computeNewChapters(chapters, history)

		assertEquals(1, result.count)
		assertEquals(3L, result.latestChapter?.id)
		assertEquals("Ch. 3", result.rangeText)
	}

	@Test
	fun branchFallback_fallsBackToPreferredBranch() {
		val chapters = listOf(
			chapter(1L, 1f, branch = "en"),
			chapter(2L, 2f, branch = "en"),
			chapter(101L, 1f, branch = "ru"),
			chapter(102L, 2f, branch = "ru"),
		)
		val history = history(chapterId = 999L, page = 1)
		val anchor = TrackingAnchor(lastReadNumber = 1f)

		val result = ChapterCounter.computeNewChapters(chapters, history, anchor, preferredBranch = "ru")

		assertEquals(1, result.count)
		assertEquals(102L, result.latestChapter?.id)
		assertEquals("ru", result.latestChapter?.branch)
	}

	@Test
	fun mixedNumberedAndUnnumberedChapters() {
		val chapters = listOf(
			chapter(1L, 0f, title = "Prologue", url = "/prologue"),
			chapter(2L, 1f, url = "/ch1"),
			chapter(3L, 0f, title = "Notice / Hiatus", url = "/notice"),
			chapter(4L, 2f, url = "/ch2"),
		)
		val history = history(chapterId = 2L, page = 1)

		val result = ChapterCounter.computeNewChapters(chapters, history)

		assertEquals(2, result.count)
		assertEquals(listOf(3L, 4L), result.chaptersAfterPosition.map { it.id })
	}

	@Test
	fun idChurn_matchesByStoredLastReadNumber() {
		val chapters = listOf(
			chapter(998L, 9f),
			chapter(999L, 10f),
			chapter(1000L, 11f),
			chapter(1001L, 12f),
		)
		val history = history(chapterId = 100L, page = 1)
		val anchor = TrackingAnchor(lastReadNumber = 10f)

		val result = ChapterCounter.computeNewChapters(chapters, history, anchor)

		assertEquals(2, result.count)
		assertEquals(listOf(1000L, 1001L), result.chaptersAfterPosition.map { it.id })
		assertEquals("Ch. 11–12", result.rangeText)
	}

	@Test
	fun idChurn_withNoStoredNumber_fallsBackWithoutCrashing() {
		val chapters = listOf(
			chapter(998L, 9f),
			chapter(999L, 10f),
			chapter(1000L, 11f),
			chapter(1001L, 12f),
		)
		val history = history(chapterId = 9999L, page = 1, percent = 0.5f)

		val result = ChapterCounter.computeNewChapters(chapters, history, null)

		assertNotNull(result)
		assertEquals(1, result.count)
		assertEquals(1001L, result.latestChapter?.id)
	}

	@Test
	fun unusualListOrder_respectsListPositionNotNumericalMagnitude() {
		val chapters = listOf(
			chapter(1L, 1f),
			chapter(2L, 2f),
			chapter(25L, 2.5f, title = "Flashback"),
			chapter(3L, 3f),
		)
		val history = history(chapterId = 2L, page = 1)

		val result = ChapterCounter.computeNewChapters(chapters, history)

		assertEquals(2, result.count)
		assertEquals(listOf(25L, 3L), result.chaptersAfterPosition.map { it.id })
	}

	@Test
	fun sourceLagsBehindReadingPosition_neverNegative() {
		val chapters = generateChapters(1..120)
		val history = history(chapterId = 9999L, page = 1)
		val anchor = TrackingAnchor(lastReadNumber = 125f)

		val result = ChapterCounter.computeNewChapters(chapters, history, anchor)

		assertEquals(0, result.count)
		assertNull(result.latestChapter)
	}

	@Test
	fun unreadTitleWithBaseline_countsOnlyChaptersAfterBaseline() {
		val chapters = generateChapters(1..53)
		val anchor = TrackingAnchor(baselineNumber = 50f)

		val result = ChapterCounter.computeNewChapters(chapters, history = null, anchor = anchor)

		assertEquals(3, result.count)
		assertEquals(51f, result.firstNewChapter?.number)
		assertEquals(53f, result.latestChapter?.number)
		assertEquals("Ch. 51–53", result.rangeText)
	}

	@Test
	fun comickSource_storedPositionAtSecondToLast_detectsExactlyOneNewChapter() {
		// Comick pattern: slug-based URLs, multiple scanlation groups, decimal chapters (Ch. 99, 99.5, 100)
		val chapters = listOf(
			chapter(1001L, 98f, branch = "en", scanlator = "Flame Comics", url = "/comic/solo-leveling/ch-98"),
			chapter(1002L, 99f, branch = "en", scanlator = "Flame Comics", url = "/comic/solo-leveling/ch-99"),
			chapter(1003L, 99.5f, branch = "en", scanlator = "Asura Scans", url = "/comic/solo-leveling/ch-99-5"),
			chapter(1004L, 100f, branch = "en", scanlator = "Flame Comics", url = "/comic/solo-leveling/ch-100"),
		)
		// Stored position at second-to-last chapter (Ch. 99.5)
		val history = history(chapterId = 1003L, page = 1, percent = 0.99f)
		val anchor = TrackingAnchor(
			lastReadNumber = 99.5f,
			latestNumberAtLastRead = 99.5f,
			branch = "en",
		)

		val result = ChapterCounter.computeNewChapters(chapters, history, anchor, preferredBranch = "en")

		assertEquals(1, result.count)
		assertEquals(1, result.newReleasesCount)
		assertTrue(result.hasNewReleases)
		assertEquals(100f, result.firstNewChapter?.number)
		assertEquals(100f, result.latestChapter?.number)
		assertEquals("Ch. 100", result.rangeText)
	}

	@Test
	fun comixSource_storedPositionAtSecondToLast_detectsExactlyOneNewChapter() {
		// Comix pattern: volume-grouped chapters with volume numbering (Vol 10 Ch 1, Vol 10 Ch 2)
		val chapters = listOf(
			chapter(2001L, 1f, volume = 9, branch = "default", url = "/read/v9/c1"),
			chapter(2002L, 2f, volume = 9, branch = "default", url = "/read/v9/c2"),
			chapter(2003L, 1f, volume = 10, branch = "default", url = "/read/v10/c1"),
			chapter(2004L, 2f, volume = 10, branch = "default", url = "/read/v10/c2"),
		)
		// Stored position at second-to-last chapter (Vol 10 Ch 1)
		val history = history(chapterId = 2003L, page = 1, percent = 0.95f)
		val anchor = TrackingAnchor(
			lastReadVolume = 10,
			lastReadNumber = 1f,
			latestVolumeAtLastRead = 10,
			latestNumberAtLastRead = 1f,
		)

		val result = ChapterCounter.computeNewChapters(chapters, history, anchor)

		assertEquals(1, result.count)
		assertEquals(1, result.newReleasesCount)
		assertTrue(result.hasNewReleases)
		assertEquals(10, result.latestChapter?.volume)
		assertEquals(2f, result.latestChapter?.number)
		assertEquals("Ch. 2", result.rangeText)
	}

	@Test
	fun mangaDexSource_storedPositionAtSecondToLast_detectsExactlyOneNewChapter() {
		// MangaDex pattern: language branches ("en", "es"), UUID-mapped chapters
		val chapters = listOf(
			chapter(3001L, 50f, branch = "en", url = "/chapter/uuid-50"),
			chapter(3002L, 51f, branch = "en", url = "/chapter/uuid-51"),
			chapter(3003L, 52f, branch = "en", url = "/chapter/uuid-52"),
			chapter(4001L, 50f, branch = "es", url = "/chapter/uuid-es-50"),
			chapter(4002L, 51f, branch = "es", url = "/chapter/uuid-es-51"),
		)
		// Stored position at second-to-last English chapter (Ch. 51)
		val history = history(chapterId = 3002L, page = 1, percent = 0.98f)
		val anchor = TrackingAnchor(
			lastReadNumber = 51f,
			latestNumberAtLastRead = 51f,
			branch = "en",
		)

		val result = ChapterCounter.computeNewChapters(chapters, history, anchor, preferredBranch = "en")

		assertEquals(1, result.count)
		assertEquals(1, result.newReleasesCount)
		assertTrue(result.hasNewReleases)
		assertEquals(52f, result.latestChapter?.number)
		assertEquals("Ch. 52", result.rangeText)
	}

	@Test
	fun aggregatorMultiScanlator_singleNewChapterWithThreeGroups_showsExactlyOneNewRelease() {
		// When Ch. 105 drops on an aggregator with 3 scanlators (Flame, Asura, Zero):
		// New releases count MUST be 1 (never inflated to +3)
		val chapters = listOf(
			chapter(101L, 104f, scanlator = "Flame Comics", url = "/c104-flame"),
			chapter(102L, 104f, scanlator = "Asura Scans", url = "/c104-asura"),
			chapter(103L, 104f, scanlator = "Zero Scans", url = "/c104-zero"),
			chapter(201L, 105f, scanlator = "Flame Comics", url = "/c105-flame"),
			chapter(202L, 105f, scanlator = "Asura Scans", url = "/c105-asura"),
			chapter(203L, 105f, scanlator = "Zero Scans", url = "/c105-zero"),
		)
		// User read Ch. 104 Flame (page = 1)
		val history = history(chapterId = 101L, page = 1)
		val anchor = TrackingAnchor(
			lastReadNumber = 104f,
			latestNumberAtLastRead = 104f,
		)

		val result = ChapterCounter.computeNewChapters(chapters, history, anchor)

		assertEquals(1, result.count)
		assertEquals(1, result.newReleasesCount)
		assertTrue(result.hasNewReleases)
		assertEquals(105f, result.firstNewChapter?.number)
		assertEquals(105f, result.latestChapter?.number)
		assertEquals("Ch. 105", result.rangeText)
	}

	@Test
	fun aggregatorMultiScanlator_descendingOrderWithDuplicateNumbers_reversesAndCountsOne() {
		// Parser serves descending order with duplicate chapter numbers across scanlators:
		// Ch. 105 (3 groups), Ch. 104 (3 groups), Ch. 103 (2 groups)
		val chapters = listOf(
			chapter(201L, 105f, scanlator = "Flame"),
			chapter(202L, 105f, scanlator = "Asura"),
			chapter(203L, 105f, scanlator = "Zero"),
			chapter(101L, 104f, scanlator = "Flame"),
			chapter(102L, 104f, scanlator = "Asura"),
			chapter(103L, 104f, scanlator = "Zero"),
			chapter(91L, 103f, scanlator = "Flame"),
			chapter(92L, 103f, scanlator = "Asura"),
		)
		// User read Ch. 104 (page = 1)
		val history = history(chapterId = 101L, page = 1)
		val anchor = TrackingAnchor(
			lastReadNumber = 104f,
			latestNumberAtLastRead = 104f,
		)

		val result = ChapterCounter.computeNewChapters(chapters, history, anchor)

		assertEquals(1, result.count)
		assertEquals(1, result.newReleasesCount)
		assertTrue(result.hasNewReleases)
		assertEquals(105f, result.latestChapter?.number)
		assertEquals("Ch. 105", result.rangeText)
	}

	@Test
	fun aggregatorMultiScanlator_numberZeroInModel_extractsFromTitleAndDeduplicates() {
		// Source where number == 0f in model, chapter numbers only appear in titles:
		val chapters = listOf(
			chapter(101L, 0f, title = "Chapter 104 [Flame Scans]", url = "/c104-flame"),
			chapter(102L, 0f, title = "Chapter 104 - Asura Scans", url = "/c104-asura"),
			chapter(201L, 0f, title = "Chapter 105 [Flame Scans]", url = "/c105-flame"),
			chapter(202L, 0f, title = "Chapter 105 [Asura Scans]", url = "/c105-asura"),
			chapter(203L, 0f, title = "Chapter 105 [Zero Scans]", url = "/c105-zero"),
		)
		val history = history(chapterId = 101L, page = 1)
		val anchor = TrackingAnchor(
			lastReadNumber = 104f,
			latestNumberAtLastRead = 104f,
		)

		val result = ChapterCounter.computeNewChapters(chapters, history, anchor)

		assertEquals(1, result.count)
		assertEquals(1, result.newReleasesCount)
		assertTrue(result.hasNewReleases)
		assertEquals("Ch. 105", result.rangeText)
	}

	@Test
	fun aggregatorMultiScanlator_differentVolumeTagsBetweenGroups_deduplicatesSameChapterNumber() {
		// Group A tagged Ch. 105 as volume 1, Group B tagged Ch. 105 as volume 2:
		val chapters = listOf(
			chapter(101L, 104f, volume = 1),
			chapter(201L, 105f, volume = 1, scanlator = "Flame"),
			chapter(202L, 105f, volume = 2, scanlator = "Asura"),
		)
		val history = history(chapterId = 101L, page = 1)
		val anchor = TrackingAnchor(
			lastReadNumber = 104f,
			latestNumberAtLastRead = 104f,
		)

		val result = ChapterCounter.computeNewChapters(chapters, history, anchor)

		assertEquals(1, result.count)
		assertEquals(1, result.newReleasesCount)
		assertTrue(result.hasNewReleases)
		assertEquals("Ch. 105", result.rangeText)
	}

	@Test
	fun aggregatorMultiScanlator_twoNewChaptersDropFromMultipleGroups_showsExactlyPlusTwo() {
		// Both Ch. 105 and Ch. 106 drop, each uploaded by 3 scanlators (6 uploads total):
		val chapters = listOf(
			chapter(101L, 104f, scanlator = "Flame"),
			chapter(102L, 104f, scanlator = "Asura"),
			chapter(201L, 105f, scanlator = "Flame"),
			chapter(202L, 105f, scanlator = "Asura"),
			chapter(203L, 105f, scanlator = "Zero"),
			chapter(301L, 106f, scanlator = "Flame"),
			chapter(302L, 106f, scanlator = "Asura"),
			chapter(303L, 106f, scanlator = "Zero"),
		)
		val history = history(chapterId = 101L, page = 1)
		val anchor = TrackingAnchor(
			lastReadNumber = 104f,
			latestNumberAtLastRead = 104f,
		)

		val result = ChapterCounter.computeNewChapters(chapters, history, anchor)

		assertEquals(2, result.count)
		assertEquals(2, result.newReleasesCount)
		assertTrue(result.hasNewReleases)
		assertEquals("Ch. 105–106", result.rangeText)
	}

	@Test
	fun groupsInBlocks_allGroupAThenGroupB_canonicalizesAndCountsOne() {
		// Group A uploads Ch 101..105 in block 1
		// Group B uploads Ch 101..105 in block 2
		val groupA = (101..105).map { num ->
			chapter(id = num.toLong(), number = num.toFloat(), scanlator = "Flame Comics", url = "/flame/$num")
		}
		val groupB = (101..105).map { num ->
			chapter(id = (num + 1000).toLong(), number = num.toFloat(), scanlator = "Asura Scans", url = "/asura/$num")
		}
		val chapters = groupA + groupB

		// User read Ch 104 from Group A (page 1)
		val history = history(chapterId = 104L, page = 1)
		val anchor = TrackingAnchor(
			lastReadNumber = 104f,
			latestNumberAtLastRead = 104f,
		)

		val result = ChapterCounter.computeNewChapters(chapters, history, anchor)

		// Flattens the blocks: Ch 105 is the ONLY unread chapter (+1)
		assertEquals(1, result.count)
		assertEquals(1, result.newReleasesCount)
		assertTrue(result.hasNewReleases)
		assertEquals(105f, result.latestChapter?.number)
		assertEquals("Ch. 105", result.rangeText)
	}

	@Test
	fun groupsInBlocks_prefersLastReadGroupForRepresentativeChapter() {
		// Group B is in block 1, Group A (Flame) is in block 2
		val groupB = listOf(chapter(101L, 105f, scanlator = "Asura Scans", url = "/asura/105"))
		val groupA = listOf(chapter(102L, 105f, scanlator = "Flame Comics", url = "/flame/105"))
		val chapters = listOf(
			chapter(100L, 104f, scanlator = "Flame Comics", url = "/flame/104"),
		) + groupB + groupA

		// User read Ch 104 from Flame Comics
		val history = history(chapterId = 100L, page = 1)
		val anchor = TrackingAnchor(
			lastReadNumber = 104f,
			latestNumberAtLastRead = 104f,
		)

		val result = ChapterCounter.computeNewChapters(chapters, history, anchor)

		assertEquals(1, result.count)
		assertEquals(1, result.newReleasesCount)
		// Chooses user's last-read group ("Flame Comics") over Asura Scans
		assertEquals("Flame Comics", result.firstNewChapter?.scanlator)
		assertEquals(102L, result.firstNewChapter?.id)
	}

	@Test
	fun numberExtraction_multiLingualTitlePrefixes_mergesAcrossLanguages() {
		// Sources in Spanish, Russian, Korean, Japanese:
		val chapters = listOf(
			chapter(1L, 104f),
			chapter(201L, 0f, title = "Capítulo 105 [TuManga]", url = "/es/105"),
			chapter(202L, 0f, title = "Глава 105 [Remanga]", url = "/ru/105"),
			chapter(203L, 0f, title = "105화 [Naver]", url = "/ko/105"),
			chapter(204L, 0f, title = "第105話 [Comic]", url = "/ja/105"),
		)
		val history = history(chapterId = 1L, page = 1)
		val anchor = TrackingAnchor(
			lastReadNumber = 104f,
			latestNumberAtLastRead = 104f,
		)

		val result = ChapterCounter.computeNewChapters(chapters, history, anchor)

		// All multi-lingual formats parse to 105f and merge to +1
		assertEquals(1, result.count)
		assertEquals(1, result.newReleasesCount)
		assertTrue(result.hasNewReleases)
		assertEquals("Ch. 105", result.rangeText)
	}

	@Test
	fun numberExtraction_strictUrlVsDatabaseId() {
		// URL /chapter/4829301 is a database ID and must NOT be parsed as a chapter number
		val chapters = listOf(
			chapter(1L, 1f, url = "/read/ch-1/"),
			chapter(2L, 2f, url = "/read/ch-2/"),
			chapter(3L, 0f, title = "Prologue", url = "/chapter/4829301"),
		)
		val parsedNum = ChapterCounter.extractChapterNumber(chapters[2], maxReliable = 2f)
		assertEquals(0f, parsedNum, 0.001f) // Rejected!

		// Title "Season 2 Episode 5" extracts 5f, NOT 2f
		val epChapter = chapter(4L, 0f, title = "Season 2 Episode 5", url = "/s2-ep5")
		val epNum = ChapterCounter.extractChapterNumber(epChapter)
		assertEquals(5f, epNum, 0.001f)
	}

	@Test
	fun numberExtraction_nonEnglishUnnumberedDeduplicatesByTitle() {
		// Spanish and Russian unnumbered prologue chapters
		val chapters = listOf(
			chapter(101L, 0f, title = "Prólogo [Team Alpha]", url = "/prologo-a"),
			chapter(102L, 0f, title = "Prólogo (Team Beta)", url = "/prologo-b"),
			chapter(201L, 1f, title = "Capítulo 1", url = "/c1"),
		)
		// User at prologue unstarted (page = 0, scroll = 0)
		val history = history(chapterId = 101L, page = 0, scroll = 0)
		val result = ChapterCounter.computeNewChapters(chapters, history = history)

		// "Prólogo [Team Alpha]" and "Prólogo (Team Beta)" merge into 1 unnumbered chapter!
		assertEquals(2, result.count) // Prologue + Ch 1
	}

	@Test
	fun floatFormatting_105Dot0Vs105_mergesAsSameChapter() {
		val chapters = listOf(
			chapter(1L, 104f),
			chapter(201L, 105.0f, scanlator = "Group A"),
			chapter(202L, 105f, scanlator = "Group B"),
		)
		val history = history(chapterId = 1L, page = 1)
		val anchor = TrackingAnchor(lastReadNumber = 104f, latestNumberAtLastRead = 104f)

		val result = ChapterCounter.computeNewChapters(chapters, history, anchor)

		assertEquals(1, result.count)
		assertEquals(1, result.newReleasesCount)
		assertEquals("Ch. 105", result.rangeText)
	}

	@Test
	fun sameGroup_part1AndPart2_keptDistinct() {
		// Same group uploads Part 1 and Part 2 of Chapter 105
		val chapters = listOf(
			chapter(1L, 104f, scanlator = "Flame"),
			chapter(201L, 105f, title = "Chapter 105 Part 1", scanlator = "Flame"),
			chapter(202L, 105f, title = "Chapter 105 Part 2", scanlator = "Flame"),
		)
		val history = history(chapterId = 1L, page = 1)
		val anchor = TrackingAnchor(lastReadNumber = 104f, latestNumberAtLastRead = 104f)

		val result = ChapterCounter.computeNewChapters(chapters, history, anchor)

		// Both parts are distinct for the same group (+2)
		assertEquals(2, result.count)
		assertEquals(2, result.newReleasesCount)
		assertEquals("Ch. 105", result.rangeText)
	}

	@Test
	fun differentGroups_partsMergeAcrossGroups() {
		// Group A uploads Ch 105 Part 1, Group B uploads Ch 105 Part 1
		val chapters = listOf(
			chapter(1L, 104f),
			chapter(201L, 105f, title = "Chapter 105 Part 1", scanlator = "Flame"),
			chapter(202L, 105f, title = "Chapter 105 Part 1", scanlator = "Asura"),
		)
		val history = history(chapterId = 1L, page = 1)
		val anchor = TrackingAnchor(lastReadNumber = 104f, latestNumberAtLastRead = 104f)

		val result = ChapterCounter.computeNewChapters(chapters, history, anchor)

		// Part 1 across groups merges to 1
		assertEquals(1, result.count)
		assertEquals(1, result.newReleasesCount)
	}

	@Test
	fun sideStory_addedAfterLaterChapters_countsAsNewRelease() {
		// User read Ch 1..20
		val chapters1To20 = generateChapters(1..20)
		// Later, Ch 2.5 (extra/flashback) is published and placed at the end of the list
		val chaptersWithExtra = chapters1To20 + chapter(205L, 2.5f, title = "Chapter 2.5: Special Story")

		val history = history(chapterId = 20L, page = 1)
		val anchor = TrackingAnchor(
			lastReadNumber = 20f,
			latestNumberAtLastRead = 20f,
		)

		val result = ChapterCounter.computeNewChapters(chaptersWithExtra, history, anchor)

		// Key 2.5 is not in readKeys (only keys 1..20 were read). Positioned after snapshot 20.
		assertEquals(1, result.count)
		assertEquals(1, result.newReleasesCount)
		assertTrue(result.hasNewReleases)
		assertEquals(2.5f, result.latestChapter?.number)
	}

	@Test
	fun unchangedListRunTwice_preservesIdenticalResult() {
		val chapters = listOf(
			chapter(101L, 104f, scanlator = "Flame"),
			chapter(201L, 105f, scanlator = "Flame"),
			chapter(202L, 105f, scanlator = "Asura"),
		)
		val history = history(chapterId = 101L, page = 1)
		val anchor = TrackingAnchor(lastReadNumber = 104f, latestNumberAtLastRead = 104f)

		val result1 = ChapterCounter.computeNewChapters(chapters, history, anchor)
		val result2 = ChapterCounter.computeNewChapters(chapters, history, anchor)

		assertEquals(result1.count, result2.count)
		assertEquals(result1.newReleasesCount, result2.newReleasesCount)
		assertEquals(result1.latestChapter?.id, result2.latestChapter?.id)
		assertEquals(result1.rangeText, result2.rangeText)
	}

	@Test
	fun perVolumeVsGlobalNumbering_volumeResetDetection() {
		// Tankōbon source: Vol 1 Ch 1..5, Vol 2 Ch 1..5 (genuine volume reset)
		val perVolumeChapters = listOf(
			chapter(1L, 1f, volume = 1),
			chapter(2L, 5f, volume = 1),
			chapter(3L, 1f, volume = 2), // drops to 1 in vol 2
			chapter(4L, 2f, volume = 2),
		)
		assertTrue(ChapterCounter.detectPerVolumeNumbering(perVolumeChapters))

		// Global-numbering aggregator with mismatched volume tags (104 -> 105 -> 106)
		val globalChapters = listOf(
			chapter(101L, 104f, volume = 1),
			chapter(102L, 105f, volume = 1, scanlator = "Flame"),
			chapter(103L, 105f, volume = 2, scanlator = "Asura"),
			chapter(104L, 106f, volume = 2),
		)
		assertFalse(ChapterCounter.detectPerVolumeNumbering(globalChapters))
	}

	@Test
	fun goldenFixture_affectedAggregatorTitle_producesExactlyPlusOne() {
		// Golden fixture of the real-world issue:
		// Aggregator source serving Chapter 105 from Flame Comics, Asura Scans, and Zero Scans.
		// Some groups tag volume 1, some tag volume 2, some volume 0.
		val chapters = listOf(
			chapter(1001L, 103f, volume = 1, scanlator = "Flame Comics", url = "/solo/c103-flame"),
			chapter(1002L, 103f, volume = 1, scanlator = "Asura Scans", url = "/solo/c103-asura"),
			chapter(1003L, 104f, volume = 1, scanlator = "Flame Comics", url = "/solo/c104-flame"),
			chapter(1004L, 104f, volume = 2, scanlator = "Asura Scans", url = "/solo/c104-asura"),
			chapter(1005L, 104f, volume = 0, scanlator = "Zero Scans", url = "/solo/c104-zero"),
			chapter(2001L, 105f, volume = 1, scanlator = "Flame Comics", url = "/solo/c105-flame"),
			chapter(2002L, 105f, volume = 2, scanlator = "Asura Scans", url = "/solo/c105-asura"),
			chapter(2003L, 105f, volume = 0, scanlator = "Zero Scans", url = "/solo/c105-zero"),
		)
		// User read Ch 104 from Flame Comics (page 1)
		val history = history(chapterId = 1003L, page = 1)
		// Anchor holding snapshot at 104
		val anchor = TrackingAnchor(
			lastChapterId = 1003L,
			lastReadVolume = 1,
			lastReadNumber = 104f,
			latestVolumeAtLastRead = 1,
			latestNumberAtLastRead = 104f,
		)

		val result = ChapterCounter.computeNewChapters(chapters, history, anchor)

		// Expected golden output: STRICTLY +1 (NEVER +2 or +3)!
		assertEquals(1, result.count)
		assertEquals(1, result.newReleasesCount)
		assertTrue(result.hasNewReleases)
		assertEquals(105f, result.firstNewChapter?.number)
		assertEquals("Flame Comics", result.firstNewChapter?.scanlator) // Prefers user's read group
		assertEquals("Ch. 105", result.rangeText)
	}

	private fun generateChapters(range: IntRange, branch: String? = "default") = range.map { num ->
		chapter(
			id = num.toLong(),
			number = num.toFloat(),
			branch = branch,
		)
	}

	private fun chapter(
		id: Long,
		number: Float,
		volume: Int = 0,
		branch: String? = "default",
		scanlator: String? = null,
		title: String? = "Chapter $number",
		url: String = "/chapter/$id",
	) = MangaChapter(
		id = id,
		title = title,
		number = number,
		volume = volume,
		url = url,
		scanlator = scanlator,
		uploadDate = 0L,
		branch = branch,
		source = TestMangaSource,
	)

	private fun history(
		chapterId: Long,
		page: Int = 0,
		scroll: Int = 0,
		percent: Float = 0f,
		chaptersCount: Int = 0,
	) = MangaHistory(
		createdAt = Instant.now(),
		updatedAt = Instant.now(),
		chapterId = chapterId,
		page = page,
		scroll = scroll,
		percent = percent,
		chaptersCount = chaptersCount,
	)
}

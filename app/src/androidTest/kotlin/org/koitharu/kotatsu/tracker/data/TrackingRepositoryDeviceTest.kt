package org.koitharu.kotatsu.tracker.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.ChapterEntity
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.core.model.TestMangaSource
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.tracker.domain.TrackingRepository
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TrackingRepositoryDeviceTest {

	@get:Rule
	var hiltRule = HiltAndroidRule(this)

	@Inject
	lateinit var db: MangaDatabase

	@Inject
	lateinit var trackingRepository: TrackingRepository

	@Inject
	lateinit var historyRepository: HistoryRepository

	@Inject
	lateinit var settings: AppSettings

	private val testMangaId = 99991L
	private val testManga = MangaEntity(
		id = testMangaId,
		title = "Repo Test Manga",
		altTitles = null,
		url = "/manga/99991",
		publicUrl = "https://example.org/manga/99991",
		rating = -1f,
		isNsfw = false,
		contentRating = null,
		coverUrl = "https://example.org/cover/99991.jpg",
		largeCoverUrl = null,
		state = null,
		authors = null,
		source = "TEST",
	)

	@Before
	fun setUp() {
		hiltRule.inject()
		cleanUp()
	}

	@After
	fun tearDown() {
		cleanUp()
	}

	private fun cleanUp() = runBlocking {
		db.getTracksDao().delete(testMangaId)
		db.getTrackingAnchorsDao().delete(testMangaId)
		db.getHistoryDao().delete(testMangaId)
		db.getChaptersDao().deleteAll(testMangaId)
		db.getMangaDao().delete(listOf(testManga))
	}

	private fun generateChapters(range: IntRange) = range.map { num ->
		MangaChapter(
			id = testMangaId * 1000L + num,
			title = "Chapter $num",
			number = num.toFloat(),
			volume = 0,
			url = "/ch/$num",
			scanlator = null,
			uploadDate = 1000L + num,
			branch = "default",
			source = TestMangaSource,
		)
	}

	private suspend fun saveChapters(chapters: List<MangaChapter>) {
		db.getChaptersDao().replaceAll(testMangaId, chapters.mapIndexed { i, ch ->
			ChapterEntity(
				chapterId = ch.id,
				mangaId = testMangaId,
				title = ch.title ?: "",
				number = ch.number,
				volume = ch.volume,
				url = ch.url,
				scanlator = ch.scanlator,
				uploadDate = ch.uploadDate,
				branch = ch.branch,
				source = "TEST",
				index = i,
			)
		})
	}

	@Test
	fun applyTrackingResult_forwardRead_advancesSnapshotAndUnreadCount() = runBlocking {
		db.getMangaDao().upsert(testManga)
		saveChapters(generateChapters(1..100))

		// User starts at Ch. 50 (page 1)
		db.getHistoryDao().upsert(
			HistoryEntity(
				mangaId = testMangaId,
				createdAt = 1000L,
				updatedAt = 1000L,
				chapterId = testMangaId * 1000L + 50,
				page = 1,
				scroll = 0f,
				percent = 0.50f,
				chaptersCount = 100,
				deletedAt = 0L,
			),
		)

		// Initial check
		val result1 = trackingRepository.applyTrackingResult(testMangaId, isForwardRead = true)
		assertNotNull(result1)
		assertEquals(50, result1?.count) // Ch. 51..100 = 50 unread
		assertEquals(0, result1?.newReleasesCount) // Initial read baselines snapshot to 100f

		val anchor1 = db.getTrackingAnchorsDao().find(testMangaId)
		val track1 = db.getTracksDao().find(testMangaId)
		assertEquals(50f, anchor1?.lastReadNumber)
		assertEquals(100f, anchor1?.latestNumberAtLastRead)
		assertEquals(0, anchor1?.newReleasesCount)
		assertEquals(50, track1?.newChapters)

		// Now 10 new chapters arrive from source: Ch. 101..110
		saveChapters(generateChapters(1..110))
		val result2 = trackingRepository.applyTrackingResult(testMangaId)
		assertNotNull(result2)
		assertEquals(60, result2?.count) // Ch. 51..110 = 60 unread
		assertEquals(10, result2?.newReleasesCount) // 10 new releases past 100f!

		val anchor2 = db.getTrackingAnchorsDao().find(testMangaId)
		val track2 = db.getTracksDao().find(testMangaId)
		assertEquals(100f, anchor2?.latestNumberAtLastRead) // snapshot stays 100f until user reads forward
		assertEquals(10, anchor2?.newReleasesCount)
		assertEquals(60, track2?.newChapters)

		// User reads forward to Ch. 70
		db.getHistoryDao().upsert(
			HistoryEntity(
				mangaId = testMangaId,
				createdAt = 2000L,
				updatedAt = 2000L,
				chapterId = testMangaId * 1000L + 70,
				page = 1,
				scroll = 0f,
				percent = 0.63f,
				chaptersCount = 110,
				deletedAt = 0L,
			),
		)
		val result3 = trackingRepository.applyTrackingResult(testMangaId, isForwardRead = true)
		assertNotNull(result3)
		assertEquals(40, result3?.count) // Ch. 71..110 = 40 unread

		val anchor3 = db.getTrackingAnchorsDao().find(testMangaId)
		val track3 = db.getTracksDao().find(testMangaId)
		assertEquals(70f, anchor3?.lastReadNumber)
		assertEquals(110f, anchor3?.latestNumberAtLastRead) // forward read advanced snapshot to 110f!
		assertEquals(0, anchor3?.newReleasesCount) // new releases seen!
		assertEquals(40, track3?.newChapters)
	}

	@Test
	fun applyTrackingResult_backwardReread_doesNotLowerSnapshot() = runBlocking {
		db.getMangaDao().upsert(testManga)
		saveChapters(generateChapters(1..110))

		// User was at Ch. 70 with snapshot at 110f
		db.getHistoryDao().upsert(
			HistoryEntity(
				mangaId = testMangaId,
				createdAt = 1000L,
				updatedAt = 1000L,
				chapterId = testMangaId * 1000L + 70,
				page = 1,
				scroll = 0f,
				percent = 0.63f,
				chaptersCount = 110,
				deletedAt = 0L,
			),
		)
		trackingRepository.applyTrackingResult(testMangaId, isForwardRead = true)

		val anchorBefore = db.getTrackingAnchorsDao().find(testMangaId)
		assertEquals(110f, anchorBefore?.latestNumberAtLastRead)

		// User rereads Ch. 20 (backward reading)
		db.getHistoryDao().upsert(
			HistoryEntity(
				mangaId = testMangaId,
				createdAt = 3000L,
				updatedAt = 3000L,
				chapterId = testMangaId * 1000L + 20,
				page = 1,
				scroll = 0f,
				percent = 0.18f,
				chaptersCount = 110,
				deletedAt = 0L,
			),
		)
		val result = trackingRepository.applyTrackingResult(testMangaId, isForwardRead = true)
		assertNotNull(result)
		assertEquals(90, result?.count) // Ch. 21..110 = 90 unread

		val anchorAfter = db.getTrackingAnchorsDao().find(testMangaId)
		val trackAfter = db.getTracksDao().find(testMangaId)
		assertEquals(20f, anchorAfter?.lastReadNumber)
		assertEquals(110f, anchorAfter?.latestNumberAtLastRead) // snapshot NOT lowered!
		assertEquals(0, anchorAfter?.newReleasesCount)
		assertEquals(90, trackAfter?.newChapters)
	}

	@Test
	fun historyRepositoryDelete_resetsAnchorToNeverReadBaseline() = runBlocking {
		db.getMangaDao().upsert(testManga)
		saveChapters(generateChapters(1..100))

		db.getHistoryDao().upsert(
			HistoryEntity(
				mangaId = testMangaId,
				createdAt = 1000L,
				updatedAt = 1000L,
				chapterId = testMangaId * 1000L + 50,
				page = 1,
				scroll = 0f,
				percent = 0.50f,
				chaptersCount = 100,
				deletedAt = 0L,
			),
		)
		trackingRepository.applyTrackingResult(testMangaId, isForwardRead = true)

		// Delete history via HistoryRepository
		val domainManga = testManga.toManga(emptySet(), null)
		historyRepository.delete(domainManga)

		val anchor = db.getTrackingAnchorsDao().find(testMangaId)
		val track = db.getTracksDao().find(testMangaId)

		assertNotNull(anchor)
		assertNull(anchor?.lastReadNumber) // cleared
		assertEquals(100f, anchor?.baselineNumber) // set to latest chapter
		assertEquals(100f, anchor?.latestNumberAtLastRead)
		assertEquals(0, anchor?.newReleasesCount)
		assertEquals(0, track?.newChapters)
	}

	@Test
	fun aniListImport_setsHonestUnreadAndZeroNewReleases() = runBlocking {
		db.getMangaDao().upsert(testManga)
		saveChapters(generateChapters(1..105))

		// AniList import: read up to Ch. 80, points to Ch. 81 unstarted (page 0, scroll 0)
		db.getHistoryDao().upsert(
			HistoryEntity(
				mangaId = testMangaId,
				createdAt = 5000L,
				updatedAt = 5000L,
				chapterId = testMangaId * 1000L + 81,
				page = 0,
				scroll = 0f,
				percent = 0.77f,
				chaptersCount = 105,
				deletedAt = 0L,
			),
		)

		val result = trackingRepository.applyTrackingResult(testMangaId, isImport = true)
		assertNotNull(result)
		assertEquals(25, result?.count) // Ch. 81..105 = 25 honest unread chapters
		assertEquals(0, result?.newReleasesCount) // 0 new releases (does not flood rail!)

		val anchor = db.getTrackingAnchorsDao().find(testMangaId)
		val track = db.getTracksDao().find(testMangaId)
		assertEquals(105f, anchor?.latestNumberAtLastRead)
		assertEquals(0, anchor?.newReleasesCount)
		assertEquals(25, track?.newChapters)
	}

	@Test
	fun syncTracksToAnchors_populatesAnchorFromExistingTrack() = runBlocking {
		db.getMangaDao().upsert(testManga)
		db.getTracksDao().upsert(
			TrackEntity(
				mangaId = testMangaId,
				lastChapterId = 1000L,
				newChapters = 6,
				lastCheckTime = 88888L,
				lastChapterDate = 77777L,
				lastResult = TrackEntity.RESULT_HAS_UPDATE,
				lastError = null,
			),
		)

		assertNull(db.getTrackingAnchorsDao().find(testMangaId))

		trackingRepository.syncTracksToAnchors()

		val anchor = db.getTrackingAnchorsDao().find(testMangaId)
		assertNotNull(anchor)
		assertEquals(6, anchor?.newReleasesCount)
		assertEquals(88888L, anchor?.lastCheckedAt)
	}

	@Test
	fun syncTracksToAnchors_updatesZeroedAnchorFromExistingTrack() = runBlocking {
		db.getMangaDao().upsert(testManga)
		db.getTrackingAnchorsDao().upsert(
			TrackingAnchorEntity.create(testMangaId, "default").copy(
				newReleasesCount = 0,
			),
		)
		db.getTracksDao().upsert(
			TrackEntity(
				mangaId = testMangaId,
				lastChapterId = 1000L,
				newChapters = 14,
				lastCheckTime = 99999L,
				lastChapterDate = 88888L,
				lastResult = TrackEntity.RESULT_HAS_UPDATE,
				lastError = null,
			),
		)

		assertEquals(0, db.getTrackingAnchorsDao().find(testMangaId)?.newReleasesCount)

		trackingRepository.syncTracksToAnchors()

		val anchor = db.getTrackingAnchorsDao().find(testMangaId)
		assertNotNull(anchor)
		assertEquals(14, anchor?.newReleasesCount)
	}

	@Test
	fun historyRepositoryClear_resetsAllAnchorsToNeverReadBaseline() = runBlocking {
		db.getMangaDao().upsert(testManga)
		saveChapters(generateChapters(1..100))

		db.getHistoryDao().upsert(
			HistoryEntity(
				mangaId = testMangaId,
				createdAt = 1000L,
				updatedAt = 1000L,
				chapterId = testMangaId * 1000L + 50,
				page = 1,
				scroll = 0f,
				percent = 0.50f,
				chaptersCount = 100,
				deletedAt = 0L,
			),
		)
		trackingRepository.applyTrackingResult(testMangaId, isForwardRead = true)

		historyRepository.clear()

		val anchor = db.getTrackingAnchorsDao().find(testMangaId)
		val track = db.getTracksDao().find(testMangaId)

		assertNotNull(anchor)
		assertNull(anchor?.lastReadNumber)
		assertEquals(100f, anchor?.baselineNumber)
		assertEquals(100f, anchor?.latestNumberAtLastRead)
		assertEquals(0, anchor?.newReleasesCount)
		assertEquals(0, track?.newChapters)
	}

	@Test
	fun historyCleared_thenMangaReread_advancesAnchorCorrectly() = runBlocking {
		db.getMangaDao().upsert(testManga)
		saveChapters(generateChapters(1..100))

		// Manga anchor after history was cleared (baseline at 100)
		db.getTrackingAnchorsDao().upsert(
			TrackingAnchorEntity.create(testMangaId, "default").copy(
				baselineNumber = 100f,
				latestNumberAtLastRead = 100f,
				newReleasesCount = 0,
			),
		)

		// User re-reads Ch. 1 (page 1)
		db.getHistoryDao().upsert(
			HistoryEntity(
				mangaId = testMangaId,
				createdAt = 2000L,
				updatedAt = 2000L,
				chapterId = testMangaId * 1000L + 1,
				page = 1,
				scroll = 0f,
				percent = 0.01f,
				chaptersCount = 100,
				deletedAt = 0L,
			),
		)
		val result = trackingRepository.applyTrackingResult(testMangaId, isForwardRead = true)
		assertNotNull(result)
		assertEquals(99, result?.count) // Ch. 2..100 = 99 unread
		assertEquals(0, result?.newReleasesCount) // 0 new releases beyond 100

		val anchor = db.getTrackingAnchorsDao().find(testMangaId)
		val track = db.getTracksDao().find(testMangaId)
		assertEquals(1f, anchor?.lastReadNumber)
		assertEquals(99, track?.newChapters)
		assertEquals(0, anchor?.newReleasesCount)
	}

	@Test
	fun toggleCycle_onOffOn_clearsAndResyncsCorrectly() = runBlocking {
		db.getMangaDao().upsert(testManga)
		db.getTracksDao().upsert(
			TrackEntity(
				mangaId = testMangaId,
				lastChapterId = 1000L,
				newChapters = 7,
				lastCheckTime = 12345L,
				lastChapterDate = 67890L,
				lastResult = TrackEntity.RESULT_HAS_UPDATE,
				lastError = null,
			),
		)

		// Toggle ON
		trackingRepository.onLiquidGlassToggled(true)
		val anchorOn = db.getTrackingAnchorsDao().find(testMangaId)
		assertNotNull(anchorOn)
		assertEquals(7, anchorOn?.newReleasesCount)

		// Toggle OFF
		trackingRepository.onLiquidGlassToggled(false)
		val trackOff = db.getTracksDao().find(testMangaId)
		assertEquals(0, trackOff?.newChapters)

		// Toggle ON again with updated track
		db.getTracksDao().upsert(
			TrackEntity(
				mangaId = testMangaId,
				lastChapterId = 1000L,
				newChapters = 3,
				lastCheckTime = 54321L,
				lastChapterDate = 67890L,
				lastResult = TrackEntity.RESULT_HAS_UPDATE,
				lastError = null,
			),
		)
		trackingRepository.onLiquidGlassToggled(true)
		val anchorOnAgain = db.getTrackingAnchorsDao().find(testMangaId)
		assertNotNull(anchorOnAgain)
		assertEquals(3, anchorOnAgain?.newReleasesCount)
	}

	@Test
	fun multiScanlator_singleNewChapter_updatesBothAnchorAndTrackToExactCount() = runBlocking {
		db.getMangaDao().upsert(testManga)

		// 3 scanlators upload Ch 104, 3 scanlators upload Ch 105
		val chapters = listOf(
			MangaChapter(1001L, "Ch 104", 104f, 1, "/c104-flame", "Flame", 1000L, "default", TestMangaSource),
			MangaChapter(1002L, "Ch 104", 104f, 2, "/c104-asura", "Asura", 1001L, "default", TestMangaSource),
			MangaChapter(1003L, "Ch 104", 104f, 0, "/c104-zero", "Zero", 1002L, "default", TestMangaSource),
			MangaChapter(2001L, "Ch 105", 105f, 1, "/c105-flame", "Flame", 2000L, "default", TestMangaSource),
			MangaChapter(2002L, "Ch 105", 105f, 2, "/c105-asura", "Asura", 2001L, "default", TestMangaSource),
			MangaChapter(2003L, "Ch 105", 105f, 0, "/c105-zero", "Zero", 2002L, "default", TestMangaSource),
		)
		saveChapters(chapters)

		// User read Ch 104 from Flame (page 1)
		db.getHistoryDao().upsert(
			HistoryEntity(
				mangaId = testMangaId,
				createdAt = 1000L,
				updatedAt = 1000L,
				chapterId = 1001L,
				page = 1,
				scroll = 0f,
				percent = 0.99f,
				chaptersCount = 6,
				deletedAt = 0L,
			),
		)

		// Previous snapshot was at 104
		db.getTrackingAnchorsDao().upsert(
			TrackingAnchorEntity(
				mangaId = testMangaId,
				lastReadVolume = 1,
				lastReadNumber = 104f,
				latestVolumeAtLastRead = 1,
				latestNumberAtLastRead = 104f,
				baselineVolume = null,
				baselineNumber = null,
				branch = "default",
				newReleasesCount = 0,
				newDetectedAt = 0L,
				lastCheckedAt = 1000L,
				status = TrackingAnchorEntity.STATUS_OK,
				failCount = 0,
				pendingShrinkCount = null,
				pendingShrinkHash = null,
			),
		)

		// Check/apply tracking result
		val result = trackingRepository.applyTrackingResult(testMangaId)

		assertNotNull(result)
		assertEquals(1, result?.count)
		assertEquals(1, result?.newReleasesCount)

		val updatedAnchor = db.getTrackingAnchorsDao().find(testMangaId)
		val updatedTrack = db.getTracksDao().find(testMangaId)

		// Crucial assertion: BOTH rows match the exact count (+1), never inflated (+3)!
		assertEquals(1, updatedAnchor?.newReleasesCount)
		assertEquals(1, updatedTrack?.newChapters)
	}

	@Test
	fun staleStoredCounter_recomputesOnCounterVersionBump() = runBlocking {
		db.getMangaDao().upsert(testManga)

		val chapters = listOf(
			MangaChapter(1001L, "Ch 104", 104f, 1, "/c104-flame", "Flame", 1000L, "default", TestMangaSource),
			MangaChapter(2001L, "Ch 105", 105f, 1, "/c105-flame", "Flame", 2000L, "default", TestMangaSource),
			MangaChapter(2002L, "Ch 105", 105f, 2, "/c105-asura", "Asura", 2001L, "default", TestMangaSource),
			MangaChapter(2003L, "Ch 105", 105f, 0, "/c105-zero", "Zero", 2002L, "default", TestMangaSource),
		)
		saveChapters(chapters)

		db.getHistoryDao().upsert(
			HistoryEntity(
				mangaId = testMangaId,
				createdAt = 1000L,
				updatedAt = 1000L,
				chapterId = 1001L,
				page = 1,
				scroll = 0f,
				percent = 0.99f,
				chaptersCount = 4,
				deletedAt = 0L,
			),
		)

		// Stale inflated anchor in DB holding +3 from previous broken version
		db.getTrackingAnchorsDao().upsert(
			TrackingAnchorEntity(
				mangaId = testMangaId,
				lastReadVolume = 1,
				lastReadNumber = 104f,
				latestVolumeAtLastRead = 1,
				latestNumberAtLastRead = 104f,
				baselineVolume = null,
				baselineNumber = null,
				branch = "default",
				newReleasesCount = 3, // Inflated!
				newDetectedAt = 1000L,
				lastCheckedAt = 1000L,
				status = TrackingAnchorEntity.STATUS_OK,
				failCount = 0,
				pendingShrinkCount = null,
				pendingShrinkHash = null,
			),
		)

		// Set counter version to 0 to simulate old app version
		settings.trackingCounterVersion = 0

		// Sync tracks to anchors triggers one-time recompute!
		trackingRepository.syncTracksToAnchors()

		val fixedAnchor = db.getTrackingAnchorsDao().find(testMangaId)
		val fixedTrack = db.getTracksDao().find(testMangaId)

		// Automatically recomputed and cleaned up from 3 to 1!
		assertEquals(1, fixedAnchor?.newReleasesCount)
		assertEquals(1, fixedTrack?.newChapters)
		assertEquals(1, settings.trackingCounterVersion)
	}
}

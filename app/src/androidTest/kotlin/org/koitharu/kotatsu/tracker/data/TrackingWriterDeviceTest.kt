package org.koitharu.kotatsu.tracker.data

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.ChapterEntity
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.core.model.TestMangaSource
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.tracker.domain.ChapterCounter

@RunWith(AndroidJUnit4::class)
class TrackingWriterDeviceTest {

	private lateinit var db: MangaDatabase

	@Before
	fun setUp() {
		db = Room.inMemoryDatabaseBuilder(
			InstrumentationRegistry.getInstrumentation().targetContext,
			MangaDatabase::class.java,
		).build()
	}

	@After
	fun tearDown() {
		db.close()
	}

	/**
	 * Concurrency check: If user reads ahead to Ch. 90 while a network fetch for Ch. 101..105 was in flight,
	 * computing inside the transaction from current DB state ensures final count reflects Ch. 90 (15 unread),
	 * not the stale Ch. 80 (25 unread).
	 */
	@Test
	fun concurrency_historyWriteBetweenFetchAndApply_finalCountReflectsNewPosition() = runBlocking {
		val mangaDao = db.getMangaDao()
		val historyDao = db.getHistoryDao()
		val chaptersDao = db.getChaptersDao()
		val anchorsDao = db.getTrackingAnchorsDao()
		val tracksDao = db.getTracksDao()

		val mangaId = 1L
		mangaDao.upsert(createManga(mangaId, "Concurrency Manga"))

		// 1. Initially user is at Ch. 80
		historyDao.upsert(
			HistoryEntity(
				mangaId = mangaId,
				createdAt = 1000L,
				updatedAt = 1000L,
				chapterId = 80L,
				page = 1,
				scroll = 0f,
				percent = 0.76f,
				chaptersCount = 105,
				deletedAt = 0L,
			),
		)
		anchorsDao.upsert(
			TrackingAnchorEntity(
				mangaId = mangaId,
				lastReadVolume = 0,
				lastReadNumber = 80f,
				latestVolumeAtLastRead = 0,
				latestNumberAtLastRead = 100f,
				baselineVolume = null,
				baselineNumber = null,
				branch = "default",
				newReleasesCount = 0,
				newDetectedAt = 1000L,
				lastCheckedAt = 1000L,
				status = TrackingAnchorEntity.STATUS_OK,
				failCount = 0,
				pendingShrinkCount = null,
				pendingShrinkHash = null,
			),
		)

		// 2. Refresher fetched new chapters (1..105) over network.
		// Before the apply transaction opens, user advances to Ch. 90 in the reader!
		historyDao.upsert(
			HistoryEntity(
				mangaId = mangaId,
				createdAt = 1000L,
				updatedAt = 2000L,
				chapterId = 90L,
				page = 1,
				scroll = 0f,
				percent = 0.85f,
				chaptersCount = 105,
				deletedAt = 0L,
			),
		)

		val fetchedChapters = generateChapters(1..105)
		// Store fetched chapters in DB
		chaptersDao.replaceAll(mangaId, fetchedChapters.mapIndexed { i, ch ->
			ChapterEntity(
				chapterId = ch.id,
				mangaId = mangaId,
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

		// 3. Apply transaction opens: computes from CURRENT DB state
		db.withTransaction {
			val currentHistory = historyDao.find(mangaId)?.toMangaHistory()
			val currentAnchor = anchorsDao.find(mangaId)?.toTrackingAnchor()
			val cachedChapters = chaptersDao.findAll(mangaId).map { it.toMangaChapter() }

			val result = ChapterCounter.computeNewChapters(
				chapters = cachedChapters,
				history = currentHistory,
				anchor = currentAnchor,
				preferredBranch = "default",
			)

			// Write inside the transaction
			anchorsDao.upsert(
				TrackingAnchorEntity(
					mangaId = mangaId,
					lastReadVolume = 0,
					lastReadNumber = 90f,
					latestVolumeAtLastRead = 0,
					latestNumberAtLastRead = 100f, // previous snapshot was 100f
					baselineVolume = null,
					baselineNumber = null,
					branch = "default",
					newReleasesCount = result.newReleasesCount,
					newDetectedAt = 3000L,
					lastCheckedAt = 3000L,
					status = TrackingAnchorEntity.STATUS_OK,
					failCount = 0,
					pendingShrinkCount = null,
					pendingShrinkHash = null,
				),
			)
			tracksDao.upsert(
				TrackEntity(
					mangaId = mangaId,
					lastChapterId = 105L,
					newChapters = result.count,
					lastCheckTime = 3000L,
					lastChapterDate = 0L,
					lastResult = TrackEntity.RESULT_HAS_UPDATE,
					lastError = null,
				),
			)
		}

		// Assert final count reflects Ch. 90 (15 unread: 91..105), NOT stale Ch. 80 (25 unread)!
		val updatedTrack = tracksDao.find(mangaId)
		val updatedAnchor = anchorsDao.find(mangaId)

		assertNotNull(updatedTrack)
		assertNotNull(updatedAnchor)
		assertEquals(15, updatedTrack?.newChapters) // unread count is 15
		assertEquals(5, updatedAnchor?.newReleasesCount) // new releases beyond 100f is 5 (101..105)
	}

	@Test
	fun deleteHistory_singleTitle_resetsAnchorToNeverReadBaseline() = runBlocking {
		val mangaDao = db.getMangaDao()
		val historyDao = db.getHistoryDao()
		val chaptersDao = db.getChaptersDao()
		val anchorsDao = db.getTrackingAnchorsDao()
		val tracksDao = db.getTracksDao()

		val mangaId = 2L
		mangaDao.upsert(createManga(mangaId, "Delete History Manga"))
		val chapters = generateChapters(1..100)
		chaptersDao.replaceAll(mangaId, chapters.mapIndexed { i, ch ->
			ChapterEntity(
				chapterId = ch.id,
				mangaId = mangaId,
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

		historyDao.upsert(
			HistoryEntity(
				mangaId = mangaId,
				createdAt = 1000L,
				updatedAt = 1000L,
				chapterId = 50L,
				page = 1,
				scroll = 0f,
				percent = 0.50f,
				chaptersCount = 100,
				deletedAt = 0L,
			),
		)
		anchorsDao.upsert(
			TrackingAnchorEntity.create(mangaId, "default").copy(
				lastReadNumber = 50f,
				latestNumberAtLastRead = 100f,
				newReleasesCount = 5,
			),
		)
		tracksDao.upsert(TrackEntity.create(mangaId).copy(newChapters = 50))

		// User deletes history for this manga
		db.withTransaction {
			historyDao.delete(mangaId)
			// Reset anchor to unread/never-read baseline
			val latest = chaptersDao.findAll(mangaId).lastOrNull()
			anchorsDao.upsert(
				TrackingAnchorEntity.create(mangaId, "default").copy(
					baselineVolume = latest?.volume ?: 0,
					baselineNumber = latest?.number ?: 0f,
					latestVolumeAtLastRead = latest?.volume ?: 0,
					latestNumberAtLastRead = latest?.number ?: 0f,
					newReleasesCount = 0,
					newDetectedAt = 0L,
				),
			)
			tracksDao.clearCounter(mangaId)
		}

		val anchor = anchorsDao.find(mangaId)
		val track = tracksDao.find(mangaId)

		assertNotNull(anchor)
		assertNull(anchor?.lastReadNumber) // last_read cleared
		assertEquals(100f, anchor?.baselineNumber) // baseline set to latest
		assertEquals(100f, anchor?.latestNumberAtLastRead)
		assertEquals(0, anchor?.newReleasesCount) // count reset to 0
		assertEquals(0, track?.newChapters) // tracks reset to 0
	}

	@Test
	fun historyCleared_thenMangaReRead_startsCleanFromNewPosition() = runBlocking {
		val mangaDao = db.getMangaDao()
		val historyDao = db.getHistoryDao()
		val chaptersDao = db.getChaptersDao()
		val anchorsDao = db.getTrackingAnchorsDao()
		val tracksDao = db.getTracksDao()

		val mangaId = 3L
		mangaDao.upsert(createManga(mangaId, "Re-read Manga"))
		val chapters = generateChapters(1..100)
		chaptersDao.replaceAll(mangaId, chapters.mapIndexed { i, ch ->
			ChapterEntity(
				chapterId = ch.id,
				mangaId = mangaId,
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

		// Baseline anchor after history cleared
		anchorsDao.upsert(
			TrackingAnchorEntity.create(mangaId, "default").copy(
				baselineNumber = 100f,
				latestNumberAtLastRead = 100f,
				newReleasesCount = 0,
			),
		)

		// User opens manga again and reads Ch. 1 (page = 1)
		db.withTransaction {
			historyDao.upsert(
				HistoryEntity(
					mangaId = mangaId,
					createdAt = 3000L,
					updatedAt = 3000L,
					chapterId = 1L,
					page = 1,
					scroll = 0f,
					percent = 0.01f,
					chaptersCount = 100,
					deletedAt = 0L,
				),
			)
			val currentHistory = historyDao.find(mangaId)?.toMangaHistory()
			val currentAnchor = anchorsDao.find(mangaId)?.toTrackingAnchor()
			val cachedChapters = chaptersDao.findAll(mangaId).map { it.toMangaChapter() }

			val result = ChapterCounter.computeNewChapters(
				chapters = cachedChapters,
				history = currentHistory,
				anchor = currentAnchor,
				preferredBranch = "default",
			)

			anchorsDao.upsert(
				TrackingAnchorEntity.create(mangaId, "default").copy(
					lastReadNumber = 1f,
					latestNumberAtLastRead = 100f, // preserved snapshot at 100f
					newReleasesCount = result.newReleasesCount,
					newDetectedAt = 0L,
				),
			)
			tracksDao.upsert(
				TrackEntity.create(mangaId).copy(newChapters = result.count),
			)
		}

		val anchor = anchorsDao.find(mangaId)
		val track = tracksDao.find(mangaId)

		assertNotNull(anchor)
		assertEquals(1f, anchor?.lastReadNumber)
		assertEquals(99, track?.newChapters) // Ch. 2..100 = 99 unread
		assertEquals(0, anchor?.newReleasesCount) // 0 new releases past 100f
	}

	@Test
	fun aniListImportApply_createsAnchorWithSnapshotAtLatestAndZeroNewReleases() = runBlocking {
		val mangaDao = db.getMangaDao()
		val historyDao = db.getHistoryDao()
		val chaptersDao = db.getChaptersDao()
		val anchorsDao = db.getTrackingAnchorsDao()
		val tracksDao = db.getTracksDao()

		val mangaId = 4L
		mangaDao.upsert(createManga(mangaId, "AniList Import Manga"))
		val chapters = generateChapters(1..105)
		chaptersDao.replaceAll(mangaId, chapters.mapIndexed { i, ch ->
			ChapterEntity(
				chapterId = ch.id,
				mangaId = mangaId,
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

		// AniList import: progress at Ch. 80, points to Ch. 81 (page 0, scroll 0)
		db.withTransaction {
			historyDao.upsert(
				HistoryEntity(
					mangaId = mangaId,
					createdAt = 5000L,
					updatedAt = 5000L,
					chapterId = 81L,
					page = 0,
					scroll = 0f,
					percent = 0.77f,
					chaptersCount = 105,
					deletedAt = 0L,
				),
			)

			val currentHistory = historyDao.find(mangaId)?.toMangaHistory()
			val cachedChapters = chaptersDao.findAll(mangaId).map { it.toMangaChapter() }
			// Import knows source's current latest chapter (Ch. 105)
			val latestChapter = cachedChapters.lastOrNull()
			val importAnchor = TrackingAnchorEntity.create(mangaId, "default").copy(
				latestVolumeAtLastRead = latestChapter?.volume ?: 0,
				latestNumberAtLastRead = latestChapter?.number ?: 0f,
			).toTrackingAnchor()

			val result = ChapterCounter.computeNewChapters(
				chapters = cachedChapters,
				history = currentHistory,
				anchor = importAnchor,
				preferredBranch = "default",
			)

			anchorsDao.upsert(
				TrackingAnchorEntity.create(mangaId, "default").copy(
					lastReadNumber = 80f,
					latestVolumeAtLastRead = latestChapter?.volume ?: 0,
					latestNumberAtLastRead = latestChapter?.number ?: 0f,
					newReleasesCount = result.newReleasesCount,
					newDetectedAt = 0L,
				),
			)
			tracksDao.upsert(
				TrackEntity.create(mangaId).copy(newChapters = result.count),
			)
		}

		val anchor = anchorsDao.find(mangaId)
		val track = tracksDao.find(mangaId)

		assertNotNull(anchor)
		assertEquals(25, track?.newChapters) // Honest unread count is 25 (Ch. 81..105)
		assertEquals(0, anchor?.newReleasesCount) // 0 new releases (stays out of New Rail)
		assertEquals(105f, anchor?.latestNumberAtLastRead)
	}

	private fun createManga(id: Long, title: String) = MangaEntity(
		id = id,
		title = title,
		altTitles = null,
		url = "/manga/$id",
		publicUrl = "https://example.org/manga/$id",
		rating = -1f,
		isNsfw = false,
		contentRating = null,
		coverUrl = "https://example.org/cover/$id.jpg",
		largeCoverUrl = null,
		state = null,
		authors = null,
		source = "TEST",
	)

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

	private fun ChapterEntity.toMangaChapter() = MangaChapter(
		id = chapterId,
		title = title,
		number = number,
		volume = volume,
		url = url,
		scanlator = scanlator,
		uploadDate = uploadDate,
		branch = branch,
		source = TestMangaSource,
	)

	private fun HistoryEntity.toMangaHistory() = org.koitharu.kotatsu.core.model.MangaHistory(
		createdAt = java.time.Instant.ofEpochMilli(createdAt),
		updatedAt = java.time.Instant.ofEpochMilli(updatedAt),
		chapterId = chapterId,
		page = page,
		scroll = scroll.toInt(),
		percent = percent,
		chaptersCount = chaptersCount,
	)

	private fun TrackingAnchorEntity.toTrackingAnchor() = org.koitharu.kotatsu.tracker.domain.TrackingAnchor(
		lastChapterId = 0L,
		lastReadVolume = lastReadVolume,
		lastReadNumber = lastReadNumber,
		latestVolumeAtLastRead = latestVolumeAtLastRead,
		latestNumberAtLastRead = latestNumberAtLastRead,
		baselineVolume = baselineVolume,
		baselineNumber = baselineNumber,
		branch = branch,
		newDetectedAt = newDetectedAt,
		failCount = failCount,
		status = status,
	)
}

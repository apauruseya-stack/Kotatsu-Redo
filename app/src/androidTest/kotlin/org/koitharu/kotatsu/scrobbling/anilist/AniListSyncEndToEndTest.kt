package org.koitharu.kotatsu.scrobbling.anilist

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.ChapterEntity
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.scrobbling.anilist.data.AniListRepository
import org.koitharu.kotatsu.scrobbling.anilist.data.AniListSyncLogStore
import org.koitharu.kotatsu.scrobbling.anilist.domain.AniListSyncResult
import org.koitharu.kotatsu.scrobbling.anilist.domain.AniListSyncUseCase
import org.koitharu.kotatsu.scrobbling.anilist.domain.model.AniListMediaEntry
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerStorage
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerUser
import org.koitharu.kotatsu.scrobbling.common.ui.ScrobblerAuthHelper
import org.koitharu.kotatsu.tracker.data.TrackingAnchorEntity
import org.koitharu.kotatsu.tracker.domain.TrackingRepository
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AniListSyncEndToEndTest {

	@get:Rule
	var hiltRule = HiltAndroidRule(this)

	@Inject
	lateinit var database: MangaDatabase

	@Inject
	lateinit var mangaDataRepository: MangaDataRepository

	@Inject
	lateinit var trackingRepository: TrackingRepository

	@Inject
	lateinit var appSettings: AppSettings

	@Inject
	lateinit var syncLogStore: AniListSyncLogStore

	@Inject
	lateinit var syncStateStore: org.koitharu.kotatsu.scrobbling.anilist.data.AniListSyncStateStore

	private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
	private val testSource: MangaSource get() = SampleData.manga.source
	private val testMangaId = 888100L
	private val testMediaId = 555200L

	private val testManga = MangaEntity(
		id = testMangaId,
		title = "Sync Test Manga",
		altTitles = null,
		url = "/manga/888100",
		publicUrl = "https://example.org/manga/888100",
		rating = -1f,
		isNsfw = false,
		contentRating = null,
		coverUrl = "https://example.org/cover/888100.jpg",
		largeCoverUrl = null,
		state = null,
		authors = null,
		source = testSource.name,
	)

	private class TestAniListRepository(
		context: android.content.Context,
		okHttp: OkHttpClient,
		storage: ScrobblerStorage,
		db: MangaDatabase,
	) : AniListRepository(context, okHttp, storage, db) {
		val outboundMutationsCount = AtomicInteger(0)
		var entriesToReturn = ArrayList<AniListMediaEntry>()
		var throwAuthExpired: Boolean = false
		var throwNetworkError: Boolean = false

		override suspend fun updateRate(rateId: Int, mangaId: Long, chapter: Int) {
			outboundMutationsCount.incrementAndGet()
		}

		override suspend fun updateRate(rateId: Int, mangaId: Long, rating: Float, status: String?, comment: String?) {
			outboundMutationsCount.incrementAndGet()
		}

		override suspend fun fetchMediaListUpdatedSince(userId: Long, sinceTimestampSeconds: Long): List<AniListMediaEntry> {
			if (throwAuthExpired) throw org.koitharu.kotatsu.scrobbling.anilist.data.AniListAuthExpiredException("Test auth expired")
			if (throwNetworkError) throw java.io.IOException("Test mid-pagination crash")
			return entriesToReturn
		}
	}

	private lateinit var testAniListRepository: TestAniListRepository
	private lateinit var syncUseCase: AniListSyncUseCase

	@Before
	fun setUp() {
		hiltRule.inject()
		cleanUp()

		// Authorize AniList in storage
		val storage = ScrobblerStorage(context, ScrobblerService.ANILIST)
		storage.accessToken = "test_token"
		storage.user = ScrobblerUser(99999L, "TestUser", null, ScrobblerService.ANILIST)

		testAniListRepository = TestAniListRepository(
			context = context,
			okHttp = OkHttpClient(),
			storage = storage,
			db = database,
		)

		syncUseCase = AniListSyncUseCase(
			context = context,
			db = database,
			aniListRepository = testAniListRepository,
			mangaDataRepository = mangaDataRepository,
			trackingRepository = trackingRepository,
			settings = appSettings,
			syncLogStore = syncLogStore,
			syncStateStore = syncStateStore,
		)
		appSettings.isAniListSyncEnabled = true
		appSettings.aniListLastSyncTime = 0L
		appSettings.aniListLastSyncRemoteUpdatedAt = 0L
	}

	@After
	fun tearDown() {
		cleanUp()
	}

	private fun cleanUp() = runBlocking {
		database.getTracksDao().delete(testMangaId)
		database.getTrackingAnchorsDao().delete(testMangaId)
		database.getHistoryDao().delete(testMangaId)
		database.getChaptersDao().deleteAll(testMangaId)
		database.getScrobblingDao().delete(ScrobblerService.ANILIST.id, testMangaId)
		database.getMangaDao().delete(listOf(testManga))
		syncLogStore.clear()
		syncStateStore.clear()
		appSettings.aniListLastSyncRemoteUpdatedAt = 0L
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
			source = testSource,
		)
	}

	private suspend fun saveChapters(chapters: List<MangaChapter>) {
		database.getChaptersDao().replaceAll(testMangaId, chapters.mapIndexed { i, ch ->
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
				source = testSource.name,
				index = i,
			)
		})
	}

	@Test
	fun noEcho_pullMakesZeroOutboundMutationsToAniList() = runBlocking {
		database.getMangaDao().upsert(testManga)
		saveChapters(generateChapters(1..150))

		// Local reading at Ch. 120 (page 1)
		database.getHistoryDao().upsert(
			HistoryEntity(
				mangaId = testMangaId,
				createdAt = 1000L,
				updatedAt = 1000L,
				chapterId = testMangaId * 1000L + 120,
				page = 1,
				scroll = 0f,
				percent = 0.80f,
				chaptersCount = 150,
				deletedAt = 0L,
			),
		)

		// Link manga to AniList media entry
		database.getScrobblingDao().upsert(
			ScrobblingEntity(
				scrobbler = ScrobblerService.ANILIST.id,
				id = 1111,
				mangaId = testMangaId,
				targetId = testMediaId,
				status = "CURRENT",
				chapter = 120,
				comment = null,
				rating = 0.9f,
			),
		)

		// Remote AniList progress is now 125 (read on web)
		testAniListRepository.entriesToReturn = arrayListOf(
			AniListMediaEntry(
				entryId = 1111,
				mediaId = testMediaId,
				status = "CURRENT",
				progress = 125,
				score = 0.9f,
				notes = null,
				preferredTitle = "Sync Test Manga",
				allTitles = listOf("Sync Test Manga"),
				totalChapters = 150,
				coverUrl = null,
				siteUrl = null,
				format = "MANGA",
				updatedAt = 2500L,
			),
		)

		// Execute pull sync
		val result = syncUseCase.sync(force = true)
		assertTrue(result is AniListSyncResult.Success)
		assertEquals(1, (result as AniListSyncResult.Success).updatedCount)

		// Assert NO ECHO: Zero outbound mutations to AniList during pull!
		assertEquals(0, testAniListRepository.outboundMutationsCount.get())

		// Verify local history was advanced to Ch. 126 unstarted (page 0)
		val updatedHistory = database.getHistoryDao().find(testMangaId)
		assertNotNull(updatedHistory)
		assertEquals(testMangaId * 1000L + 126, updatedHistory!!.chapterId)
		assertEquals(0, updatedHistory.page)
		assertEquals(2500L, updatedHistory.updatedAt) // Timestamp preserved from AniList!
	}

	@Test
	fun roundTrip_localRead_scrobbles_pullResultsInNoChange() = runBlocking {
		database.getMangaDao().upsert(testManga)
		saveChapters(generateChapters(1..150))

		// Local reading at Ch. 120 (page 1)
		database.getHistoryDao().upsert(
			HistoryEntity(
				mangaId = testMangaId,
				createdAt = 1000L,
				updatedAt = 1000L,
				chapterId = testMangaId * 1000L + 120,
				page = 1,
				scroll = 0f,
				percent = 0.80f,
				chaptersCount = 150,
				deletedAt = 0L,
			),
		)

		database.getScrobblingDao().upsert(
			ScrobblingEntity(
				scrobbler = ScrobblerService.ANILIST.id,
				id = 1111,
				mangaId = testMangaId,
				targetId = testMediaId,
				status = "CURRENT",
				chapter = 120,
				comment = null,
				rating = 0.9f,
			),
		)

		// AniList returns progress = 120 (identical to local)
		testAniListRepository.entriesToReturn = arrayListOf(
			AniListMediaEntry(
				entryId = 1111,
				mediaId = testMediaId,
				status = "CURRENT",
				progress = 120,
				score = 0.9f,
				notes = null,
				preferredTitle = "Sync Test Manga",
				allTitles = listOf("Sync Test Manga"),
				totalChapters = 150,
				coverUrl = null,
				siteUrl = null,
				format = "MANGA",
				updatedAt = 1000L,
			),
		)

		val result = syncUseCase.sync(force = true)
		assertTrue(result is AniListSyncResult.Success)
		assertEquals(0, (result as AniListSyncResult.Success).updatedCount) // No change, no ping-pong!
		assertEquals(0, testAniListRepository.outboundMutationsCount.get())
	}

	@Test
	fun idempotency_applyTwice_secondRunDoesNothing() = runBlocking {
		database.getMangaDao().upsert(testManga)
		saveChapters(generateChapters(1..150))

		database.getHistoryDao().upsert(
			HistoryEntity(
				mangaId = testMangaId,
				createdAt = 1000L,
				updatedAt = 1000L,
				chapterId = testMangaId * 1000L + 50,
				page = 1,
				scroll = 0f,
				percent = 0.33f,
				chaptersCount = 150,
				deletedAt = 0L,
			),
		)

		database.getScrobblingDao().upsert(
			ScrobblingEntity(
				scrobbler = ScrobblerService.ANILIST.id,
				id = 1111,
				mangaId = testMangaId,
				targetId = testMediaId,
				status = "CURRENT",
				chapter = 50,
				comment = null,
				rating = 0.9f,
			),
		)

		testAniListRepository.entriesToReturn = arrayListOf(
			AniListMediaEntry(
				entryId = 1111,
				mediaId = testMediaId,
				status = "CURRENT",
				progress = 60,
				score = 0.9f,
				notes = null,
				preferredTitle = "Sync Test Manga",
				allTitles = listOf("Sync Test Manga"),
				totalChapters = 150,
				coverUrl = null,
				siteUrl = null,
				format = "MANGA",
				updatedAt = 3000L,
			),
		)

		// First apply
		val result1 = syncUseCase.sync(force = true)
		assertEquals(1, (result1 as AniListSyncResult.Success).updatedCount)

		// Second apply immediately after
		val result2 = syncUseCase.sync(force = true)
		assertEquals(0, (result2 as AniListSyncResult.Success).updatedCount)
	}

	@Test
	fun clampedTitle_advancesWhenSourceCatchesUp() = runBlocking {
		database.getMangaDao().upsert(testManga)
		// Source currently only has 100 chapters
		saveChapters(generateChapters(1..100))

		database.getHistoryDao().upsert(
			HistoryEntity(
				mangaId = testMangaId,
				createdAt = 1000L,
				updatedAt = 1000L,
				chapterId = testMangaId * 1000L + 90,
				page = 1,
				scroll = 0f,
				percent = 0.90f,
				chaptersCount = 100,
				deletedAt = 0L,
			),
		)

		database.getScrobblingDao().upsert(
			ScrobblingEntity(
				scrobbler = ScrobblerService.ANILIST.id,
				id = 1111,
				mangaId = testMangaId,
				targetId = testMediaId,
				status = "CURRENT",
				chapter = 90,
				comment = null,
				rating = 0.9f,
			),
		)

		// AniList says 103 (source is 3 chapters behind)
		testAniListRepository.entriesToReturn = arrayListOf(
			AniListMediaEntry(
				entryId = 1111,
				mediaId = testMediaId,
				status = "CURRENT",
				progress = 103,
				score = 0.9f,
				notes = null,
				preferredTitle = "Sync Test Manga",
				allTitles = listOf("Sync Test Manga"),
				totalChapters = 110,
				coverUrl = null,
				siteUrl = null,
				format = "MANGA",
				updatedAt = 4000L,
			),
		)

		// First sync: clamps to Ch. 100 with percent = 1.0f
		val result1 = syncUseCase.sync(force = true)
		assertEquals(1, (result1 as AniListSyncResult.Success).updatedCount)
		val clampedHistory = database.getHistoryDao().find(testMangaId)
		assertNotNull(clampedHistory)
		assertEquals(testMangaId * 1000L + 100, clampedHistory!!.chapterId)
		assertEquals(1.0f, clampedHistory.percent, 0.001f)

		// Verify sync log marked clamped
		val logEntry = syncLogStore.entries.value.firstOrNull()
		assertNotNull(logEntry)
		assertTrue(logEntry!!.isClamped)

		// Source catches up and uploads up to Ch. 110!
		saveChapters(generateChapters(1..110))

		// Next sync re-applies remote 103 against new chapter list
		val result2 = syncUseCase.sync(force = true)
		assertEquals(1, (result2 as AniListSyncResult.Success).updatedCount)

		val caughtUpHistory = database.getHistoryDao().find(testMangaId)
		assertNotNull(caughtUpHistory)
		// Remote 103 -> next chapter to read is Ch. 104
		assertEquals(testMangaId * 1000L + 104, caughtUpHistory!!.chapterId)
		assertEquals(0, caughtUpHistory.page)
		assertFalse(caughtUpHistory.percent >= 1f)
	}

	@Test
	fun integration_titleDropsFromNewRailAndShowsInContinue() = runBlocking {
		database.getMangaDao().upsert(testManga)
		saveChapters(generateChapters(1..110))

		// User read up to Ch. 100. Ch. 101..110 released -> 10 new releases on New Rail!
		database.getHistoryDao().upsert(
			HistoryEntity(
				mangaId = testMangaId,
				createdAt = 1000L,
				updatedAt = 1000L,
				chapterId = testMangaId * 1000L + 100,
				page = 1,
				scroll = 0f,
				percent = 0.90f,
				chaptersCount = 110,
				deletedAt = 0L,
			),
		)

		database.getTrackingAnchorsDao().upsert(
			TrackingAnchorEntity.create(testMangaId, "default").copy(
				lastReadNumber = 100f,
				latestNumberAtLastRead = 100f,
				newReleasesCount = 10,
			),
		)

		database.getScrobblingDao().upsert(
			ScrobblingEntity(
				scrobbler = ScrobblerService.ANILIST.id,
				id = 1111,
				mangaId = testMangaId,
				targetId = testMediaId,
				status = "CURRENT",
				chapter = 100,
				comment = null,
				rating = 0.9f,
			),
		)

		// Assert before: Title is on New Rail with 10 new releases
		val anchorBefore = database.getTrackingAnchorsDao().find(testMangaId)
		assertEquals(10, anchorBefore?.newReleasesCount)

		// AniList sync pulls progress 110 (completed on web / caught up to latest release)
		testAniListRepository.entriesToReturn = arrayListOf(
			AniListMediaEntry(
				entryId = 1111,
				mediaId = testMediaId,
				status = "COMPLETED",
				progress = 110,
				score = 0.95f,
				notes = null,
				preferredTitle = "Sync Test Manga",
				allTitles = listOf("Sync Test Manga"),
				totalChapters = 110,
				coverUrl = null,
				siteUrl = null,
				format = "MANGA",
				updatedAt = 5000L,
			),
		)

		val result = syncUseCase.sync(force = true)
		assertEquals(1, (result as AniListSyncResult.Success).updatedCount)

		// Assert after: Title dropped from New Rail! newReleasesCount is 0!
		val anchorAfter = database.getTrackingAnchorsDao().find(testMangaId)
		assertEquals(0, anchorAfter?.newReleasesCount)
		assertEquals(110f, anchorAfter?.latestNumberAtLastRead)

		// History updated to Ch. 110 with percent = 1.0f
		val historyAfter = database.getHistoryDao().find(testMangaId)
		assertNotNull(historyAfter)
		assertEquals(testMangaId * 1000L + 110, historyAfter!!.chapterId)
		assertEquals(1.0f, historyAfter.percent, 0.001f)
	}

	@Test
	fun badLink_remoteFarBeyond_isSkippedAndFlagged() = runBlocking {
		database.getMangaDao().upsert(testManga)
		saveChapters(generateChapters(1..100))

		database.getHistoryDao().upsert(
			HistoryEntity(
				mangaId = testMangaId,
				createdAt = 1000L,
				updatedAt = 1000L,
				chapterId = testMangaId * 1000L + 20,
				page = 1,
				scroll = 0f,
				percent = 0.20f,
				chaptersCount = 100,
				deletedAt = 0L,
			),
		)

		database.getScrobblingDao().upsert(
			ScrobblingEntity(
				scrobbler = ScrobblerService.ANILIST.id,
				id = 1111,
				mangaId = testMangaId,
				targetId = testMediaId,
				status = "CURRENT",
				chapter = 20,
				comment = null,
				rating = 0.9f,
			),
		)

		// Remote says 300 (> 100 * 1.5 = 150) -> suspected bad link / mismatched manga!
		testAniListRepository.entriesToReturn = arrayListOf(
			AniListMediaEntry(
				entryId = 1111,
				mediaId = testMediaId,
				status = "CURRENT",
				progress = 300,
				score = 0.9f,
				notes = null,
				preferredTitle = "Sync Test Manga",
				allTitles = listOf("Sync Test Manga"),
				totalChapters = 350,
				coverUrl = null,
				siteUrl = null,
				format = "MANGA",
				updatedAt = 6000L,
			),
		)

		val result = syncUseCase.sync(force = true)
		assertEquals(0, (result as AniListSyncResult.Success).updatedCount) // Skipped!

		// Local history NOT corrupted
		val history = database.getHistoryDao().find(testMangaId)
		assertEquals(testMangaId * 1000L + 20, history?.chapterId)
	}

	@Test
	fun rereadScenario_remoteUnchanged_localBehind_nothingMoves() = runBlocking {
		database.getMangaDao().upsert(testManga)
		saveChapters(generateChapters(1..150))

		// User had previously synced up to 119
		syncStateStore.updateLastSeen(testMangaId, testMediaId, progress = 119, updatedAtSec = 2000L)

		// User is deliberately rereading locally from Ch. 10
		database.getHistoryDao().upsert(
			HistoryEntity(
				mangaId = testMangaId,
				createdAt = 1000L,
				updatedAt = 1000L,
				chapterId = testMangaId * 1000L + 10,
				page = 1,
				scroll = 0f,
				percent = 0.50f,
				chaptersCount = 150,
				deletedAt = 0L,
			),
		)

		database.getScrobblingDao().upsert(
			ScrobblingEntity(
				scrobbler = ScrobblerService.ANILIST.id,
				id = 1111,
				mangaId = testMangaId,
				targetId = testMediaId,
				status = "CURRENT",
				chapter = 119,
				comment = null,
				rating = 0.9f,
			),
		)

		// Remote AniList progress is still 119 (unchanged since last sync)
		testAniListRepository.entriesToReturn = arrayListOf(
			AniListMediaEntry(
				entryId = 1111,
				mediaId = testMediaId,
				status = "CURRENT",
				progress = 119,
				score = 0.9f,
				notes = null,
				preferredTitle = "Sync Test Manga",
				allTitles = listOf("Sync Test Manga"),
				totalChapters = 150,
				coverUrl = null,
				siteUrl = null,
				format = "MANGA",
				updatedAt = 2000L * 1000L,
			),
		)

		val result = syncUseCase.sync(force = true)
		assertTrue(result is AniListSyncResult.Success)
		assertEquals(0, (result as AniListSyncResult.Success).updatedCount)

		// Crucial assertion: Local reread position at Ch. 10 is NOT overwritten!
		val history = database.getHistoryDao().find(testMangaId)
		assertNotNull(history)
		assertEquals(testMangaId * 1000L + 10, history!!.chapterId)
	}

	@Test
	fun remoteChangesAfterLocalReread_advancesOnce() = runBlocking {
		database.getMangaDao().upsert(testManga)
		saveChapters(generateChapters(1..150))

		// User previously synced up to 119
		syncStateStore.updateLastSeen(testMangaId, testMediaId, progress = 119, updatedAtSec = 2000L)

		// User is rereading at Ch. 10
		database.getHistoryDao().upsert(
			HistoryEntity(
				mangaId = testMangaId,
				createdAt = 1000L,
				updatedAt = 1000L,
				chapterId = testMangaId * 1000L + 10,
				page = 1,
				scroll = 0f,
				percent = 0.50f,
				chaptersCount = 150,
				deletedAt = 0L,
			),
		)

		database.getScrobblingDao().upsert(
			ScrobblingEntity(
				scrobbler = ScrobblerService.ANILIST.id,
				id = 1111,
				mangaId = testMangaId,
				targetId = testMediaId,
				status = "CURRENT",
				chapter = 119,
				comment = null,
				rating = 0.9f,
			),
		)

		// User now reads up to Ch. 125 on web (remote actually changed!)
		testAniListRepository.entriesToReturn = arrayListOf(
			AniListMediaEntry(
				entryId = 1111,
				mediaId = testMediaId,
				status = "CURRENT",
				progress = 125,
				score = 0.9f,
				notes = null,
				preferredTitle = "Sync Test Manga",
				allTitles = listOf("Sync Test Manga"),
				totalChapters = 150,
				coverUrl = null,
				siteUrl = null,
				format = "MANGA",
				updatedAt = 3000L * 1000L,
			),
		)

		val result1 = syncUseCase.sync(force = true)
		assertTrue(result1 is AniListSyncResult.Success)
		assertEquals(1, (result1 as AniListSyncResult.Success).updatedCount)

		// History advanced to Ch. 126
		val historyAfter = database.getHistoryDao().find(testMangaId)
		assertNotNull(historyAfter)
		assertEquals(testMangaId * 1000L + 126, historyAfter!!.chapterId)

		// Second sync with remote still at 125 does nothing (idempotent)
		val result2 = syncUseCase.sync(force = true)
		assertEquals(0, (result2 as AniListSyncResult.Success).updatedCount)
	}

	@Test
	fun thirtyChapterBinge_appliesWithSourceLatestAsLimit() = runBlocking {
		database.getMangaDao().upsert(testManga)
		saveChapters(generateChapters(1..100))

		// Local reading at Ch. 10
		database.getHistoryDao().upsert(
			HistoryEntity(
				mangaId = testMangaId,
				createdAt = 1000L,
				updatedAt = 1000L,
				chapterId = testMangaId * 1000L + 10,
				page = 1,
				scroll = 0f,
				percent = 0.10f,
				chaptersCount = 100,
				deletedAt = 0L,
			),
		)

		database.getScrobblingDao().upsert(
			ScrobblingEntity(
				scrobbler = ScrobblerService.ANILIST.id,
				id = 1111,
				mangaId = testMangaId,
				targetId = testMediaId,
				status = "CURRENT",
				chapter = 10,
				comment = null,
				rating = 0.9f,
			),
		)

		// 30 chapter binge on web: remote progress = 40 (within source total of 100)
		testAniListRepository.entriesToReturn = arrayListOf(
			AniListMediaEntry(
				entryId = 1111,
				mediaId = testMediaId,
				status = "CURRENT",
				progress = 40,
				score = 0.9f,
				notes = null,
				preferredTitle = "Sync Test Manga",
				allTitles = listOf("Sync Test Manga"),
				totalChapters = 100,
				coverUrl = null,
				siteUrl = null,
				format = "MANGA",
				updatedAt = 4000L * 1000L,
			),
		)

		val result = syncUseCase.sync(force = true)
		assertTrue(result is AniListSyncResult.Success)
		assertEquals(1, (result as AniListSyncResult.Success).updatedCount)

		// Successfully advanced to Ch. 41 unstarted
		val history = database.getHistoryDao().find(testMangaId)
		assertNotNull(history)
		assertEquals(testMangaId * 1000L + 41, history!!.chapterId)
	}

	@Test
	fun openingChapterAtPage0_producesNoJumpOnNextPull() = runBlocking {
		database.getMangaDao().upsert(testManga)
		saveChapters(generateChapters(1..150))

		// User opens Ch. 120 in reader: Kotatsu scrobbles 120 immediately on open
		database.getHistoryDao().upsert(
			HistoryEntity(
				mangaId = testMangaId,
				createdAt = 1000L,
				updatedAt = 1000L,
				chapterId = testMangaId * 1000L + 120,
				page = 0,
				scroll = 0f,
				percent = 0f,
				chaptersCount = 150,
				deletedAt = 0L,
			),
		)

		database.getScrobblingDao().upsert(
			ScrobblingEntity(
				scrobbler = ScrobblerService.ANILIST.id,
				id = 1111,
				mangaId = testMangaId,
				targetId = testMediaId,
				status = "CURRENT",
				chapter = 120,
				comment = null,
				rating = 0.9f,
			),
		)

		// AniList reflects the scrobbled progress = 120
		testAniListRepository.entriesToReturn = arrayListOf(
			AniListMediaEntry(
				entryId = 1111,
				mediaId = testMediaId,
				status = "CURRENT",
				progress = 120,
				score = 0.9f,
				notes = null,
				preferredTitle = "Sync Test Manga",
				allTitles = listOf("Sync Test Manga"),
				totalChapters = 150,
				coverUrl = null,
				siteUrl = null,
				format = "MANGA",
				updatedAt = 1000L * 1000L,
			),
		)

		val result = syncUseCase.sync(force = true)
		assertTrue(result is AniListSyncResult.Success)
		assertEquals(0, (result as AniListSyncResult.Success).updatedCount)

		// Reading position MUST stay at Ch. 120 page 0! No jumping to Ch. 121!
		val history = database.getHistoryDao().find(testMangaId)
		assertNotNull(history)
		assertEquals(testMangaId * 1000L + 120, history!!.chapterId)
		assertEquals(0, history.page)
	}

	@Test
	fun twoSyncsRunningAtOnce_mutexPreventsConcurrentRuns() = runBlocking {
		database.getMangaDao().upsert(testManga)
		saveChapters(generateChapters(1..150))

		testAniListRepository.entriesToReturn = arrayListOf()

		// Launch two syncs concurrently
		val job1 = async { syncUseCase.sync(force = true) }
		val job2 = async { syncUseCase.sync(force = true) }

		val res1 = job1.await()
		val res2 = job2.await()

		// One should succeed, and one may be AlreadyRunning (or both succeed sequentially)
		val results = listOf(res1, res2)
		assertTrue(results.any { it is AniListSyncResult.Success })
		assertTrue(results.all { it is AniListSyncResult.Success || it is AniListSyncResult.AlreadyRunning })
	}

	@Test
	fun expiredToken_returnsAuthExpired() = runBlocking {
		database.getMangaDao().upsert(testManga)
		database.getScrobblingDao().upsert(
			ScrobblingEntity(
				scrobbler = ScrobblerService.ANILIST.id,
				id = 1111,
				mangaId = testMangaId,
				targetId = testMediaId,
				status = "CURRENT",
				chapter = 10,
				comment = null,
				rating = 0.9f,
			),
		)
		testAniListRepository.throwAuthExpired = true

		val result = syncUseCase.sync(force = true)
		assertTrue(result is AniListSyncResult.AuthExpired)
	}

	@Test
	fun midPaginationKill_doesNotAdvanceWatermark() = runBlocking {
		database.getMangaDao().upsert(testManga)
		saveChapters(generateChapters(1..150))

		database.getScrobblingDao().upsert(
			ScrobblingEntity(
				scrobbler = ScrobblerService.ANILIST.id,
				id = 1111,
				mangaId = testMangaId,
				targetId = testMediaId,
				status = "CURRENT",
				chapter = 10,
				comment = null,
				rating = 0.9f,
			),
		)

		appSettings.aniListLastSyncRemoteUpdatedAt = 1000L
		testAniListRepository.throwNetworkError = true

		val result = syncUseCase.sync(force = true)
		assertTrue(result is AniListSyncResult.Error)

		// Watermark must NOT advance when crash/kill occurs!
		assertEquals(1000L, appSettings.aniListLastSyncRemoteUpdatedAt)
	}
}

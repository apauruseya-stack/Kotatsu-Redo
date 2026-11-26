package org.koitharu.kotatsu.scrobbling.anilist

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.core.model.formatChapterPillLabel
import org.koitharu.kotatsu.core.model.formatContinueLabel
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.details.domain.ProgressUpdateUseCase
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.list.domain.ReadingProgress
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.scrobbling.anilist.domain.AniListImportUseCase
import org.koitharu.kotatsu.scrobbling.anilist.domain.AniListScrobbler
import org.koitharu.kotatsu.scrobbling.anilist.domain.model.AniListImportItem
import org.koitharu.kotatsu.scrobbling.anilist.domain.model.AniListImportPlan
import org.koitharu.kotatsu.scrobbling.anilist.domain.model.AniListMediaEntry
import org.koitharu.kotatsu.scrobbling.anilist.domain.model.ImportDecision
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AniListImportEndToEndTest {

	@get:Rule
	var hiltRule = HiltAndroidRule(this)

	@Inject
	lateinit var database: MangaDatabase

	@Inject
	lateinit var favouritesRepository: FavouritesRepository

	@Inject
	lateinit var mangaDataRepository: MangaDataRepository

	@Inject
	lateinit var historyRepository: HistoryRepository

	@Inject
	lateinit var progressUpdateUseCase: ProgressUpdateUseCase

	@Inject
	lateinit var importUseCase: AniListImportUseCase

	@Inject
	lateinit var aniListScrobbler: AniListScrobbler

	private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
	private val testSource: MangaSource get() = SampleData.manga.source

	@Before
	fun setup() {
		hiltRule.inject()
		importUseCase.clock = Clock.fixed(Instant.parse("2024-06-01T12:00:00Z"), ZoneOffset.UTC)
	}

	private fun createManga(
		id: Long,
		title: String,
		chapterCount: Int,
	): Manga {
		val chapters = (1..chapterCount).map { i ->
			MangaChapter(
				id = id * 1000L + i,
				title = "Chapter $i",
				number = i.toFloat(),
				volume = 0,
				url = "/manga/$id/chapter/$i",
				scanlator = null,
				uploadDate = 1700000000000L + i * 86400000L,
				branch = "Default",
				source = testSource,
			)
		}
		return Manga(
			id = id,
			title = title,
			altTitles = emptySet(),
			url = "/manga/$id",
			publicUrl = "https://example.com/manga/$id",
			rating = 0.9f,
			contentRating = ContentRating.SAFE,
			coverUrl = "https://example.com/manga/$id/cover.jpg",
			tags = emptySet(),
			state = MangaState.ONGOING,
			authors = setOf("Author"),
			chapters = chapters,
			source = testSource,
		)
	}

	@Test
	fun testAniListImportEndToEnd_10Titles() = runBlocking {
		// Clean test database tables
		database.getHistoryDao().clear()
		database.getScrobblingDao().findAllByScrobbler(ScrobblerService.ANILIST.id).forEach {
			database.getScrobblingDao().delete(it.scrobbler, it.mangaId)
		}

		// 1. Title already in library (Pre-existing local title)
		val preExistingManga = createManga(30013L, "One Piece", 1100)
		mangaDataRepository.storeManga(preExistingManga, replaceExisting = true)
		val readingCategory = favouritesRepository.createCategory(
			title = "Reading",
			sortOrder = ListSortOrder.UPDATED,
			isTrackerEnabled = true,
			isVisibleOnShelf = true,
		)
		favouritesRepository.addToCategory(readingCategory.id, listOf(preExistingManga))

		// Build a 10-title representative AniList list
		val frierenManga = createManga(118586L, "Sousou no Frieren", 152) // 152 chapters
		val tokyoGhoulManga = createManga(63327L, "Tokyo Ghoul", 143) // Prequel
		val tokyoGhoulReManga = createManga(85224L, "Tokyo Ghoul:re", 179) // Sequel
		val goodbyeEriManga = createManga(145863L, "Goodbye, Eri", 1) // Completed short title
		val dandadanManga = createManga(132029L, "Dandadan", 160) // Planning
		val hunterManga = createManga(30026L, "Hunter x Hunter", 400) // Paused
		val droppedManga = createManga(123456L, "Dropped Manga", 50) // Dropped
		val csmManga = createManga(105778L, "Chainsaw Man", 170) // Active with recent timestamp

		val items = listOf(
			// 1. In-progress: Frieren read up to Ch. 119 out of 152 -> Next chapter 120, ~78.9% -> 79%
			AniListImportItem(
				entry = AniListMediaEntry(
					entryId = 1001,
					mediaId = 118586L,
					status = "CURRENT",
					progress = 119,
					score = 0.9f,
					notes = null,
					preferredTitle = "Sousou no Frieren",
					allTitles = listOf("Sousou no Frieren", "Frieren: Beyond Journey's End"),
					totalChapters = 152,
					coverUrl = "https://example.com/frieren.jpg",
					siteUrl = "https://anilist.co/manga/118586",
					format = "MANGA",
					updatedAt = 1715000000000L,
				),
				decision = ImportDecision.AUTO_ACCEPT,
				targetCategoryName = "Reading",
				matchedManga = frierenManga,
				isSelected = true,
			),
			// 2. Sequel Pair - Prequel: Tokyo Ghoul (Finished Ch. 143/143)
			AniListImportItem(
				entry = AniListMediaEntry(
					entryId = 1002,
					mediaId = 63327L,
					status = "COMPLETED",
					progress = 143,
					score = 0.85f,
					notes = null,
					preferredTitle = "Tokyo Ghoul",
					allTitles = listOf("Tokyo Ghoul"),
					totalChapters = 143,
					coverUrl = "https://example.com/tg.jpg",
					siteUrl = "https://anilist.co/manga/63327",
					format = "MANGA",
					updatedAt = 1680000000000L,
				),
				decision = ImportDecision.AUTO_ACCEPT,
				targetCategoryName = "Completed",
				matchedManga = tokyoGhoulManga,
				isSelected = true,
			),
			// 3. Sequel Pair - Sequel: Tokyo Ghoul:re (Current Ch. 50/179)
			AniListImportItem(
				entry = AniListMediaEntry(
					entryId = 1003,
					mediaId = 85224L,
					status = "CURRENT",
					progress = 50,
					score = 0.80f,
					notes = null,
					preferredTitle = "Tokyo Ghoul:re",
					allTitles = listOf("Tokyo Ghoul:re"),
					totalChapters = 179,
					coverUrl = "https://example.com/tgre.jpg",
					siteUrl = "https://anilist.co/manga/85224",
					format = "MANGA",
					updatedAt = 1710000000000L,
				),
				decision = ImportDecision.AUTO_ACCEPT,
				targetCategoryName = "Reading",
				matchedManga = tokyoGhoulReManga,
				isSelected = true,
			),
			// 4. Title already in library: One Piece (Existing local manga matched)
			AniListImportItem(
				entry = AniListMediaEntry(
					entryId = 1004,
					mediaId = 30013L,
					status = "CURRENT",
					progress = 1050,
					score = 0.95f,
					notes = null,
					preferredTitle = "One Piece",
					allTitles = listOf("One Piece"),
					totalChapters = 1100,
					coverUrl = "https://example.com/op.jpg",
					siteUrl = "https://anilist.co/manga/30013",
					format = "MANGA",
					updatedAt = 1716000000000L,
				),
				decision = ImportDecision.AUTO_ACCEPT,
				targetCategoryName = "Reading",
				matchedManga = preExistingManga,
				existingLocalMangaId = preExistingManga.id,
				isSelected = true,
			),
			// 5. Title sources don't have: Obscure title (NOT_FOUND decision, unselected)
			AniListImportItem(
				entry = AniListMediaEntry(
					entryId = 1005,
					mediaId = 999999999L,
					status = "CURRENT",
					progress = 5,
					score = 0.5f,
					notes = null,
					preferredTitle = "Unobtainium Manga 404",
					allTitles = listOf("Unobtainium Manga 404"),
					totalChapters = 10,
					coverUrl = null,
					siteUrl = null,
					format = "MANGA",
					updatedAt = 1700000000000L,
				),
				decision = ImportDecision.NOT_FOUND,
				targetCategoryName = "Reading",
				matchedManga = null,
				isSelected = false,
			),
			// 6. Completed Short Manga: Goodbye, Eri (1 chapter)
			AniListImportItem(
				entry = AniListMediaEntry(
					entryId = 1006,
					mediaId = 145863L,
					status = "COMPLETED",
					progress = 1,
					score = 0.92f,
					notes = null,
					preferredTitle = "Goodbye, Eri",
					allTitles = listOf("Goodbye, Eri"),
					totalChapters = 1,
					coverUrl = "https://example.com/eri.jpg",
					siteUrl = "https://anilist.co/manga/145863",
					format = "MANGA",
					updatedAt = 1690000000000L,
				),
				decision = ImportDecision.AUTO_ACCEPT,
				targetCategoryName = "Completed",
				matchedManga = goodbyeEriManga,
				isSelected = true,
			),
			// 7. Planning title: Dandadan (progress = 0)
			AniListImportItem(
				entry = AniListMediaEntry(
					entryId = 1007,
					mediaId = 132029L,
					status = "PLANNING",
					progress = 0,
					score = 0f,
					notes = null,
					preferredTitle = "Dandadan",
					allTitles = listOf("Dandadan"),
					totalChapters = 160,
					coverUrl = "https://example.com/dandadan.jpg",
					siteUrl = "https://anilist.co/manga/132029",
					format = "MANGA",
					updatedAt = null,
				),
				decision = ImportDecision.AUTO_ACCEPT,
				targetCategoryName = "Plan to read",
				matchedManga = dandadanManga,
				isSelected = true,
			),
			// 8. Paused title: Hunter x Hunter (progress = 390)
			AniListImportItem(
				entry = AniListMediaEntry(
					entryId = 1008,
					mediaId = 30026L,
					status = "PAUSED",
					progress = 390,
					score = 0.90f,
					notes = null,
					preferredTitle = "Hunter x Hunter",
					allTitles = listOf("Hunter x Hunter"),
					totalChapters = 400,
					coverUrl = "https://example.com/hxh.jpg",
					siteUrl = "https://anilist.co/manga/30026",
					format = "MANGA",
					updatedAt = 1670000000000L,
				),
				decision = ImportDecision.AUTO_ACCEPT,
				targetCategoryName = "On hold",
				matchedManga = hunterManga,
				isSelected = true,
			),
			// 9. Dropped title
			AniListImportItem(
				entry = AniListMediaEntry(
					entryId = 1009,
					mediaId = 123456L,
					status = "DROPPED",
					progress = 10,
					score = 0.40f,
					notes = null,
					preferredTitle = "Dropped Manga",
					allTitles = listOf("Dropped Manga"),
					totalChapters = 50,
					coverUrl = "https://example.com/dropped.jpg",
					siteUrl = "https://anilist.co/manga/123456",
					format = "MANGA",
					updatedAt = 1660000000000L,
				),
				decision = ImportDecision.AUTO_ACCEPT,
				targetCategoryName = "Dropped",
				matchedManga = droppedManga,
				isSelected = true,
			),
			// 10. Active Reading: Chainsaw Man
			AniListImportItem(
				entry = AniListMediaEntry(
					entryId = 1010,
					mediaId = 105778L,
					status = "CURRENT",
					progress = 97,
					score = 0.88f,
					notes = null,
					preferredTitle = "Chainsaw Man",
					allTitles = listOf("Chainsaw Man"),
					totalChapters = 170,
					coverUrl = "https://example.com/csm.jpg",
					siteUrl = "https://anilist.co/manga/105778",
					format = "MANGA",
					updatedAt = 1715500000000L,
				),
				decision = ImportDecision.AUTO_ACCEPT,
				targetCategoryName = "Reading",
				matchedManga = csmManga,
				isSelected = true,
			),
		)

		val plan = AniListImportPlan(items)

		// Execute apply
		val appliedCount = importUseCase.apply(plan) { _, _ -> }
		// 9 selected items should be applied; item 5 was unselected NOT_FOUND
		assertEquals(9, appliedCount)

		// VERIFICATION 1: Frieren (In-progress title)
		val frierenHistory = database.getHistoryDao().find(frierenManga.id)
		assertNotNull("Frieren history must be created", frierenHistory)
		frierenHistory!!

		// Chapters 1..152: 119 read -> nextIndex is 120 (Ch. 120)
		val frierenChapters = checkNotNull(frierenManga.chapters)
		val targetChapter = frierenChapters.first { it.id == frierenHistory.chapterId }
		assertEquals(120f, targetChapter.number, 0.001f)
		assertEquals(118586L * 1000L + 120L, frierenHistory.chapterId)

		// 119 / 152 = 0.78289... (~78.3%)
		val expectedPercent = 119f / 152f
		assertEquals(expectedPercent, frierenHistory.percent, 0.001f)

		// Test shared formatters on Frieren
		val continueLabel = targetChapter.formatContinueLabel(context)
		assertEquals("Continue · 120", continueLabel)

		val chapterPillLabel = targetChapter.formatChapterPillLabel(context)
		assertEquals("Ch. 120", chapterPillLabel)

		val displayPercent = ReadingProgress.formatPercent(frierenHistory.percent, context)
		assertEquals("78%", displayPercent)

		// VERIFICATION 2: Scrobble Link in ScrobblingDao
		val scrobbleEntity = database.getScrobblingDao().find(ScrobblerService.ANILIST.id, frierenManga.id)
		assertNotNull("Scrobble link must be saved in ScrobblingDao", scrobbleEntity)
		assertEquals(118586L, scrobbleEntity!!.targetId)
		assertEquals(119, scrobbleEntity.chapter)

		// VERIFICATION 3: Tokyo Ghoul Sequel Pair
		val tgHistory = database.getHistoryDao().find(tokyoGhoulManga.id)
		assertNotNull(tgHistory)
		assertEquals(1.0f, tgHistory!!.percent, 0.001f) // Completed -> 100%

		val tgReHistory = database.getHistoryDao().find(tokyoGhoulReManga.id)
		assertNotNull(tgReHistory)
		val tgReTarget = tokyoGhoulReManga.chapters!!.first { it.id == tgReHistory!!.chapterId }
		assertEquals(51f, tgReTarget.number, 0.001f) // Progress 50 -> next is 51

		// VERIFICATION 4: Title Already in Library
		val opScrobble = database.getScrobblingDao().find(ScrobblerService.ANILIST.id, preExistingManga.id)
		assertNotNull("Pre-existing manga must be linked to AniList", opScrobble)
		assertEquals(30013L, opScrobble!!.targetId)

		// VERIFICATION 5: Obscure Title
		val unobtainiumManga = database.getMangaDao().find(999999999L)
		assertNull("Unmatched unobtainium title must not be inserted", unobtainiumManga)

		// VERIFICATION 6: Planning title (Dandadan, progress = 0)
		val dandadanHistory = database.getHistoryDao().find(dandadanManga.id)
		assertNull("Planning title with progress 0 should not generate history", dandadanHistory)

		// VERIFICATION 7: Completed title (Goodbye, Eri)
		val eriHistory = database.getHistoryDao().find(goodbyeEriManga.id)
		assertNotNull(eriHistory)
		assertEquals(1.0f, eriHistory!!.percent, 0.001f)
	}

	@Test
	fun testSource140VsAniList152ChapterCountDiscrepancy() = runBlocking {
		database.getHistoryDao().clear()

		// AniList says 152 chapters, progress 119
		val entry = AniListMediaEntry(
			entryId = 8888,
			mediaId = 99999L,
			status = "CURRENT",
			progress = 119,
			score = 8.5f,
			notes = null,
			preferredTitle = "Discrepancy Manga",
			allTitles = listOf("Discrepancy Manga"),
			totalChapters = 152, // AniList count
			coverUrl = null,
			siteUrl = null,
			format = "MANGA",
			updatedAt = 1715000000000L,
		)

		// Source only has 140 chapters (1..140)
		val chapters140 = (1..140).map { num ->
			MangaChapter(
				id = 99999000L + num,
				title = "Chapter $num",
				number = num.toFloat(),
				volume = 0,
				url = "/manga/disc/ch$num",
				scanlator = null,
				uploadDate = 1700000000000L,
				branch = "default",
				source = UnknownMangaSource,
			)
		}
		val manga140 = Manga(
			id = 99999L,
			title = "Discrepancy Manga",
			altTitles = emptySet(),
			url = "/manga/disc",
			publicUrl = "https://example.com/manga/disc",
			rating = 0.9f,
			contentRating = ContentRating.SAFE,
			coverUrl = null,
			tags = emptySet(),
			state = MangaState.ONGOING,
			authors = setOf("Author"),
			chapters = chapters140,
			source = UnknownMangaSource,
		)

		val item = AniListImportItem(
			entry = entry,
			decision = ImportDecision.AUTO_ACCEPT,
			targetCategoryName = "Reading",
			matchedManga = manga140,
			isSelected = true,
		)
		val plan = AniListImportPlan(listOf(item))
		val applied = importUseCase.apply(plan) { _, _ -> }
		assertEquals(1, applied)

		val history = database.getHistoryDao().find(manga140.id)
		assertNotNull("History should be written", history)
		history!!

		// Ground truth comes from source (140), not AniList (152)
		assertEquals(140, history.chaptersCount)
		// Next chapter is Ch. 120 (index 119)
		assertEquals(99999000L + 120L, history.chapterId)
		// Percent is 119 / 140 = 0.85f (85.0%)
		val expectedPercent = 119f / 140f
		assertEquals(expectedPercent, history.percent, 0.001f)

		// Test estimateFromCounts directly when chapter IDs rotated / missing in fresh details
		val mangaWithRotatedChapters = manga140.copy(
			chapters = (1..140).map { num ->
				MangaChapter(
					id = 88888000L + num, // Different chapter IDs
					title = "Chapter $num",
					number = num.toFloat(),
					volume = 0,
					url = "/manga/disc/ch$num",
					scanlator = null,
					uploadDate = 1700000000000L,
					branch = "default",
					source = UnknownMangaSource,
				)
			},
		)
		// Invoking progressUpdateUseCase on mangaWithRotatedChapters calls estimateFromCounts:
		val estimated = progressUpdateUseCase(mangaWithRotatedChapters)
		assertEquals(expectedPercent, estimated, 0.001f)

		val historyAfter = database.getHistoryDao().find(manga140.id)
		assertNotNull(historyAfter)
		assertEquals(expectedPercent, historyAfter!!.percent, 0.001f)
		assertEquals(140, historyAfter.chaptersCount)
	}

	@Test
	fun testHeroRailStarvationWith20CompletedTitles() = runBlocking {
		database.getHistoryDao().clear()

		val baseTime = System.currentTimeMillis()
		// Insert 20 completed manga history rows (percent = 1.0f) with very recent timestamps
		for (i in 1..20) {
			val completedManga = createManga(70000L + i, "Completed Title $i", 1)
			mangaDataRepository.storeManga(completedManga, replaceExisting = true)
			val history = HistoryEntity(
				mangaId = completedManga.id,
				createdAt = baseTime + (i * 1000L),
				updatedAt = baseTime + (i * 1000L), // More recent than in-progress
				chapterId = (70000L + i) * 1000L + 1,
				page = 0,
				scroll = 0f,
				percent = 1.0f, // COMPLETED
				chaptersCount = 1,
				deletedAt = 0L,
			)
			database.getHistoryDao().upsert(history)
		}

		// Insert 5 in-progress manga history rows (percent = 0.5f) with older timestamps
		val inProgressIds = mutableListOf<Long>()
		for (i in 1..5) {
			val inProgressManga = createManga(80000L + i, "In Progress Title $i", 2)
			inProgressIds.add(inProgressManga.id)
			mangaDataRepository.storeManga(inProgressManga, replaceExisting = true)
			val history = HistoryEntity(
				mangaId = inProgressManga.id,
				createdAt = baseTime - ((10 - i) * 1000L),
				updatedAt = baseTime - ((10 - i) * 1000L), // Older than completed
				chapterId = (80000L + i) * 1000L + 1,
				page = 0,
				scroll = 0f,
				percent = 0.5f, // IN PROGRESS
				chaptersCount = 2,
				deletedAt = 0L,
			)
			database.getHistoryDao().upsert(history)
		}

		// Query historyRepository with maxPercent = 1.0f and limit = 10 (as used by FavouritesContainerViewModel)
		val items = historyRepository.observeAllWithHistory(
			order = ListSortOrder.LAST_READ,
			filterOptions = emptySet(),
			limit = 10,
			maxPercent = 1.0f,
		).first()

		// Verify: the 20 completed titles did NOT starve the rail! All 5 in-progress titles appear!
		assertEquals(5, items.size)
		val returnedIds = items.map { it.manga.id }.toSet()
		for (id in inProgressIds) {
			assertTrue("In-progress manga $id must appear on rail", returnedIds.contains(id))
		}
	}

	@Test
	fun testContinueReadingRailMatchesHistoryOrderIncludingCompleted() = runBlocking {
		database.getHistoryDao().clear()

		val baseTime = System.currentTimeMillis()
		val manga1 = createManga(90001L, "Damn Reincarnation", 100)
		val manga2 = createManga(90002L, "The Sword Emperor's Rise of Namgung", 50)
		val manga3 = createManga(90003L, "After Returning to Farming", 120)
		val manga4 = createManga(90004L, "I'm Gonna Annihilate This Land", 80)
		val manga5 = createManga(90005L, "Eat Before You Go", 200)

		listOf(manga1, manga2, manga3, manga4, manga5).forEach {
			mangaDataRepository.storeManga(it, replaceExisting = true)
		}

		val historyList = listOf(
			HistoryEntity(manga1.id, baseTime + 5000, baseTime + 5000, manga1.id * 1000 + 95, 0, 0f, 0.93f, 0L, 100),
			HistoryEntity(manga2.id, baseTime + 4000, baseTime + 4000, manga2.id * 1000 + 31, 0, 0f, 0.84f, 0L, 50),
			HistoryEntity(manga3.id, baseTime + 3000, baseTime + 3000, manga3.id * 1000 + 120, 0, 0f, 1.0f, 0L, 120),
			HistoryEntity(manga4.id, baseTime + 2000, baseTime + 2000, manga4.id * 1000 + 80, 0, 0f, 1.0f, 0L, 80),
			HistoryEntity(manga5.id, baseTime + 1000, baseTime + 1000, manga5.id * 1000 + 2, 0, 0f, 0.01f, 0L, 200),
		)
		historyList.forEach { database.getHistoryDao().upsert(it) }

		val continueReading = historyRepository.observeAllWithHistory(
			order = ListSortOrder.LAST_READ,
			filterOptions = emptySet(),
			limit = 15,
		).first()

		assertEquals(5, continueReading.size)
		assertEquals("Damn Reincarnation", continueReading[0].manga.title)
		assertEquals("The Sword Emperor's Rise of Namgung", continueReading[1].manga.title)
		assertEquals("After Returning to Farming", continueReading[2].manga.title)
		assertEquals(1.0f, continueReading[2].history.percent, 0.001f)
		assertEquals("I'm Gonna Annihilate This Land", continueReading[3].manga.title)
		assertEquals(1.0f, continueReading[3].history.percent, 0.001f)
		assertEquals("Eat Before You Go", continueReading[4].manga.title)
	}

	@Test
	fun testImportApplyTwiceIdempotency() = runBlocking {
		database.getHistoryDao().clear()

		val entry = AniListMediaEntry(
			entryId = 5555,
			mediaId = 66666L,
			status = "CURRENT",
			progress = 10,
			score = 9.0f,
			notes = "Great manga",
			preferredTitle = "Idempotent Manga",
			allTitles = listOf("Idempotent Manga"),
			totalChapters = 20,
			coverUrl = null,
			siteUrl = null,
			format = "MANGA",
			updatedAt = 1715000000000L,
		)
		val manga = createManga(66666L, "Idempotent Manga", 20)
		val item = AniListImportItem(
			entry = entry,
			decision = ImportDecision.AUTO_ACCEPT,
			targetCategoryName = "Reading",
			matchedManga = manga,
			isSelected = true,
		)
		val plan = AniListImportPlan(listOf(item))

		// Apply first time
		val count1 = importUseCase.apply(plan) { _, _ -> }
		assertEquals(1, count1)

		val history1 = database.getHistoryDao().find(manga.id)
		val favCount1 = database.getFavouritesDao().findAll().count { it.manga.id == manga.id }
		val scrobble1 = database.getScrobblingDao().find(ScrobblerService.ANILIST.id, manga.id)

		// Apply second time
		val count2 = importUseCase.apply(plan) { _, _ -> }
		assertEquals(1, count2)

		val history2 = database.getHistoryDao().find(manga.id)
		val favCount2 = database.getFavouritesDao().findAll().count { it.manga.id == manga.id }
		val scrobble2 = database.getScrobblingDao().find(ScrobblerService.ANILIST.id, manga.id)

		// Assert exact identical state with 0 duplicate rows
		assertEquals(1, favCount1)
		assertEquals(1, favCount2)
		assertEquals(history1?.percent, history2?.percent)
		assertEquals(history1?.chapterId, history2?.chapterId)
		assertEquals(scrobble1?.targetId, scrobble2?.targetId)
		assertEquals(scrobble1?.chapter, scrobble2?.chapter)
	}

	@Test
	fun testPlanSaveAndReloadCaching() = runBlocking {
		val entry = AniListMediaEntry(
			entryId = 7777,
			mediaId = 88888L,
			status = "CURRENT",
			progress = 5,
			score = 8.0f,
			notes = "Note",
			preferredTitle = "Cached Plan Manga",
			allTitles = listOf("Cached Plan Manga"),
			totalChapters = 10,
			coverUrl = "https://example.com/cover.jpg",
			siteUrl = "https://anilist.co/manga/88888",
			format = "MANGA",
			updatedAt = 1715000000000L,
			isAdult = false,
		)
		val manga = createManga(88888L, "Cached Plan Manga", 10)
		val item = AniListImportItem(
			entry = entry,
			decision = ImportDecision.AUTO_ACCEPT,
			targetCategoryName = "Reading",
			matchedManga = manga,
			isSelected = true,
		)
		val plan = AniListImportPlan(listOf(item))

		// Save plan to cache file
		importUseCase.savePlan(plan)

		// Reload cached plan
		val loadedPlan = importUseCase.loadCachedPlan()
		assertNotNull("Loaded plan must not be null", loadedPlan)
		assertEquals(1, loadedPlan!!.items.size)

		val loadedItem = loadedPlan.items.first()
		assertEquals(entry.mediaId, loadedItem.entry.mediaId)
		assertEquals(entry.preferredTitle, loadedItem.entry.preferredTitle)
		assertEquals(entry.progress, loadedItem.entry.progress)
		assertEquals(ImportDecision.AUTO_ACCEPT, loadedItem.decision)
		assertEquals("Reading", loadedItem.targetCategoryName)
		assertEquals(manga.id, loadedItem.matchedManga?.id)

		// Clean up
		importUseCase.clearCachedPlan()
		assertNull("Cache should be deleted after clear", importUseCase.loadCachedPlan())
	}
}

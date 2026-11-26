package org.koitharu.kotatsu.tracker.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteEntity

@RunWith(AndroidJUnit4::class)
class TrackingAnchorsDeviceTest {

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

	@Test
	fun parentMangaUpsert_doesNotWipeAnchor() = runBlocking {
		val mangaDao = db.getMangaDao()
		val anchorsDao = db.getTrackingAnchorsDao()

		val manga = createManga(1L, "Original Title")
		mangaDao.upsert(manga)

		val anchor = TrackingAnchorEntity.create(1L, "default").copy(
			lastReadNumber = 50f,
			latestNumberAtLastRead = 100f,
			newReleasesCount = 5,
		)
		anchorsDao.upsert(anchor)

		// Simulate user opening Details or refetching metadata: Manga is stored again
		val updatedManga = manga.copy(title = "Updated Title", coverUrl = "https://example.org/cover2.jpg")
		mangaDao.upsert(updatedManga)

		// Assert anchor survived completely intact (ON CONFLICT DO UPDATE does NOT trigger cascade)
		val storedAnchor = anchorsDao.find(1L)
		assertNotNull("Anchor must survive parent manga upsert", storedAnchor)
		assertEquals(50f, storedAnchor?.lastReadNumber)
		assertEquals(100f, storedAnchor?.latestNumberAtLastRead)
		assertEquals(5, storedAnchor?.newReleasesCount)
	}

	@Test
	fun storeMangaViaRepository_doesNotWipeAnchor() = runBlocking {
		val mangaDao = db.getMangaDao()
		val anchorsDao = db.getTrackingAnchorsDao()

		val manga = createManga(5L, "Repository Manga")
		mangaDao.upsert(manga)

		val anchor = TrackingAnchorEntity.create(5L, "default").copy(
			lastReadNumber = 20f,
			latestNumberAtLastRead = 40f,
			newReleasesCount = 3,
		)
		anchorsDao.upsert(anchor)

		// Re-store through DAO upsert simulating storeManga
		val updatedManga = manga.copy(title = "Updated via Repo")
		mangaDao.upsert(updatedManga)

		val storedAnchor = anchorsDao.find(5L)
		assertNotNull("Anchor must survive repository re-store", storedAnchor)
		assertEquals(20f, storedAnchor?.lastReadNumber)
		assertEquals(40f, storedAnchor?.latestNumberAtLastRead)
		assertEquals(3, storedAnchor?.newReleasesCount)
	}

	@Test
	fun cleanup_removesAnchorWhenMangaNotKept_andReaddingStartsClean() = runBlocking {
		val mangaDao = db.getMangaDao()
		val anchorsDao = db.getTrackingAnchorsDao()

		// Manga 6 has an anchor but is not in history or favourites
		val manga = createManga(6L, "Temporary Manga")
		mangaDao.upsert(manga)
		anchorsDao.upsert(
			TrackingAnchorEntity.create(6L).copy(
				lastReadNumber = 10f,
				latestNumberAtLastRead = 15f,
				newReleasesCount = 2,
			),
		)
		assertNotNull(anchorsDao.find(6L))

		// Cleanup job runs: manga 6 is deleted because it has no history/favourites
		mangaDao.cleanup(idsToKeep = emptySet())

		// Assert anchor is deleted with the manga
		assertNull("Anchor must be deleted when manga is cleaned up", anchorsDao.find(6L))

		// Re-adding the manga later starts clean (null-snapshot rule applies)
		mangaDao.upsert(manga)
		assertNull("Re-added manga must not have stale anchor", anchorsDao.find(6L))
	}

	@Test
	fun updateSnapshotForward_onlyAdvancesAndNeverRegresses() = runBlocking {
		val mangaDao = db.getMangaDao()
		val anchorsDao = db.getTrackingAnchorsDao()

		mangaDao.upsert(createManga(3L, "Forward Only"))
		val anchor = TrackingAnchorEntity.create(3L, "default").copy(
			latestVolumeAtLastRead = 0,
			latestNumberAtLastRead = 100f,
		)
		anchorsDao.upsert(anchor)

		// Attempt to regress snapshot to Ch. 10 (rereading)
		val regressedRows = anchorsDao.updateSnapshotForward(3L, volume = 0, number = 10f)
		assertEquals(0, regressedRows)
		assertEquals(100f, anchorsDao.find(3L)?.latestNumberAtLastRead)

		// Advance snapshot forward to Ch. 105
		val advancedRows = anchorsDao.updateSnapshotForward(3L, volume = 0, number = 105f)
		assertEquals(1, advancedRows)
		assertEquals(105f, anchorsDao.find(3L)?.latestNumberAtLastRead)
	}

	@Test
	fun dismissUntil_advancesSnapshotAndClearsNewReleasesCount() = runBlocking {
		val mangaDao = db.getMangaDao()
		val anchorsDao = db.getTrackingAnchorsDao()

		mangaDao.upsert(createManga(4L, "Dismiss Test"))
		val anchor = TrackingAnchorEntity.create(4L, "default").copy(
			latestVolumeAtLastRead = 0,
			latestNumberAtLastRead = 100f,
			newReleasesCount = 5,
		)
		anchorsDao.upsert(anchor)

		// Dismiss until Ch. 105
		anchorsDao.dismissUntil(4L, volume = 0, number = 105f)

		val updated = anchorsDao.find(4L)
		assertNotNull(updated)
		assertEquals(105f, updated?.latestNumberAtLastRead)
		assertEquals(0, updated?.newReleasesCount)
	}

	@Test
	fun railQuery_ordersByNewDetectedAtAndFiltersByCategories() = runBlocking {
		val mangaDao = db.getMangaDao()
		val anchorsDao = db.getTrackingAnchorsDao()
		val favouritesDao = db.getFavouritesDao()
		val categoriesDao = db.getFavouriteCategoriesDao()

		// Setup categories: 10 = "Reading", 20 = "Plan to read"
		categoriesDao.insert(createCategory(10, "Reading"))
		categoriesDao.insert(createCategory(20, "Plan to read"))

		// Manga 1 in Reading: 2 new releases, detected at 2000L
		mangaDao.upsert(createManga(101L, "Manga 1"))
		favouritesDao.insert(FavouriteEntity(101L, 10L, 0, false, 0L, 0L))
		anchorsDao.upsert(
			TrackingAnchorEntity.create(101L).copy(newReleasesCount = 2, newDetectedAt = 2000L),
		)

		// Manga 2 in Reading: 1 new release, detected at 3000L (newer detection)
		mangaDao.upsert(createManga(102L, "Manga 2"))
		favouritesDao.insert(FavouriteEntity(102L, 10L, 0, false, 0L, 0L))
		anchorsDao.upsert(
			TrackingAnchorEntity.create(102L).copy(newReleasesCount = 1, newDetectedAt = 3000L),
		)

		// Manga 3 in Plan to read: 10 new releases, detected at 4000L
		mangaDao.upsert(createManga(103L, "Manga 3"))
		favouritesDao.insert(FavouriteEntity(103L, 20L, 0, false, 0L, 0L))
		anchorsDao.upsert(
			TrackingAnchorEntity.create(103L).copy(newReleasesCount = 10, newDetectedAt = 4000L),
		)

		// Manga 4 in Reading: BROKEN source, 5 new releases
		mangaDao.upsert(createManga(104L, "Manga 4"))
		favouritesDao.insert(FavouriteEntity(104L, 10L, 0, false, 0L, 0L))
		anchorsDao.upsert(
			TrackingAnchorEntity.create(104L).copy(
				newReleasesCount = 5,
				newDetectedAt = 5000L,
				status = TrackingAnchorEntity.STATUS_BROKEN,
			),
		)

		// Manga 5 in Reading: NSFW manga, 3 new releases, detected at 6000L
		mangaDao.upsert(createManga(105L, "Manga 5", isNsfw = true))
		favouritesDao.insert(FavouriteEntity(105L, 10L, 0, false, 0L, 0L))
		anchorsDao.upsert(
			TrackingAnchorEntity.create(105L).copy(newReleasesCount = 3, newDetectedAt = 6000L),
		)

		// Query for "Reading" category only (categoryId = 10L) with includeNsfw = false:
		// Manga 4 (BROKEN) is excluded
		// Manga 5 (NSFW) is excluded
		// Manga 3 (different category) is excluded
		val railItems = anchorsDao.observeNewReleasesByCategories(listOf(10L), includeNsfw = false, limit = 10).first()

		// Must return exactly 2 items, sorted by new_detected_at DESC (Manga 2 first, Manga 1 second)
		assertEquals(2, railItems.size)
		assertEquals(102L, railItems[0].anchor.mangaId)
		assertEquals("Manga 2", railItems[0].manga.title)
		assertEquals(101L, railItems[1].anchor.mangaId)
		assertEquals("Manga 1", railItems[1].manga.title)

		// Query with includeNsfw = true: Manga 5 is included
		val railItemsWithNsfw = anchorsDao.observeNewReleasesByCategories(listOf(10L), includeNsfw = true, limit = 10).first()
		assertEquals(3, railItemsWithNsfw.size)
		assertEquals(105L, railItemsWithNsfw[0].anchor.mangaId)
		assertEquals(102L, railItemsWithNsfw[1].anchor.mangaId)
		assertEquals(101L, railItemsWithNsfw[2].anchor.mangaId)
	}

	private fun createManga(id: Long, title: String, isNsfw: Boolean = false) = MangaEntity(
		id = id,
		title = title,
		altTitles = null,
		url = "/manga/$id",
		publicUrl = "https://example.org/manga/$id",
		rating = -1f,
		isNsfw = isNsfw,
		contentRating = null,
		coverUrl = "https://example.org/cover/$id.jpg",
		largeCoverUrl = null,
		state = null,
		authors = null,
		source = "TEST",
	)

	private fun createCategory(id: Int, title: String) = FavouriteCategoryEntity(
		categoryId = id,
		createdAt = 0L,
		sortKey = 0,
		title = title,
		order = "MANUAL",
		track = true,
		isVisibleInLibrary = true,
		deletedAt = 0L,
	)
}

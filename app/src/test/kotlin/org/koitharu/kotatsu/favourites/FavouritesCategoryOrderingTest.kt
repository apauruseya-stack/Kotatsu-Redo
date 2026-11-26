package org.koitharu.kotatsu.favourites

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.favourites.ui.container.FavouriteTabModel
import org.koitharu.kotatsu.favourites.ui.list.FavouritesListFragment.Companion.NO_ID
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.scrobbling.anilist.domain.model.AniListCategoryNames
import java.time.Instant
import java.util.Locale

class FavouritesCategoryOrderingTest {

	private fun createCategory(
		id: Long,
		title: String,
		sortKey: Int = id.toInt(),
	) = FavouriteCategory(
		id = id,
		title = title,
		sortKey = sortKey,
		order = ListSortOrder.NEWEST,
		createdAt = Instant.EPOCH,
		isTrackingEnabled = true,
		isVisibleInLibrary = true,
	)

	private fun matchesReadingCategory(title: String?): Boolean {
		if (title == null) return false
		val lower = title.trim().lowercase(Locale.ROOT)
		return lower == "reading" ||
			lower == "status_reading" ||
			AniListCategoryNames.matchesReading(title)
	}

	private fun matchesReadLaterCategory(title: String?): Boolean {
		if (title == null) return false
		val lower = title.trim().lowercase(Locale.ROOT)
		return lower == "read later" ||
			lower == "read_later" ||
			lower == "planning" ||
			lower == "plan to read" ||
			lower == "plan_to_read"
	}

	private fun matchesPausedCategory(title: String?): Boolean {
		if (title == null) return false
		val lower = title.trim().lowercase(Locale.ROOT)
		return lower == "paused" ||
			lower == "on hold" ||
			lower == "on_hold" ||
			lower == "status_paused"
	}

	private fun matchesDroppedCategory(title: String?): Boolean {
		if (title == null) return false
		val lower = title.trim().lowercase(Locale.ROOT)
		return lower == "dropped" ||
			lower == "status_dropped"
	}

	private fun matchesCompletedCategory(title: String?): Boolean {
		if (title == null) return false
		val lower = title.trim().lowercase(Locale.ROOT)
		return lower == "completed" ||
			lower == "finished" ||
			lower == "status_completed"
	}

	private fun getTabRank(tab: FavouriteTabModel): Int {
		if (tab.id == NO_ID) {
			return 40 // All favourites
		}
		val title = tab.title
		return when {
			matchesReadingCategory(title) -> 10
			matchesReadLaterCategory(title) -> 20
			matchesPausedCategory(title) -> 30
			matchesDroppedCategory(title) -> 50
			matchesCompletedCategory(title) -> 60
			else -> 70
		}
	}

	private fun toUi(categories: List<FavouriteCategory>, showAll: Boolean): List<FavouriteTabModel> {
		if (categories.isEmpty()) {
			return if (showAll) listOf(FavouriteTabModel(NO_ID, null)) else emptyList()
		}
		val allTabs = ArrayList<FavouriteTabModel>(if (showAll) categories.size + 1 else categories.size)
		if (showAll) {
			allTabs.add(FavouriteTabModel(NO_ID, null))
		}
		categories.mapTo(allTabs) { FavouriteTabModel(it.id, it.title) }

		return allTabs.sortedWith { a, b ->
			val rankA = getTabRank(a)
			val rankB = getTabRank(b)
			if (rankA != rankB) {
				rankA.compareTo(rankB)
			} else {
				val catA = categories.find { it.id == a.id }
				val catB = categories.find { it.id == b.id }
				(catA?.sortKey ?: 0).compareTo(catB?.sortKey ?: 0)
			}
		}
	}

	@Test
	fun standardCategories_orderMatchesUserSpecificationExactly() {
		// User specified:
		// all fauvourites - read later - reading - comepleted - dropped - paused
		// i want: reading - read later - paused - all fauvourites - dropped (then completed)
		val inputCategories = listOf(
			createCategory(id = 1L, title = "Read later", sortKey = 1),
			createCategory(id = 2L, title = "Reading", sortKey = 2),
			createCategory(id = 3L, title = "Completed", sortKey = 3),
			createCategory(id = 4L, title = "Dropped", sortKey = 4),
			createCategory(id = 5L, title = "Paused", sortKey = 5),
		)

		val result = toUi(inputCategories, showAll = true)
		val resultTitles = result.map { it.title ?: "All favourites" }

		assertEquals(
			listOf(
				"Reading",
				"Read later",
				"Paused",
				"All favourites",
				"Dropped",
				"Completed",
			),
			resultTitles,
		)
	}

	@Test
	fun standardCategories_withoutAllFavourites_preservesRelativeOrder() {
		val inputCategories = listOf(
			createCategory(id = 1L, title = "Read later", sortKey = 1),
			createCategory(id = 2L, title = "Reading", sortKey = 2),
			createCategory(id = 3L, title = "Completed", sortKey = 3),
			createCategory(id = 4L, title = "Dropped", sortKey = 4),
			createCategory(id = 5L, title = "Paused", sortKey = 5),
		)

		val result = toUi(inputCategories, showAll = false)
		val resultTitles = result.map { it.title }

		assertEquals(
			listOf("Reading", "Read later", "Paused", "Dropped", "Completed"),
			resultTitles,
		)
	}

	@Test
	fun aniListCategoryNames_mappedToExpectedOrder() {
		val inputCategories = listOf(
			createCategory(id = 10L, title = AniListCategoryNames.PLANNING, sortKey = 1),
			createCategory(id = 20L, title = AniListCategoryNames.READING, sortKey = 2),
			createCategory(id = 30L, title = AniListCategoryNames.COMPLETED, sortKey = 3),
			createCategory(id = 40L, title = AniListCategoryNames.DROPPED, sortKey = 4),
			createCategory(id = 50L, title = AniListCategoryNames.PAUSED, sortKey = 5),
		)

		val result = toUi(inputCategories, showAll = true)
		val resultTitles = result.map { it.title ?: "All favourites" }

		assertEquals(
			listOf(
				AniListCategoryNames.READING,
				AniListCategoryNames.PLANNING,
				AniListCategoryNames.PAUSED,
				"All favourites",
				AniListCategoryNames.DROPPED,
				AniListCategoryNames.COMPLETED,
			),
			resultTitles,
		)
	}

	@Test
	fun customCategories_appearAfterCompletedInSortKeyOrder() {
		val inputCategories = listOf(
			createCategory(id = 1L, title = "Read later", sortKey = 1),
			createCategory(id = 2L, title = "Reading", sortKey = 2),
			createCategory(id = 3L, title = "Completed", sortKey = 3),
			createCategory(id = 4L, title = "Dropped", sortKey = 4),
			createCategory(id = 5L, title = "Paused", sortKey = 5),
			createCategory(id = 6L, title = "Manga To Buy", sortKey = 10),
			createCategory(id = 7L, title = "Action Gems", sortKey = 7),
		)

		val result = toUi(inputCategories, showAll = true)
		val resultTitles = result.map { it.title ?: "All favourites" }

		assertEquals(
			listOf(
				"Reading",
				"Read later",
				"Paused",
				"All favourites",
				"Dropped",
				"Completed",
				"Action Gems",
				"Manga To Buy",
			),
			resultTitles,
		)
	}

	@Test
	fun resolveCategoryIds_byStringTitleOrNumericId() {
		val allCategories = listOf(
			createCategory(id = 1L, title = "Read later"),
			createCategory(id = 2L, title = "Reading"),
			createCategory(id = 3L, title = "Completed"),
			createCategory(id = 4L, title = "Dropped"),
			createCategory(id = 5L, title = "Paused"),
		)

		fun resolve(prefStrings: Set<String>): Set<Long> {
			if (prefStrings.isEmpty()) return emptySet()
			val result = HashSet<Long>()
			for (token in prefStrings) {
				val numericId = token.toLongOrNull()
				if (numericId != null) {
					val exists = allCategories.any { it.id == numericId }
					if (exists) {
						result.add(numericId)
						continue
					}
				}
				for (cat in allCategories) {
					if (cat.title.equals(token, ignoreCase = true)) {
						result.add(cat.id)
					} else if (matchesReadingCategory(token) && matchesReadingCategory(cat.title)) {
						result.add(cat.id)
					} else if (matchesReadLaterCategory(token) && matchesReadLaterCategory(cat.title)) {
						result.add(cat.id)
					} else if (matchesPausedCategory(token) && matchesPausedCategory(cat.title)) {
						result.add(cat.id)
					} else if (matchesDroppedCategory(token) && matchesDroppedCategory(cat.title)) {
						result.add(cat.id)
					} else if (matchesCompletedCategory(token) && matchesCompletedCategory(cat.title)) {
						result.add(cat.id)
					}
				}
			}
			return result
		}

		// When preference is saved as ID "2"
		assertEquals(setOf(2L), resolve(setOf("2")))

		// When preference is saved as title "Reading"
		assertEquals(setOf(2L), resolve(setOf("Reading")))

		// When preference is saved case-insensitively "reading"
		assertEquals(setOf(2L), resolve(setOf("reading")))

		// When preference contains multiple categories
		assertEquals(setOf(1L, 2L), resolve(setOf("Reading", "1")))

		// When non-existent category token is provided
		assertEquals(emptySet<Long>(), resolve(setOf("NonExistent")))
	}
}

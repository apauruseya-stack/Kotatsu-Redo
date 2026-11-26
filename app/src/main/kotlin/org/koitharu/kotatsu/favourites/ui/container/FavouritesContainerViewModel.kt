package org.koitharu.kotatsu.favourites.ui.container

import android.content.Context
import android.text.format.DateUtils
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.ui.util.ReversibleHandle
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.favourites.ui.list.FavouritesListFragment.Companion.NO_ID
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.scrobbling.anilist.domain.model.AniListCategoryNames
import org.koitharu.kotatsu.tracker.data.toManga
import org.koitharu.kotatsu.tracker.domain.ForegroundTrackingRefresher
import org.koitharu.kotatsu.tracker.domain.TrackingRepository
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class FavouritesContainerViewModel @Inject constructor(
	val settings: AppSettings,
	private val favouritesRepository: FavouritesRepository,
	private val historyRepository: HistoryRepository,
	private val mangaDataRepository: MangaDataRepository,
	private val trackingRepository: TrackingRepository,
	private val foregroundTrackingRefresher: ForegroundTrackingRefresher,
	@ApplicationContext private val context: Context,
) : BaseViewModel() {

	init {
		launchJob(Dispatchers.IO) {
			trackingRepository.syncTracksToAnchors()
			foregroundTrackingRefresher.triggerAutoRefresh(delayMs = 2500L)
		}
	}

	val onActionDone = MutableEventFlow<ReversibleAction>()

	val isRefreshing = foregroundTrackingRefresher.isRefreshing

	private val categoriesStateFlow = favouritesRepository.observeCategoriesForLibrary()
		.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	val categories = combine(
		categoriesStateFlow.filterNotNull(),
		observeAllFavouritesVisibility(),
	) { list, showAll ->
		list.toUi(showAll)
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	val isEmpty = categoriesStateFlow.map {
		it?.isEmpty() == true
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

	private data class RailFilterConfig(
		val isLiquidGlass: Boolean,
		val includeNsfw: Boolean,
		val categoryIds: Set<Long>?,
		val continueIds: Set<Long>,
	)

	val continueReadingItems = settings.observeAsFlow(AppSettings.KEY_LIQUID_GLASS) { isLiquidGlass }
		.flatMapLatest { isLiquidGlass ->
			if (!isLiquidGlass) {
				flowOf(emptyList())
			} else {
				historyRepository.observeAllWithHistory(
					order = ListSortOrder.LAST_READ,
					filterOptions = emptySet(),
					limit = 15,
				).map { list ->
					withContext(Dispatchers.IO) {
						list.map { item ->
							val chapter = mangaDataRepository.findCachedChapter(item.manga.id, item.history.chapterId)
								?: item.manga.chapters?.firstOrNull { it.id == item.history.chapterId }
							val newCount = trackingRepository.getNewChaptersCount(item.manga.id)
							ContinueReadingItem(item, chapter, newChaptersCount = newCount)
						}
					}
				}
			}
		}.stateIn(
			viewModelScope + Dispatchers.Default,
			SharingStarted.WhileSubscribed(5000),
			emptyList(),
		)

	@OptIn(kotlinx.coroutines.FlowPreview::class)
	val newChaptersItems = combine(
		settings.observeAsFlow(AppSettings.KEY_LIQUID_GLASS) { isLiquidGlass },
		settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) { isNsfwContentDisabled },
		settings.observeAsFlow(AppSettings.KEY_NEW_CHAPTERS_CATEGORIES) {
			if (isNewChaptersRailCategoriesConfigured) newChaptersRailCategories else null
		},
		categoriesStateFlow.filterNotNull(),
		continueReadingItems,
	) { isLiquidGlass, isNsfwDisabled, prefCategoryStrings, allCategories, continueItems ->
		val continueIds = continueItems.mapTo(HashSet()) { it.mangaWithHistory.manga.id }
		val resolvedCategoryIds: Set<Long>? = if (prefCategoryStrings == null) {
			null
		} else {
			resolveCategoryIds(prefCategoryStrings, allCategories)
		}
		RailFilterConfig(
			isLiquidGlass = isLiquidGlass,
			includeNsfw = !isNsfwDisabled,
			categoryIds = resolvedCategoryIds,
			continueIds = continueIds,
		)
	}.flatMapLatest { config ->
		if (!config.isLiquidGlass) {
			flowOf(emptyList())
		} else if (config.categoryIds != null && config.categoryIds.isEmpty()) {
			flowOf(emptyList())
		} else {
			trackingRepository.observeNewChaptersRail(
				categoryIds = config.categoryIds,
				includeNsfw = config.includeNsfw,
				limit = 20,
			).map { list ->
				withContext(Dispatchers.IO) {
					val now = System.currentTimeMillis()
					list.filterNot { it.anchor.mangaId in config.continueIds }
						.map { item ->
							val anchor = item.anchor
							val latestNum = anchor.latestNumberAtLastRead
							val lastRead = anchor.lastReadNumber
							val numStr = if (latestNum != null && latestNum > 0f) {
								if (lastRead != null && anchor.newReleasesCount > 1 && latestNum > lastRead) {
									val startNum = (lastRead + 1f).coerceAtMost(latestNum)
									val formatNum: (Float) -> String = { n -> if (n % 1f == 0f) n.toInt().toString() else n.toString() }
									if (startNum < latestNum) {
										"Ch. ${formatNum(startNum)}–${formatNum(latestNum)}"
									} else {
										"Ch. ${formatNum(latestNum)}"
									}
								} else {
									val formatted = if (latestNum % 1f == 0f) latestNum.toInt().toString() else latestNum.toString()
									"Ch. $formatted"
								}
							} else {
								null
							}
							val timeStr = if (anchor.newDetectedAt > 0L) {
								DateUtils.getRelativeTimeSpanString(
									anchor.newDetectedAt,
									now,
									DateUtils.MINUTE_IN_MILLIS,
									DateUtils.FORMAT_ABBREV_RELATIVE,
								).toString()
							} else {
								null
							}
							val subtitle = when {
								numStr != null && timeStr != null -> "$numStr  •  $timeStr"
								numStr != null -> numStr
								timeStr != null -> timeStr
								else -> null
							}
							val isNewToday = anchor.newDetectedAt > 0L && (now - anchor.newDetectedAt) < 24 * 3600 * 1000L
							NewChapterRailItem(
								manga = item.toManga(),
								anchor = anchor,
								subtitle = subtitle,
								isNewToday = isNewToday,
							)
						}
				}
			}
		}
	}.debounce(300L)
	.stateIn(
		viewModelScope + Dispatchers.Default,
		SharingStarted.WhileSubscribed(5000),
		emptyList(),
	)

	private fun resolveCategoryIds(
		prefStrings: Set<String>,
		allCategories: List<FavouriteCategory>,
	): Set<Long> {
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

	suspend fun getFirstUnreadChapter(manga: org.koitharu.kotatsu.parsers.model.Manga): MangaChapter? = withContext(Dispatchers.IO) {
		val cached = trackingRepository.getCachedChapters(manga.id)
		if (cached.isEmpty()) return@withContext null
		val history = historyRepository.getOne(manga)
		if (history == null) {
			return@withContext cached.firstOrNull()
		}
		val currentChapter = cached.find { it.id == history.chapterId }
		if (currentChapter == null) {
			return@withContext cached.firstOrNull()
		}
		val branchChapters = cached.filter { it.branch == currentChapter.branch || it.branch == null }
		val currentIndex = branchChapters.indexOfFirst { it.id == currentChapter.id }
		if (currentIndex in 0 until branchChapters.lastIndex) {
			branchChapters[currentIndex + 1]
		} else {
			currentChapter
		}
	}

	fun dismissNewRelease(mangaId: Long, volume: Int?, number: Float?) {
		launchJob(Dispatchers.IO) {
			trackingRepository.dismissNewReleases(mangaId, volume, number)
		}
	}

	fun muteManga(mangaId: Long) {
		launchJob(Dispatchers.IO) {
			trackingRepository.muteManga(mangaId)
		}
	}

	fun refreshTracker(force: Boolean = false) {
		launchJob(Dispatchers.IO) {
			trackingRepository.syncTracksToAnchors()
		}
		foregroundTrackingRefresher.triggerRefresh(force)
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

	private fun List<FavouriteCategory>.toUi(showAll: Boolean): List<FavouriteTabModel> {
		if (isEmpty()) {
			return if (showAll) listOf(FavouriteTabModel(NO_ID, null)) else emptyList()
		}
		val allTabs = ArrayList<FavouriteTabModel>(if (showAll) size + 1 else size)
		if (showAll) {
			allTabs.add(FavouriteTabModel(NO_ID, null))
		}
		mapTo(allTabs) { FavouriteTabModel(it.id, it.title) }

		return allTabs.sortedWith { a, b ->
			val rankA = getTabRank(a)
			val rankB = getTabRank(b)
			if (rankA != rankB) {
				rankA.compareTo(rankB)
			} else {
				val catA = find { it.id == a.id }
				val catB = find { it.id == b.id }
				(catA?.sortKey ?: 0).compareTo(catB?.sortKey ?: 0)
			}
		}
	}

	private fun matchesReadingCategory(title: String?): Boolean {
		if (title == null) return false
		val lower = title.trim().lowercase(Locale.ROOT)
		return lower == "reading" ||
			lower == "status_reading" ||
			AniListCategoryNames.matchesReading(title) ||
			runCatching { context.getString(R.string.status_reading) }.getOrNull()?.equals(title, ignoreCase = true) == true
	}

	private fun matchesReadLaterCategory(title: String?): Boolean {
		if (title == null) return false
		val lower = title.trim().lowercase(Locale.ROOT)
		return lower == "read later" ||
			lower == "read_later" ||
			lower == "planning" ||
			lower == "plan to read" ||
			lower == "plan_to_read" ||
			runCatching { context.getString(R.string.read_later) }.getOrNull()?.equals(title, ignoreCase = true) == true
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
			lower == "status_dropped" ||
			runCatching { context.getString(R.string.status_dropped) }.getOrNull()?.equals(title, ignoreCase = true) == true
	}

	private fun matchesCompletedCategory(title: String?): Boolean {
		if (title == null) return false
		val lower = title.trim().lowercase(Locale.ROOT)
		return lower == "completed" ||
			lower == "finished" ||
			lower == "status_completed" ||
			runCatching { context.getString(R.string.status_completed) }.getOrNull()?.equals(title, ignoreCase = true) == true
	}

	fun hide(categoryId: Long) {
		launchJob(Dispatchers.Default) {
			if (categoryId == NO_ID) {
				settings.isAllFavouritesVisible = false
			} else {
				favouritesRepository.updateCategory(categoryId, isVisibleInLibrary = false)
				val reverse = ReversibleHandle {
					favouritesRepository.updateCategory(categoryId, isVisibleInLibrary = true)
				}
				onActionDone.call(ReversibleAction(R.string.category_hidden_done, reverse))
			}
		}
	}

	fun deleteCategory(categoryId: Long) {
		launchJob(Dispatchers.Default) {
			favouritesRepository.removeCategories(setOf(categoryId))
		}
	}

	private fun observeAllFavouritesVisibility() = settings.observeAsFlow(
		key = AppSettings.KEY_ALL_FAVOURITES_VISIBLE,
		valueProducer = { isAllFavouritesVisible },
	)
}

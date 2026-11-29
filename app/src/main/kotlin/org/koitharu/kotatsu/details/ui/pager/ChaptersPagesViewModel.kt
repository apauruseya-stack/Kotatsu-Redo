package org.koitharu.kotatsu.details.ui.pager

import android.app.Activity
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import okio.FileNotFoundException
import org.koitharu.kotatsu.bookmarks.domain.BookmarksRepository
import org.koitharu.kotatsu.core.model.toChipModel
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsStateFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.core.util.LocaleStringComparator
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.combine
import org.koitharu.kotatsu.core.util.ext.requireValue
import org.koitharu.kotatsu.core.util.ext.sortedWithSafe
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.domain.DetailsInteractor
import org.koitharu.kotatsu.details.domain.ScanlatorChapterMerger
import org.koitharu.kotatsu.details.domain.ScanlatorPriorities
import org.koitharu.kotatsu.details.ui.DetailsActivity
import org.koitharu.kotatsu.details.ui.DetailsViewModel
import org.koitharu.kotatsu.details.ui.mapChapters
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.download.ui.worker.DownloadTask
import org.koitharu.kotatsu.download.ui.worker.DownloadWorker
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.local.domain.DeleteLocalMangaUseCase
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.reader.ui.ReaderActivity
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.reader.ui.ReaderViewModel

abstract class ChaptersPagesViewModel(
	@JvmField val settings: AppSettings,
	@JvmField protected val interactor: DetailsInteractor,
	private val bookmarksRepository: BookmarksRepository,
	private val historyRepository: HistoryRepository,
	private val downloadScheduler: DownloadWorker.Scheduler,
	private val deleteLocalMangaUseCase: DeleteLocalMangaUseCase,
	private val localStorageChanges: SharedFlow<LocalManga?>,
) : BaseViewModel() {

	val mangaDetails = MutableStateFlow<MangaDetails?>(null)
	val readingState = MutableStateFlow<ReaderState?>(null)

	val onActionDone = MutableEventFlow<ReversibleAction>()
	val onDownloadStarted = MutableEventFlow<Unit>()
	val onMangaRemoved = MutableEventFlow<Manga>()
	val scanlatorPromptEvent = MutableEventFlow<ScanlatorPromptData>()

	private val chaptersQuery = MutableStateFlow("")
	val selectedBranch = MutableStateFlow<String?>(null)
	val selectedScanlator = MutableStateFlow<String?>(null)
	val preferredScanlator = MutableStateFlow<String?>(null)

	val manga = mangaDetails.map { x -> x?.toManga() }
		.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	val coverUrl = mangaDetails.map { x -> x?.coverUrl }
		.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	val isChaptersReversed = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_REVERSE_CHAPTERS,
		valueProducer = { isChaptersReverse },
	)

	val isChaptersInGridView = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_GRID_VIEW_CHAPTERS,
		valueProducer = { isChaptersGridView },
	)

	val isDownloadedOnly = MutableStateFlow(false)

	val newChaptersCount = mangaDetails.flatMapLatest { d ->
		if (d?.isLocal == false) {
			interactor.observeNewChapters(d.id)
		} else {
			flowOf(0)
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, 0)

	val emptyReason: StateFlow<EmptyMangaReason?> = combine(
		mangaDetails,
		isLoading,
		onError.onStart { emit(null) },
	) { details, loading, error ->
		when {
			details == null || loading -> null
			details.chapters.isNotEmpty() -> null
			details.toManga().state == MangaState.RESTRICTED -> EmptyMangaReason.RESTRICTED
			error != null -> EmptyMangaReason.LOADING_ERROR
			else -> EmptyMangaReason.NO_CHAPTERS
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.WhileSubscribed(), null)

	val bookmarks = mangaDetails.flatMapLatest {
		if (it != null) {
			bookmarksRepository.observeBookmarks(it.toManga()).withErrorHandling()
		} else {
			flowOf(emptyList())
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, emptyList())

	private data class ChapterFilterParams(
		val branch: String?,
		val selectedScanlator: String?,
		val preferredScanlator: String?,
		val isDownloadedOnly: Boolean,
		val isGrid: Boolean,
	)

	private val chapterFilterParams = combine(
		selectedBranch,
		selectedScanlator,
		preferredScanlator,
		isDownloadedOnly,
		isChaptersInGridView,
	) { branch, scanlator, preferred, downloadedOnly, grid ->
		ChapterFilterParams(branch, scanlator, preferred, downloadedOnly, grid)
	}

	val chapters = combine(
		combine(
			mangaDetails,
			readingState.map { it?.chapterId ?: 0L }.distinctUntilChanged(),
			newChaptersCount,
			bookmarks,
			chapterFilterParams,
		) { manga, currentChapterId, news, bookmarks, filter ->
			manga?.mapChapters(
				currentChapterId = currentChapterId,
				newCount = news,
				branch = filter.branch,
				bookmarks = bookmarks,
				isGrid = filter.isGrid,
				isDownloadedOnly = filter.isDownloadedOnly,
				selectedScanlator = filter.selectedScanlator,
				preferredScanlator = filter.preferredScanlator,
			).orEmpty()
		},
		isChaptersReversed,
		chaptersQuery,
	) { list, reversed, query ->
		(if (reversed) list.asReversed() else list).filterSearch(query)
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	val quickFilter = combine(
		mangaDetails,
		selectedBranch,
		selectedScanlator,
		preferredScanlator,
	) { details, branch, currentScanlator, preferred ->
		val result = ArrayList<ChipsView.ChipModel>()
		val allChapters = details?.allChapters.orEmpty()
		if (allChapters.isEmpty()) {
			return@combine result
		}

		val distinctBranches = details?.chapters?.keys?.filterNotNull()?.filter { it.isNotBlank() }.orEmpty()
		val distinctScanlators = allChapters.mapNotNull { it.scanlator?.trim() }.filter { it.isNotBlank() }.distinct()

		val isBranchGroup = distinctBranches.size > 1
		val distinctGroups = if (isBranchGroup) distinctBranches else distinctScanlators

		if (distinctGroups.size > 1) {
			val effectivePreferred = preferred
				?: ScanlatorPriorities.pickBestDefault(distinctGroups, allChapters)
				?: distinctGroups.first()

			val mergedAllCount = ScanlatorChapterMerger.mergeChapters(
				chapters = allChapters,
				selectedScanlator = null,
				preferredScanlator = effectivePreferred,
				useBranch = isBranchGroup,
			).size

			val currentGroup = if (isBranchGroup) branch else currentScanlator
			val isAllSelected = currentGroup.isNullOrEmpty() || currentGroup.equals("ALL", ignoreCase = true)

			val allOption = ListFilterOption.Scanlator(
				titleText = null,
				chaptersCount = mergedAllCount,
				isAll = true,
			)
			result.add(allOption.toChipModel(isChecked = isAllSelected))

			val groupCounts = if (isBranchGroup) {
				details?.chapters?.mapValues { it.value.size }.orEmpty()
			} else {
				allChapters.groupingBy { it.scanlator?.trim().orEmpty() }.eachCount()
			}

			val sortedGroups = distinctGroups.sortedWith(
				compareBy<String> {
					if (it.equals(effectivePreferred, ignoreCase = true)) 0 else 1
				}.thenBy {
					ScanlatorPriorities.getPriority(it)
				}.thenByDescending {
					groupCounts[it] ?: 0
				}.thenBy(LocaleStringComparator()) { it }
			)

			for (g in sortedGroups) {
				val isSelected = !isAllSelected && currentGroup.equals(g, ignoreCase = true)
				val option = if (isBranchGroup) {
					ListFilterOption.Branch(
						titleText = g,
						chaptersCount = groupCounts[g] ?: 0,
					)
				} else {
					ListFilterOption.Scanlator(
						titleText = g,
						chaptersCount = groupCounts[g] ?: 0,
						isAll = false,
					)
				}
				result.add(option.toChipModel(isChecked = isSelected))
			}
		}

		result
	}

	private var hasPromptedScanlators = false

	private fun checkAndPromptScanlators(details: MangaDetails) {
		val distinctBranches = details.chapters.keys.filterNotNull().filter { it.isNotBlank() }
		val distinctScanlators = details.allChapters.mapNotNull { it.scanlator?.trim() }.filter { it.isNotBlank() }.distinct()
		val distinctGroups = if (distinctBranches.size > 1) distinctBranches else distinctScanlators
		if (distinctGroups.size <= 1) return

		val mangaId = details.id
		if (settings.hasPreferredScanlator(mangaId)) {
			preferredScanlator.value = settings.getPreferredScanlator(mangaId)
			return
		}

		val bestDefault = ScanlatorPriorities.pickBestDefault(distinctGroups, details.allChapters)
			?: distinctGroups.first()
		preferredScanlator.value = bestDefault

		if (!hasPromptedScanlators) {
			hasPromptedScanlators = true
			val sortedScanlators = distinctGroups.sortedWith(
				compareBy<String> { ScanlatorPriorities.getPriority(it) }
					.thenBy(LocaleStringComparator()) { it }
			)
			scanlatorPromptEvent.call(
				ScanlatorPromptData(
					mangaId = mangaId,
					scanlators = sortedScanlators,
					defaultSelection = bestDefault,
				)
			)
		}
	}

	init {
		launchJob(Dispatchers.Default) {
			localStorageChanges
				.collect { onDownloadComplete(it) }
		}
		launchJob(Dispatchers.Default) {
			mangaDetails.collect { details ->
				if (details != null && details.allChapters.isNotEmpty()) {
					checkAndPromptScanlators(details)
				}
			}
		}
	}

	fun requestScanlatorSelection() {
		val details = mangaDetails.value ?: return
		val distinctBranches = details.chapters.keys.filterNotNull().filter { it.isNotBlank() }
		val distinctScanlators = details.allChapters.mapNotNull { it.scanlator?.trim() }.filter { it.isNotBlank() }.distinct()
		val distinctGroups = if (distinctBranches.size > 1) distinctBranches else distinctScanlators
		if (distinctGroups.size <= 1) return

		val currentPreferred = preferredScanlator.value
			?: ScanlatorPriorities.pickBestDefault(distinctGroups, details.allChapters)
			?: distinctGroups.first()

		val sortedScanlators = distinctGroups.sortedWith(
			compareBy<String> { ScanlatorPriorities.getPriority(it) }
				.thenBy(LocaleStringComparator()) { it }
		)

		scanlatorPromptEvent.call(
			ScanlatorPromptData(
				mangaId = details.id,
				scanlators = sortedScanlators,
				defaultSelection = currentPreferred,
			)
		)
	}

	fun setChaptersReversed(newValue: Boolean) {
		settings.isChaptersReverse = newValue
	}

	fun setChaptersInGridView(newValue: Boolean) {
		settings.isChaptersGridView = newValue
	}

	fun setSelectedBranch(branch: String?) {
		selectedBranch.value = branch
		selectedScanlator.value = null
	}

	fun setSelectedScanlator(scanlator: String?) {
		selectedScanlator.value = scanlator
	}

	fun setPreferredScanlator(scanlator: String?) {
		preferredScanlator.value = scanlator
		val mangaId = mangaDetails.value?.id ?: return
		settings.setPreferredScanlator(mangaId, scanlator)
	}

	data class ScanlatorPromptData(
		val mangaId: Long,
		val scanlators: List<String>,
		val defaultSelection: String,
	)

	fun performChapterSearch(query: String?) {
		chaptersQuery.value = query?.trim().orEmpty()
	}

	fun getMangaOrNull(): Manga? = mangaDetails.value?.toManga()

	fun requireManga() = mangaDetails.requireValue().toManga()

	fun markChapterAsCurrent(chapterId: Long) {
		launchJob(Dispatchers.Default) {
			val manga = mangaDetails.requireValue()
			val chapters = checkNotNull(manga.chapters[selectedBranch.value])
			val chapterIndex = chapters.indexOfFirst { it.id == chapterId }
			check(chapterIndex in chapters.indices) { "Chapter not found" }
			val percent = chapterIndex / chapters.size.toFloat()
			historyRepository.addOrUpdate(
				manga = manga.toManga(),
				chapterId = chapterId,
				page = 0,
				scroll = 0,
				percent = percent,
				force = true,
			)
		}
	}

	fun download(chaptersIds: Set<Long>?, allowMeteredNetwork: Boolean) {
		launchJob(Dispatchers.Default) {
			val manga = requireManga()
			val task = DownloadTask(
				mangaId = manga.id,
				isPaused = false,
				isSilent = false,
				chaptersIds = chaptersIds?.toLongArray(),
				destination = null,
				format = null,
				allowMeteredNetwork = allowMeteredNetwork,
			)
			downloadScheduler.schedule(setOf(manga to task))
			onDownloadStarted.call(Unit)
		}
	}

	fun deleteLocal() {
		val m = mangaDetails.value?.local?.manga
		if (m == null) {
			errorEvent.call(FileNotFoundException())
			return
		}
		launchLoadingJob(Dispatchers.Default) {
			deleteLocalMangaUseCase(m)
			onMangaRemoved.call(m)
		}
	}

	private fun List<ChapterListItem>.filterSearch(query: String): List<ChapterListItem> {
		if (query.isEmpty() || this.isEmpty()) {
			return this
		}
		return filter { it.contains(query) }
	}

	private suspend fun onDownloadComplete(downloadedManga: LocalManga?) {
		downloadedManga ?: return
		mangaDetails.update {
			interactor.updateLocal(it, downloadedManga)
		}
	}

	class ActivityVMLazy(
		private val fragment: Fragment,
	) : Lazy<ChaptersPagesViewModel> {
		private var cached: ChaptersPagesViewModel? = null

		override val value: ChaptersPagesViewModel
			get() {
				val viewModel = cached
				return if (viewModel == null) {
					val activity = fragment.requireActivity()
					val vmClass = getViewModelClass(activity)
					ViewModelProvider.create(
						store = activity.viewModelStore,
						factory = activity.defaultViewModelProviderFactory,
						extras = activity.defaultViewModelCreationExtras,
					)[vmClass].also { cached = it }
				} else {
					viewModel
				}
			}

		override fun isInitialized(): Boolean = cached != null

		private fun getViewModelClass(activity: Activity) = when (activity) {
			is ReaderActivity -> ReaderViewModel::class.java
			is DetailsActivity -> DetailsViewModel::class.java
			else -> error("Wrong activity ${activity.javaClass.simpleName} for ${ChaptersPagesViewModel::class.java.simpleName}")
		}
	}
}

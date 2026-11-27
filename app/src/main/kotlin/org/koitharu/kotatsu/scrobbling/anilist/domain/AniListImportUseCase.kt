package org.koitharu.kotatsu.scrobbling.anilist.domain

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.model.getPreferredBranch
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.bundle.ActiveParsers
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.favourites.data.toFavouriteCategory
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.scrobbling.anilist.data.AniListRepository
import org.koitharu.kotatsu.scrobbling.anilist.domain.model.AniListCategoryNames
import org.koitharu.kotatsu.scrobbling.anilist.domain.model.AniListImportCandidate
import org.koitharu.kotatsu.scrobbling.anilist.domain.model.AniListImportItem
import org.koitharu.kotatsu.scrobbling.anilist.domain.model.AniListImportPlan
import org.koitharu.kotatsu.scrobbling.anilist.data.AniListTimestampMapper
import org.koitharu.kotatsu.scrobbling.anilist.domain.model.AniListMediaEntry
import org.koitharu.kotatsu.scrobbling.anilist.domain.model.ImportDecision
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.ui.ScrobblerAuthHelper
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.tracker.domain.TrackingRepository
import java.io.File
import java.time.Clock
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

@Singleton
class AniListImportUseCase @Inject constructor(
	@ApplicationContext private val context: Context,
	private val db: MangaDatabase,
	private val aniListRepository: AniListRepository,
	private val scrobblerAuthHelper: ScrobblerAuthHelper,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val mangaDataRepository: MangaDataRepository,
	private val favouritesRepository: FavouritesRepository,
	private val mangaSourcesRepository: MangaSourcesRepository,
	private val settings: AppSettings,
	private val trackingRepository: TrackingRepository,
) {

	private val planCacheFile = File(context.cacheDir, "anilist_import_plan.json")
	var clock: Clock = Clock.systemUTC()

	suspend fun getPrioritySources(): List<MangaSource> {
		val working = ActiveParsers.workingSources.toList()
		val configuredNames = settings.aniListImportSourceNames
		if (!configuredNames.isNullOrEmpty()) {
			val selectedSources = working.filter { it.name in configuredNames }
			if (selectedSources.isNotEmpty()) {
				return selectedSources
			}
		}

		val enabledSources = runCatching { mangaSourcesRepository.getEnabledSources() }
			.getOrDefault(emptyList())
		if (enabledSources.isNotEmpty()) {
			return enabledSources
		}

		val asura = working.filter {
			it.name.contains("asura", ignoreCase = true) || it.getTitle(context).contains("asura", ignoreCase = true)
		}
		val mangadot = working.filter {
			it.name.contains("mangadot", ignoreCase = true) || it.getTitle(context).contains("mangadot", ignoreCase = true)
		}
		return (asura + mangadot + working).distinct().take(5)
	}

	suspend fun resolve(
		progressCallback: suspend (current: Int, total: Int, title: String) -> Unit,
	): AniListImportPlan = withContext(Dispatchers.Default) {
		val user = scrobblerAuthHelper.getUser(ScrobblerService.ANILIST)
		val allMedia = aniListRepository.fetchMediaList(user.id)
			.filter { it.format == null || !it.format.equals("NOVEL", ignoreCase = true) }
		val skippedAdult = if (settings.isNsfwContentDisabled) allMedia.count { it.isAdult } else 0
		val mediaList = if (settings.isNsfwContentDisabled) allMedia.filterNot { it.isAdult } else allMedia

		val sources = getPrioritySources()
		val existingScrobbling = db.getScrobblingDao().findAllByScrobbler(ScrobblerService.ANILIST.id)
			.associateBy { it.targetId }

		val total = mediaList.size
		val semaphore = Semaphore(3)
		val completedCount = AtomicInteger(0)

		coroutineScope {
			mediaList.map { entry ->
				async {
					semaphore.withPermit {
						val item = resolveEntry(entry, sources, existingScrobbling)
						val current = completedCount.incrementAndGet()
						progressCallback(current, total, entry.preferredTitle)
						item
					}
				}
			}.awaitAll()
		}.let { items ->
			val plan = AniListImportPlan(
				items = items,
				createdAt = clock.millis(),
				skippedAdultCount = skippedAdult,
				userId = user.id,
			)
			savePlan(plan)
			plan
		}
	}

	private suspend fun resolveEntry(
		entry: AniListMediaEntry,
		sources: List<MangaSource>,
		existingScrobbling: Map<Long, ScrobblingEntity>,
	): AniListImportItem {
		val targetCategory = getCategoryNameForStatus(entry.status)

		// 1. Check if already linked
		val existingEntity = existingScrobbling[entry.mediaId]
		if (existingEntity != null) {
			return AniListImportItem(
				entry = entry,
				decision = ImportDecision.ALREADY_LINKED,
				targetCategoryName = targetCategory,
				matchedManga = null,
				existingLocalMangaId = existingEntity.mangaId,
				isSelected = true,
			)
		}

		// 2. Check if an exact match already exists in local database
		val localMatches = db.getMangaDao().searchByTitle(entry.preferredTitle, 5)
		val exactLocal = localMatches.firstOrNull {
			AniListTitleMatcher.computeSimilarity(it.manga.title, entry.preferredTitle) >= 0.95f
		}
		if (exactLocal != null) {
			val localManga = exactLocal.toManga()
			return AniListImportItem(
				entry = entry,
				decision = ImportDecision.AUTO_ACCEPT,
				targetCategoryName = targetCategory,
				matchedManga = localManga,
				existingLocalMangaId = localManga.id,
				isSelected = true,
			)
		}

		// 3. Search candidate sources in parallel
		val (bestResult, bestRepo, candidates) = searchSourcesForEntry(entry, sources)

		if (bestResult != null && bestRepo != null && bestResult.confidence != MatchConfidence.NONE) {
			val fullManga = withTimeoutOrNull(4.seconds) {
				runCatching { bestRepo.getDetails(bestResult.manga) }.getOrNull()
			} ?: bestResult.manga

			val decision = if (bestResult.confidence == MatchConfidence.HIGH) {
				ImportDecision.AUTO_ACCEPT
			} else {
				ImportDecision.REVIEW
			}

			return AniListImportItem(
				entry = entry,
				decision = decision,
				targetCategoryName = targetCategory,
				matchedManga = fullManga,
				alternativeCandidates = candidates.sortedByDescending { it.score },
				isSelected = decision == ImportDecision.AUTO_ACCEPT,
			)
		} else {
			return AniListImportItem(
				entry = entry,
				decision = ImportDecision.NOT_FOUND,
				targetCategoryName = targetCategory,
				matchedManga = null,
				isSelected = false,
			)
		}
	}

	private suspend fun searchSourcesForEntry(
		entry: AniListMediaEntry,
		sources: List<MangaSource>,
	): Triple<MatchResult?, MangaRepository?, List<AniListImportCandidate>> = coroutineScope {
		val primaryQuery = entry.preferredTitle.ifEmpty { entry.allTitles.firstOrNull() ?: "" }
		if (primaryQuery.isBlank()) return@coroutineScope Triple(null, null, emptyList())

		val cleanQuery = primaryQuery.replace(Regex("\\s*\\([^)]*\\)"), "").trim()
		val queriesToTry = buildList {
			add(primaryQuery)
			if (cleanQuery.isNotEmpty() && cleanQuery != primaryQuery) {
				add(cleanQuery)
			}
			entry.allTitles.firstOrNull { it != primaryQuery && it != cleanQuery }?.let { add(it) }
		}.distinct()

		var bestResult: MatchResult? = null
		var bestRepo: MangaRepository? = null
		val candidatesList = ArrayList<AniListImportCandidate>()

		for (query in queriesToTry) {
			val resultsPerSource = sources.map { source ->
				async {
					val repo = mangaRepositoryFactory.create(source)
					val results = withTimeoutOrNull(3.5.seconds) {
						runCatching {
							repo.getList(0, SortOrder.RELEVANCE, MangaListFilter(query = query))
						}.getOrNull()
					}.orEmpty()
					Pair(repo, results)
				}
			}.awaitAll()

			for ((repo, results) in resultsPerSource) {
				for (candidate in results) {
					val eval = AniListTitleMatcher.evaluate(entry, candidate)
					if (eval.confidence != MatchConfidence.NONE) {
						candidatesList.add(
							AniListImportCandidate(
								manga = candidate,
								score = eval.score,
								confidence = eval.confidence,
								reason = eval.reason,
							),
						)
					}
					if (bestResult == null || eval.score > bestResult.score) {
						bestResult = eval
						bestRepo = repo
					}
				}
			}

			if (bestResult != null && bestResult!!.confidence == MatchConfidence.HIGH && bestResult!!.score >= 0.90f) {
				break
			}
		}

		Triple(bestResult, bestRepo, candidatesList)
	}

	suspend fun apply(
		plan: AniListImportPlan,
		progressCallback: suspend (current: Int, total: Int) -> Unit,
	): Int = withContext(Dispatchers.Default) {
		val selectedItems = plan.items.filter { it.isSelected && (it.matchedManga != null || it.existingLocalMangaId != null) }
		val total = selectedItems.size
		var appliedCount = 0

		for ((index, item) in selectedItems.withIndex()) {
			progressCallback(index + 1, total)

			try {
				val category = getOrCreateCategory(item.targetCategoryName)
				var primaryManga: Manga? = null

				if (item.existingLocalMangaId != null) {
					val localManga = mangaDataRepository.findMangaById(item.existingLocalMangaId, withChapters = true)
					if (localManga != null) {
						primaryManga = localManga
						favouritesRepository.addToCategory(category.id, listOf(localManga))

						val localHistory = db.getHistoryDao().find(localManga.id)
						val localCurrentChapter = localManga.chapters?.firstOrNull { it.id == localHistory?.chapterId }
						val localNumber = localCurrentChapter?.let { getChapterNumber(it) } ?: 0f

						if (item.entry.progress > localNumber) {
							applyChapterProgress(localManga, item.entry)
						}

						val scrobblerEntity = ScrobblingEntity(
							scrobbler = ScrobblerService.ANILIST.id,
							id = item.entry.entryId,
							mangaId = localManga.id,
							targetId = item.entry.mediaId,
							status = item.entry.status,
							chapter = item.entry.progress,
							comment = item.entry.notes,
							rating = item.entry.score,
						)
						db.getScrobblingDao().upsert(scrobblerEntity)
						appliedCount++
					}
				} else if (item.matchedManga != null) {
					var details = item.matchedManga
					if (details.chapters.isNullOrEmpty()) {
						details = runCatching {
							mangaRepositoryFactory.create(details.source).getDetails(details)
						}.getOrDefault(details)
					}
					primaryManga = details

					db.withTransaction {
						mangaDataRepository.storeManga(details, replaceExisting = true)
						favouritesRepository.addToCategory(category.id, listOf(details))

						if (item.entry.progress > 0) {
							applyChapterProgress(details, item.entry)
						}

						val scrobblerEntity = ScrobblingEntity(
							scrobbler = ScrobblerService.ANILIST.id,
							id = item.entry.entryId,
							mangaId = details.id,
							targetId = item.entry.mediaId,
							status = item.entry.status,
							chapter = item.entry.progress,
							comment = item.entry.notes,
							rating = item.entry.score,
						)
						db.getScrobblingDao().upsert(scrobblerEntity)
					}
					appliedCount++
				}

				if (primaryManga != null) {
					// Also sync across other local copies of this series in the user's library
					val otherLocalMangaList = ArrayList<Manga>()
					val linkedOther = db.getScrobblingDao().findByTargetId(ScrobblerService.ANILIST.id, item.entry.mediaId)
					if (linkedOther != null && linkedOther.mangaId != primaryManga.id) {
						mangaDataRepository.findMangaById(linkedOther.mangaId, withChapters = true)?.let { otherLocalMangaList.add(it) }
					}
					val localCandidates = db.getMangaDao().searchByTitle(item.entry.preferredTitle, 5)
					for (candidate in localCandidates) {
						if (candidate.manga.id != primaryManga.id && otherLocalMangaList.none { it.id == candidate.manga.id }) {
							val sim = AniListTitleMatcher.computeSimilarity(candidate.manga.title, item.entry.preferredTitle)
							if (sim >= 0.90f) {
								mangaDataRepository.findMangaById(candidate.manga.id, withChapters = true)?.let { otherLocalMangaList.add(it) }
							}
						}
					}

					for (other in otherLocalMangaList) {
						favouritesRepository.addToCategory(category.id, listOf(other))
						val otherHistory = db.getHistoryDao().find(other.id)
						val otherCurrentChapter = other.chapters?.firstOrNull { it.id == otherHistory?.chapterId }
						val otherNumber = otherCurrentChapter?.let { getChapterNumber(it) } ?: 0f

						if (item.entry.progress > otherNumber) {
							applyChapterProgress(other, item.entry)
						}

						val otherScrobbler = ScrobblingEntity(
							scrobbler = ScrobblerService.ANILIST.id,
							id = item.entry.entryId,
							mangaId = other.id,
							targetId = item.entry.mediaId,
							status = item.entry.status,
							chapter = item.entry.progress,
							comment = item.entry.notes,
							rating = item.entry.score,
						)
						db.getScrobblingDao().upsert(otherScrobbler)
					}
				}
			} catch (e: Exception) {
				e.printStackTraceDebug()
			}
		}

		clearCachedPlan()
		appliedCount
	}

	private suspend fun applyChapterProgress(manga: Manga, entry: AniListMediaEntry) {
		val preferredBranch = run {
			val allBranches = manga.chapters?.mapNotNull { it.branch }?.distinct().orEmpty()
			if (allBranches.size > 1) {
				allBranches.firstOrNull { b ->
					b.contains("asura", ignoreCase = true) &&
						manga.getChapters(b).any { getChapterNumber(it) == entry.progress.toFloat() }
				} ?: allBranches.firstOrNull { b ->
					manga.getChapters(b).any { getChapterNumber(it) == entry.progress.toFloat() }
				} ?: allBranches.firstOrNull { b ->
					b.contains("asura", ignoreCase = true)
				} ?: manga.getPreferredBranch(null)
			} else {
				manga.getPreferredBranch(null)
			}
		}
		val chapters = manga.getChapters(preferredBranch)
		if (chapters.isEmpty()) return

		var matchedIndex = -1
		for (i in chapters.indices) {
			val num = getChapterNumber(chapters[i])
			if (num > 0f && num <= entry.progress) {
				matchedIndex = i
			}
		}
		if (matchedIndex == -1) {
			matchedIndex = (entry.progress - 1).coerceIn(0, chapters.lastIndex)
		}

		val latestChapter = chapters.last()
		val isLatestFractionalOfCurrent = matchedIndex == chapters.lastIndex - 1 &&
			getChapterNumber(latestChapter) > 0f &&
			getChapterNumber(latestChapter) < (entry.progress + 1.0f)

		val isFinished = entry.status.equals("COMPLETED", ignoreCase = true) ||
			matchedIndex >= chapters.lastIndex ||
			isLatestFractionalOfCurrent

		val (targetChapter, percent) = if (isFinished) {
			val target = if (isLatestFractionalOfCurrent) latestChapter else chapters[matchedIndex]
			Pair(target, 1.0f)
		} else {
			val nextIndex = matchedIndex + 1
			val calculatedPercent = (matchedIndex + 1).toFloat() / chapters.size.toFloat()
			Pair(chapters[nextIndex], calculatedPercent)
		}

		// Use AniList's updatedAt (* 1000 ms), which falls back to completedAt, startedAt, and createdAt.
		// If completely unstamped on AniList, use a fallback timestamp (30 days ago) so it sorts below
		// actively read titles without landing under a strange 1970 date header in History.
		val fallbackTimestamp = AniListTimestampMapper.defaultFallbackTimestamp(clock)
		val timestamp = entry.updatedAt?.takeIf { it > 0L } ?: fallbackTimestamp
		val historyEntity = HistoryEntity(
			mangaId = manga.id,
			createdAt = timestamp,
			updatedAt = timestamp,
			chapterId = targetChapter.id,
			page = 0,
			scroll = 0f,
			percent = percent,
			chaptersCount = chapters.size,
			deletedAt = 0L,
		)
		db.getHistoryDao().upsert(historyEntity)
		if (settings.isLiquidGlass) {
			trackingRepository.applyTrackingResult(
				mangaId = manga.id,
				preferredBranch = preferredBranch,
				isImport = true,
			)
		}
	}

	private suspend fun getOrCreateCategory(categoryName: String): FavouriteCategory {
		val readLaterTitle = context.getString(R.string.read_later)
		val isReadLaterOrPlanning = categoryName.equals(readLaterTitle, ignoreCase = true) ||
			categoryName.equals("Read later", ignoreCase = true) ||
			categoryName.equals("Planning", ignoreCase = true) ||
			categoryName.equals("Plan to read", ignoreCase = true)

		val allCategories = db.getFavouriteCategoriesDao().findAll()
		val existing = if (isReadLaterOrPlanning) {
			allCategories.firstOrNull {
				it.title.equals(readLaterTitle, ignoreCase = true) ||
					it.title.equals("Read later", ignoreCase = true) ||
					it.title.equals("Planning", ignoreCase = true) ||
					it.title.equals("Plan to read", ignoreCase = true)
			}
		} else {
			allCategories.firstOrNull { it.title.equals(categoryName, ignoreCase = true) }
		}

		if (isReadLaterOrPlanning && existing != null) {
			val planningCategory = allCategories.firstOrNull {
				it.categoryId != existing.categoryId && (it.title.equals("Planning", ignoreCase = true) || it.title.equals("Plan to read", ignoreCase = true))
			}
			if (planningCategory != null) {
				runCatching {
					db.openHelper.writableDatabase.execSQL(
						"UPDATE favourites SET category_id = ${existing.categoryId} WHERE category_id = ${planningCategory.categoryId}"
					)
					db.getFavouriteCategoriesDao().delete(planningCategory.categoryId.toLong())
				}
			}
		}

		return if (existing != null) {
			existing.toFavouriteCategory()
		} else {
			favouritesRepository.createCategory(
				title = if (isReadLaterOrPlanning) readLaterTitle else categoryName,
				sortOrder = ListSortOrder.UPDATED,
				isTrackerEnabled = true,
				isVisibleOnShelf = true,
			)
		}
	}

	private fun getCategoryNameForStatus(status: String): String = AniListCategoryNames.fromStatus(status)

	private fun getChapterNumber(chapter: MangaChapter): Float {
		if (chapter.number > 0f) return chapter.number
		val title = chapter.title ?: return 0f
		val match = Regex("(?i)chapter\\s*(\\d+(?:\\.\\d+)?)").find(title)
		if (match != null) {
			return match.groupValues[1].toFloatOrNull() ?: 0f
		}
		return 0f
	}

	fun savePlan(plan: AniListImportPlan) {
		runCatching {
			val root = JSONObject()
			root.put("version", PLAN_VERSION)
			root.put("createdAt", plan.createdAt)
			root.put("skippedAdultCount", plan.skippedAdultCount)
			if (plan.userId != null) {
				root.put("userId", plan.userId)
			}
			val itemsArr = JSONArray()
			for (item in plan.items) {
				val itemObj = JSONObject()
				itemObj.put("decision", item.decision.name)
				itemObj.put("category", item.targetCategoryName)
				itemObj.put("isSelected", item.isSelected)
				if (item.existingLocalMangaId != null) {
					itemObj.put("localMangaId", item.existingLocalMangaId)
				}
				val entryObj = JSONObject()
				entryObj.put("entryId", item.entry.entryId)
				entryObj.put("mediaId", item.entry.mediaId)
				entryObj.put("title", item.entry.preferredTitle)
				entryObj.put("progress", item.entry.progress)
				entryObj.put("status", item.entry.status)
				entryObj.put("score", item.entry.score.toDouble())
				entryObj.put("totalChapters", item.entry.totalChapters ?: -1)
				entryObj.put("notes", item.entry.notes)
				entryObj.put("coverUrl", item.entry.coverUrl)
				entryObj.put("siteUrl", item.entry.siteUrl)
				entryObj.put("format", item.entry.format)
				entryObj.put("updatedAt", item.entry.updatedAt ?: 0L)
				entryObj.put("isAdult", item.entry.isAdult)
				val allTitlesArr = JSONArray()
				for (t in item.entry.allTitles) {
					allTitlesArr.put(t)
				}
				entryObj.put("allTitles", allTitlesArr)
				itemObj.put("entry", entryObj)

				if (item.matchedManga != null) {
					val m = item.matchedManga
					val mangaObj = JSONObject()
					mangaObj.put("id", m.id)
					mangaObj.put("title", m.title)
					mangaObj.put("url", m.url)
					mangaObj.put("publicUrl", m.publicUrl)
					mangaObj.put("rating", m.rating.toDouble())
					mangaObj.put("coverUrl", m.coverUrl)
					mangaObj.put("source", m.source.name)
					mangaObj.put("state", m.state?.name)
					itemObj.put("matchedManga", mangaObj)
				}
				itemsArr.put(itemObj)
			}
			root.put("items", itemsArr)
			planCacheFile.writeText(root.toString())
		}.onFailure { it.printStackTraceDebug() }
	}

	fun loadCachedPlan(): AniListImportPlan? {
		if (!planCacheFile.exists()) return null
		return runCatching {
			val root = JSONObject(planCacheFile.readText())
			val version = root.optInt("version", 0)
			if (version != PLAN_VERSION) {
				planCacheFile.delete()
				return null
			}
			val createdAt = root.optLong("createdAt", 0L)
			if (System.currentTimeMillis() - createdAt > 24 * 60 * 60 * 1000L) {
				planCacheFile.delete()
				return null
			}
			val skippedAdultCount = root.optInt("skippedAdultCount", 0)
			val userId = if (root.has("userId")) root.getLong("userId") else null
			val currentUserId = aniListRepository.cachedUser?.id
			if (userId != null && currentUserId != null && userId != currentUserId) {
				planCacheFile.delete()
				return null
			}
			val itemsArr = root.getJSONArray("items")
			val items = ArrayList<AniListImportItem>()
			for (i in 0 until itemsArr.length()) {
				val itemObj = itemsArr.getJSONObject(i)
				val entryObj = itemObj.getJSONObject("entry")
				val allTitles = ArrayList<String>()
				val titlesArr = entryObj.optJSONArray("allTitles")
				if (titlesArr != null) {
					for (k in 0 until titlesArr.length()) {
						allTitles.add(titlesArr.getString(k))
					}
				}
				val totalChapters = entryObj.optInt("totalChapters", -1).takeIf { it >= 0 }
				val updatedAt = entryObj.optLong("updatedAt", 0L).takeIf { it > 0L }
				val entry = AniListMediaEntry(
					entryId = entryObj.getInt("entryId"),
					mediaId = entryObj.getLong("mediaId"),
					status = entryObj.getString("status"),
					progress = entryObj.getInt("progress"),
					score = entryObj.optDouble("score", 0.0).toFloat(),
					notes = entryObj.optString("notes").ifEmpty { null },
					preferredTitle = entryObj.getString("title"),
					allTitles = allTitles,
					totalChapters = totalChapters,
					coverUrl = entryObj.optString("coverUrl").ifEmpty { null },
					siteUrl = entryObj.optString("siteUrl").ifEmpty { null },
					format = entryObj.optString("format").ifEmpty { null },
					updatedAt = updatedAt,
					isAdult = entryObj.optBoolean("isAdult", false),
				)
				val decision = ImportDecision.valueOf(itemObj.getString("decision"))
				val targetCategory = itemObj.getString("category")
				val isSelected = itemObj.optBoolean("isSelected", true)
				val localId = if (itemObj.has("localMangaId")) itemObj.getLong("localMangaId") else null

				var matchedManga: Manga? = null
				if (itemObj.has("matchedManga")) {
					val mObj = itemObj.getJSONObject("matchedManga")
					val sourceName = mObj.getString("source")
					val source = ActiveParsers.workingSources.firstOrNull { it.name.equals(sourceName, ignoreCase = true) }
					if (source != null) {
						matchedManga = Manga(
							id = mObj.getLong("id"),
							title = mObj.getString("title"),
							altTitles = emptySet(),
							url = mObj.getString("url"),
							publicUrl = mObj.getString("publicUrl"),
							rating = mObj.optDouble("rating", 0.0).toFloat(),
							contentRating = null,
							coverUrl = mObj.optString("coverUrl").ifEmpty { null },
							tags = emptySet(),
							state = mObj.optString("state").ifEmpty { null }?.let { runCatching { MangaState.valueOf(it) }.getOrNull() },
							authors = emptySet(),
							source = source,
						)
					}
				}

				items.add(
					AniListImportItem(
						entry = entry,
						decision = decision,
						targetCategoryName = targetCategory,
						matchedManga = matchedManga,
						existingLocalMangaId = localId,
						isSelected = isSelected,
					),
				)
			}
			AniListImportPlan(
				items = items,
				createdAt = createdAt,
				skippedAdultCount = skippedAdultCount,
				userId = userId,
			)
		}.getOrNull()
	}

	fun clearCachedPlan() {
		planCacheFile.delete()
	}

	companion object {
		const val PLAN_VERSION = 1
	}
}

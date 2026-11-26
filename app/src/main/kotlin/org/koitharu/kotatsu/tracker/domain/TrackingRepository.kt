package org.koitharu.kotatsu.tracker.domain

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.db.entity.toMangaTags
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.ParserMangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.mapItems
import org.koitharu.kotatsu.core.util.ext.toInstantOrNull
import org.koitharu.kotatsu.details.domain.ProgressUpdateUseCase
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.ifZero
import org.koitharu.kotatsu.core.db.entity.toMangaChapters
import org.koitharu.kotatsu.history.data.toMangaHistory
import org.koitharu.kotatsu.tracker.data.MangaWithAnchor
import org.koitharu.kotatsu.tracker.data.TrackEntity
import org.koitharu.kotatsu.tracker.data.TrackLogEntity
import org.koitharu.kotatsu.tracker.data.TrackingAnchorEntity
import org.koitharu.kotatsu.tracker.data.toTrackingAnchor
import org.koitharu.kotatsu.tracker.data.toTrackingLogItem
import org.koitharu.kotatsu.tracker.domain.model.MangaTracking
import org.koitharu.kotatsu.tracker.domain.model.MangaUpdates
import org.koitharu.kotatsu.tracker.domain.model.TrackingLogItem
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val NO_ID = 0L
private const val MAX_LOG_SIZE = 120

@Reusable
class TrackingRepository @Inject constructor(
	private val db: MangaDatabase,
	private val settings: AppSettings,
	private val progressUpdateUseCase: ProgressUpdateUseCase,
	@ApplicationContext private val context: Context,
	private val mangaRepositoryFactory: MangaRepository.Factory,
) {

	private var isGcCalled = AtomicBoolean(false)

	suspend fun getNewChaptersCount(mangaId: Long): Int {
		if (settings.isLiquidGlass) {
			val anchor = db.getTrackingAnchorsDao().find(mangaId)
			if (anchor != null) {
				return anchor.newReleasesCount
			}
		}
		return db.getTracksDao().findNewChapters(mangaId)
	}

	fun observeNewChaptersCount(mangaId: Long): Flow<Int> {
		return db.getTracksDao().observeNewChapters(mangaId)
	}

	suspend fun getLastUpdateTime(mangaId: Long): Long {
		return db.getTrackLogsDao().getLastLogTime(mangaId) ?: 0L
	}

	@Deprecated("")
	fun observeUpdatedMangaCount(): Flow<Int> {
		return db.getTracksDao().observeUpdateMangaCount()
			.onStart { gcIfNotCalled() }
	}

	fun observeUnreadUpdatesCount(): Flow<Int> {
		return db.getTrackLogsDao().observeUnreadCount()
	}

	fun observeUpdatedManga(limit: Int, filterOptions: Set<ListFilterOption>): Flow<List<MangaTracking>> {
		return db.getTracksDao().observeUpdatedManga(limit, filterOptions)
			.mapItems {
				MangaTracking(
					manga = it.manga.toManga(it.tags.toMangaTags(), null),
					lastChapterId = it.track.lastChapterId,
					lastCheck = it.track.lastCheckTime.toInstantOrNull(),
					lastChapterDate = it.track.lastChapterDate.toInstantOrNull(),
					newChapters = it.track.newChapters,
				)
			}.distinctUntilChanged()
			.onStart { gcIfNotCalled() }
	}

	suspend fun getTracks(offset: Int, limit: Int, minActivityTime: Long, staleCheckTime: Long): List<MangaTracking> {
		// Tracks from sources with update checking disabled never get their last_check_time
		// advanced, so they permanently sort to the front of the batch. Filtering them after
		// the SQL LIMIT would let them occupy batch slots forever, starving the rest of the
		// queue — page through until the requested amount of checkable tracks is collected.
		val result = ArrayList<MangaTracking>(if (limit == Int.MAX_VALUE) 16 else limit)
		var currentOffset = offset
		while (result.size < limit) {
			val window = db.getTracksDao().findAll(
				offset = currentOffset,
				limit = limit - result.size,
				minActivityTime = minActivityTime,
				staleCheckTime = staleCheckTime,
			)
			if (window.isEmpty()) {
				break
			}
			currentOffset += window.size
			for (item in window) {
				val manga = item.manga.toManga(emptySet(), null)
				if (isUpdateCheckingDisabled(manga)) {
					Log.i(
						TAG,
						"getTracks: [${manga.id}] \"${manga.title}\" skipped: " +
							"update checking disabled for source ${manga.source.name}",
					)
					continue
				}
				result.add(
					MangaTracking(
						manga = manga,
						lastChapterId = item.track.lastChapterId,
						lastCheck = item.track.lastCheckTime.toInstantOrNull(),
						lastChapterDate = item.track.lastChapterDate.toInstantOrNull(),
						newChapters = item.track.newChapters,
					),
				)
				if (result.size >= limit) {
					break
				}
			}
		}
		Log.i(
			TAG,
			"getTracks: collected ${result.size} of ${if (limit == Int.MAX_VALUE) "all" else limit} requested, " +
				"scanned ${currentOffset - offset} eligible row(s), " +
				"total tracks in db: ${db.getTracksDao().getTracksCount()}, " +
				"minActivityTime=${minActivityTime.toInstantOrNull()}, staleCheckTime=${staleCheckTime.toInstantOrNull()}",
		)
		return result
	}

	private fun isUpdateCheckingDisabled(manga: Manga): Boolean {
		val repository = mangaRepositoryFactory.create(manga.source)
		if (repository !is ParserMangaRepository) {
			return false // Non-parser sources (local, etc.) are not disabled
		}

		// Check if parser has DisableUpdateChecking ConfigKey
		val configKeys = repository.getConfigKeys()
		val disableKey = configKeys.filterIsInstance<ConfigKey.DisableUpdateChecking>().firstOrNull()
		return disableKey?.defaultValue == true
	}

	/** Every track with no known chapter for the silence [StaleTrackPolicy] goes by, however long ago. */
	suspend fun getSilentTrackIds(now: Long): Set<Long> {
		return db.getTracksDao().findIdsWithLastChapterBefore(StaleTrackPolicy.maxLastChapterDate(now)).toSet()
	}

	/**
	 * Tracks to put through [StaleTrackPolicy]. Everything used here stays in the database while a source is failing:
	 * failed checks keep the track's last chapter date and don't touch the stored chapter list.
	 */
	suspend fun getStaleTrackCandidates(now: Long, excludeIds: Set<Long>): List<Pair<Manga, StaleTrackPolicy.Track>> {
		val rows = db.getTracksDao().findByLastChapterDate(
			minChapterDate = StaleTrackPolicy.minLastChapterDate(now),
			maxChapterDate = StaleTrackPolicy.maxLastChapterDate(now),
		)
		return rows.mapNotNull { row ->
			val manga = row.manga.toManga(emptySet(), null)
			if (manga.id in excludeIds || isUpdateCheckingDisabled(manga)) {
				return@mapNotNull null
			}
			manga to StaleTrackPolicy.Track(
				mangaId = manga.id,
				state = manga.state,
				lastChapterDate = row.track.lastChapterDate,
				lastLogTime = db.getTrackLogsDao().getLastLogTime(manga.id) ?: 0L,
				lastCheckTime = row.track.lastCheckTime,
				releaseDates = db.getChaptersDao().findUploadDates(manga.id, row.track.lastChapterId, RELEASE_DATES_LIMIT),
			)
		}
	}

	@Deprecated("")
	suspend fun getTrack(manga: Manga): MangaTracking {
		return getTrackOrNull(manga) ?: MangaTracking(
			manga = manga,
			lastChapterId = NO_ID,
			lastCheck = null,
			lastChapterDate = null,
			newChapters = 0,
		)
	}

	suspend fun getTrackOrNull(manga: Manga): MangaTracking? {
		val track = db.getTracksDao().find(manga.id) ?: return null
		return MangaTracking(
			manga = manga,
			lastChapterId = track.lastChapterId,
			lastCheck = track.lastCheckTime.toInstantOrNull(),
			lastChapterDate = track.lastChapterDate.toInstantOrNull(),
			newChapters = track.newChapters,
		)
	}

	@VisibleForTesting
	suspend fun deleteTrack(mangaId: Long) {
		db.getTracksDao().delete(mangaId)
	}

	fun observeTrackingLog(limit: Int, filterOptions: Set<ListFilterOption>): Flow<List<TrackingLogItem>> {
		return db.getTrackLogsDao().observeAll(limit, filterOptions)
			.mapItems { it.toTrackingLogItem() }
			.onStart { gcIfNotCalled() }
	}

	suspend fun getLogsCount() = db.getTrackLogsDao().count()

	suspend fun clearLogs() = db.getTrackLogsDao().clear()

	suspend fun clearCounters() = db.getTracksDao().clearCounters()

	suspend fun markAsRead(trackLogId: Long) = db.getTrackLogsDao().markAsRead(trackLogId)

	/**
	 * Deletes a single feed entry and returns it, so the caller can offer to put it back.
	 * Returns null if it was already gone.
	 */
	suspend fun removeLog(trackLogId: Long): TrackLogEntity? = db.withTransaction {
		val dao = db.getTrackLogsDao()
		dao.find(trackLogId)?.also { dao.delete(trackLogId) }
	}

	/** Reinserts an entry removed by [removeLog]; the entity carries its original id, so it goes back
	 *  in the same place in the feed rather than to the top. */
	suspend fun restoreLog(entity: TrackLogEntity) {
		db.getTrackLogsDao().insert(entity)
	}

	suspend fun gc() = db.withTransaction {
		db.getTracksDao().gc()
		db.getTracksDao().clearStaleCounters(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(MAX_STALE_UPDATE_DAYS))
		db.getTrackLogsDao().run {
			gc()
			trim(MAX_LOG_SIZE)
		}
	}

	suspend fun saveUpdates(updates: MangaUpdates) {
		db.withTransaction {
			if (settings.isLiquidGlass) {
				applyTrackingResult(
					mangaId = updates.manga.id,
					preferredBranch = (updates as? MangaUpdates.Success)?.branch,
					status = when (updates) {
						is MangaUpdates.Failure -> TrackingAnchorEntity.STATUS_FAILED
						is MangaUpdates.Success -> TrackingAnchorEntity.STATUS_OK
					},
					failCountDelta = if (updates is MangaUpdates.Failure) 1 else 0,
					resetFailCount = updates is MangaUpdates.Success,
				)
				if (updates is MangaUpdates.Success && updates.isValid && updates.newChapters.isNotEmpty()) {
					progressUpdateUseCase(updates.manga)
					val logEntity = TrackLogEntity(
						mangaId = updates.manga.id,
						chapters = updates.newChapters.joinToString("\n") { x -> x.name },
						createdAt = System.currentTimeMillis(),
						isUnread = true,
					)
					db.getTrackLogsDao().insert(logEntity)
				}
			} else {
				val track = getOrCreateTrack(updates.manga.id).mergeWith(updates)
				db.getTracksDao().upsert(track)
				if (updates is MangaUpdates.Success && updates.isValid && updates.newChapters.isNotEmpty()) {
					progressUpdateUseCase(updates.manga)
					val logEntity = TrackLogEntity(
						mangaId = updates.manga.id,
						chapters = updates.newChapters.joinToString("\n") { x -> x.name },
						createdAt = System.currentTimeMillis(),
						isUnread = true,
					)
					db.getTrackLogsDao().insert(logEntity)
				}
			}
		}
	}

	suspend fun clearUpdates(ids: Collection<Long>) {
		when {
			ids.isEmpty() -> return
			ids.size == 1 -> db.getTracksDao().clearCounter(ids.single())
			else -> db.withTransaction {
				for (id in ids) {
					db.getTracksDao().clearCounter(id)
				}
			}
		}
	}

	val isLiquidGlass: Boolean
		get() = settings.isLiquidGlass

	suspend fun applyTrackingResult(
		mangaId: Long,
		preferredBranch: String? = null,
		isForwardRead: Boolean = false,
		isImport: Boolean = false,
		status: Int = TrackingAnchorEntity.STATUS_OK,
		lastCheckedAt: Long = System.currentTimeMillis(),
		failCountDelta: Int = 0,
		resetFailCount: Boolean = false,
	): NewChaptersResult? = db.withTransaction {
		val historyEntity = db.getHistoryDao().find(mangaId)
		val history = historyEntity?.toMangaHistory()
		val chapterEntities = db.getChaptersDao().findAll(mangaId)
		if (chapterEntities.isEmpty()) {
			return@withTransaction null
		}
		val cachedChapters = chapterEntities.toMangaChapters()

		val existingAnchorEntity = db.getTrackingAnchorsDao().find(mangaId)
		val targetBranch = preferredBranch
			?: existingAnchorEntity?.branch
			?: history?.chapterId?.let { id -> cachedChapters.find { it.id == id }?.branch }
			?: "default"

		val branchChapters = cachedChapters.filter { it.branch == targetBranch || it.branch == null }
		val latestBranchChapter = branchChapters.lastOrNull() ?: cachedChapters.lastOrNull()

		val existingTrack = db.getTracksDao().find(mangaId)
		val hasExistingTrackUpdates = existingTrack != null && existingTrack.newChapters > 0 && !isImport

		// When no anchor row exists yet (first check, upgrade from v28, restore from backup, or import):
		// snapshot is initialized to latest chapter of the source,
		// so nobody gets flooded with old backlog as "new releases".
		// Exception: if the existing tracker already recorded new chapters, preserve them!
		val anchorForComputation = if (existingAnchorEntity == null) {
			TrackingAnchor(
				lastChapterId = latestBranchChapter?.id ?: 0L,
				baselineVolume = latestBranchChapter?.volume,
				baselineNumber = latestBranchChapter?.number,
				latestVolumeAtLastRead = if (hasExistingTrackUpdates) null else latestBranchChapter?.volume,
				latestNumberAtLastRead = if (hasExistingTrackUpdates) null else latestBranchChapter?.number,
				branch = targetBranch,
			)
		} else {
			existingAnchorEntity.toTrackingAnchor()
		}

		val result = ChapterCounter.computeNewChapters(
			chapters = cachedChapters,
			history = history,
			anchor = anchorForComputation,
			preferredBranch = targetBranch,
		)

		val currentReadChapter = history?.chapterId?.let { id -> cachedChapters.find { it.id == id } }
		val currentReadNum = currentReadChapter?.number ?: 0f
		val currentReadVol = currentReadChapter?.volume ?: 0
		val prevReadNum = existingAnchorEntity?.lastReadNumber ?: 0f
		val prevReadVol = existingAnchorEntity?.lastReadVolume ?: 0

		val isActuallyAdvancing = isForwardRead && (
			currentReadVol > prevReadVol || (currentReadVol == prevReadVol && currentReadNum > prevReadNum)
		)

		val newSnapshotVol: Int?
		val newSnapshotNum: Float?

		when {
			isImport -> {
				newSnapshotVol = latestBranchChapter?.volume ?: 0
				newSnapshotNum = latestBranchChapter?.number ?: 0f
			}
			isActuallyAdvancing -> {
				newSnapshotVol = latestBranchChapter?.volume ?: existingAnchorEntity?.latestVolumeAtLastRead
				newSnapshotNum = latestBranchChapter?.number ?: existingAnchorEntity?.latestNumberAtLastRead
			}
			existingAnchorEntity != null -> {
				newSnapshotVol = existingAnchorEntity.latestVolumeAtLastRead
				newSnapshotNum = existingAnchorEntity.latestNumberAtLastRead
			}
			hasExistingTrackUpdates -> {
				newSnapshotVol = result.baselineToPersist?.latestVolumeAtLastRead
				newSnapshotNum = result.baselineToPersist?.latestNumberAtLastRead
			}
			else -> {
				newSnapshotVol = result.baselineToPersist?.latestVolumeAtLastRead ?: latestBranchChapter?.volume
				newSnapshotNum = result.baselineToPersist?.latestNumberAtLastRead ?: latestBranchChapter?.number
			}
		}

		val finalNewReleasesCount = when {
			isActuallyAdvancing -> 0
			isImport -> 0
			result.count == 0 -> 0
			existingAnchorEntity == null && hasExistingTrackUpdates -> {
				minOf(result.count, existingTrack.newChapters)
			}
			else -> result.newReleasesCount
		}

		val newDetectedAt = when {
			finalNewReleasesCount == 0 -> 0L
			finalNewReleasesCount > (existingAnchorEntity?.newReleasesCount ?: 0) -> System.currentTimeMillis()
			else -> existingAnchorEntity?.newDetectedAt ?: System.currentTimeMillis()
		}

		val newFailCount = when {
			resetFailCount -> 0
			else -> ((existingAnchorEntity?.failCount ?: 0) + failCountDelta).coerceAtLeast(0)
		}

		val updatedAnchor = TrackingAnchorEntity(
			mangaId = mangaId,
			lastReadVolume = if (history != null) {
				if (history.page > 0 || history.scroll > 0 || history.percent >= 1f) {
					currentReadVol
				} else {
					existingAnchorEntity?.lastReadVolume ?: currentReadVol
				}
			} else {
				null
			},
			lastReadNumber = if (history != null) {
				if (history.page > 0 || history.scroll > 0 || history.percent >= 1f) {
					currentReadNum
				} else {
					existingAnchorEntity?.lastReadNumber ?: (currentReadNum - 1f).coerceAtLeast(0f)
				}
			} else {
				null
			},
			latestVolumeAtLastRead = newSnapshotVol,
			latestNumberAtLastRead = newSnapshotNum,
			baselineVolume = result.baselineToPersist?.baselineVolume ?: existingAnchorEntity?.baselineVolume,
			baselineNumber = result.baselineToPersist?.baselineNumber ?: existingAnchorEntity?.baselineNumber,
			branch = targetBranch,
			newReleasesCount = finalNewReleasesCount,
			newDetectedAt = newDetectedAt,
			lastCheckedAt = lastCheckedAt,
			status = status,
			failCount = newFailCount,
			pendingShrinkCount = existingAnchorEntity?.pendingShrinkCount,
			pendingShrinkHash = existingAnchorEntity?.pendingShrinkHash,
		)
		db.getTrackingAnchorsDao().upsert(updatedAnchor)

		if (settings.isLiquidGlass) {
			val existingTrack = db.getTracksDao().find(mangaId) ?: TrackEntity.create(mangaId)
			val latestCh = result.latestChapter ?: latestBranchChapter
			val updatedTrack = existingTrack.copy(
				newChapters = result.count,
				lastChapterId = latestCh?.id ?: existingTrack.lastChapterId,
				lastChapterDate = latestCh?.uploadDate?.ifZero { existingTrack.lastChapterDate } ?: existingTrack.lastChapterDate,
				lastCheckTime = lastCheckedAt,
				lastResult = when {
					status == TrackingAnchorEntity.STATUS_FAILED -> TrackEntity.RESULT_FAILED
					result.count > 0 -> TrackEntity.RESULT_HAS_UPDATE
					else -> TrackEntity.RESULT_NO_UPDATE
				},
				lastError = if (status == TrackingAnchorEntity.STATUS_FAILED) existingTrack.lastError else null,
			)
			db.getTracksDao().upsert(updatedTrack)
		}

		result
	}

	suspend fun resetTrackingAnchorOnHistoryDeleted(mangaId: Long) = db.withTransaction {
		val latest = db.getChaptersDao().findAll(mangaId).lastOrNull()
		val existing = db.getTrackingAnchorsDao().find(mangaId)
		val targetBranch = existing?.branch ?: "default"
		val anchor = TrackingAnchorEntity.create(mangaId, targetBranch).copy(
			baselineVolume = latest?.volume ?: 0,
			baselineNumber = latest?.number ?: 0f,
			latestVolumeAtLastRead = latest?.volume ?: 0,
			latestNumberAtLastRead = latest?.number ?: 0f,
			newReleasesCount = 0,
			newDetectedAt = 0L,
		)
		db.getTrackingAnchorsDao().upsert(anchor)
		if (settings.isLiquidGlass) {
			db.getTracksDao().clearCounter(mangaId)
		}
	}

	suspend fun resetTrackingAnchorsOnHistoryDeleted(ids: Collection<Long>) = db.withTransaction {
		for (id in ids) {
			resetTrackingAnchorOnHistoryDeleted(id)
		}
	}

	suspend fun resetAllTrackingAnchorsOnHistoryCleared() = db.withTransaction {
		val allAnchorIds = db.getTrackingAnchorsDao().findAllIds()
		for (id in allAnchorIds) {
			resetTrackingAnchorOnHistoryDeleted(id)
		}
	}

	suspend fun dismissNewReleases(mangaId: Long, volume: Int?, number: Float?) = db.withTransaction {
		db.getTrackingAnchorsDao().dismissUntil(mangaId, volume, number)
		if (settings.isLiquidGlass) {
			db.getTracksDao().clearCounter(mangaId)
		}
	}

	suspend fun muteManga(mangaId: Long) {
		db.getTrackingAnchorsDao().mute(mangaId)
	}

	suspend fun unmuteManga(mangaId: Long) {
		db.getTrackingAnchorsDao().unmute(mangaId)
	}

	suspend fun getCachedChapters(mangaId: Long): List<MangaChapter> {
		return db.getChaptersDao().findAll(mangaId).toMangaChapters()
	}

	suspend fun getAnchor(mangaId: Long): TrackingAnchorEntity? {
		return db.getTrackingAnchorsDao().find(mangaId)
	}

	suspend fun updatePendingShrink(mangaId: Long, count: Int, hash: Long) {
		db.getTrackingAnchorsDao().updatePendingShrink(mangaId, count, hash)
	}

	suspend fun clearPendingShrink(mangaId: Long) {
		db.getTrackingAnchorsDao().clearPendingShrink(mangaId)
	}

	fun observeNewChaptersRail(
		categoryIds: Collection<Long>?,
		includeNsfw: Boolean,
		limit: Int = 20,
	): Flow<List<MangaWithAnchor>> {
		return db.getTrackingAnchorsDao().observeRailManga(categoryIds, includeNsfw, limit)
	}

	suspend fun syncTracksToAnchors() = db.withTransaction {
		val dao = db.getTrackingAnchorsDao()
		dao.insertMissingFromTracks()
		dao.updateZeroedFromTracks()
		recomputeStaleCountersIfNeeded()
	}

	private suspend fun recomputeStaleCountersIfNeeded() {
		if (settings.trackingCounterVersion >= CURRENT_COUNTER_VERSION) return
		val anchorsWithUpdates = db.getTrackingAnchorsDao().findAllWithNewReleases()
		for (anchor in anchorsWithUpdates) {
			val chapters = db.getChaptersDao().findAll(anchor.mangaId)
			if (chapters.isNotEmpty()) {
				applyTrackingResult(
					mangaId = anchor.mangaId,
					preferredBranch = anchor.branch,
				)
			}
		}
		settings.trackingCounterVersion = CURRENT_COUNTER_VERSION
	}

	suspend fun onLiquidGlassToggled(enabled: Boolean) = db.withTransaction {
		if (!enabled) {
			db.getTracksDao().clearCounters()
			db.getTrackingAnchorsDao().clear()
		} else {
			syncTracksToAnchors()
		}
	}

	suspend fun mergeWith(tracking: MangaTracking) {
		val entity = TrackEntity(
			mangaId = tracking.manga.id,
			lastChapterId = tracking.lastChapterId,
			newChapters = tracking.newChapters,
			lastCheckTime = tracking.lastCheck?.toEpochMilli() ?: 0L,
			lastChapterDate = tracking.lastChapterDate?.toEpochMilli() ?: 0L,
			lastResult = TrackEntity.RESULT_EXTERNAL_MODIFICATION,
			lastError = null,
		)
		db.getTracksDao().upsert(entity)
	}

	suspend fun getCategoriesCount(): IntArray {
		val categories = db.getFavouriteCategoriesDao().findAll()
		return intArrayOf(
			categories.count { it.track },
			categories.size,
		)
	}

	suspend fun updateTracks() = db.withTransaction {
		val dao = db.getTracksDao()
		dao.gc()
		val ids = dao.findAllIds().toMutableSet()
		val size = ids.size
		var addedFromHistory = 0
		var addedFromFavourites = 0
		// A manga can be tracked by both history and favourites: once the history pass has
		// handled an id, the favourites pass must not touch it — recreating the row would
		// wipe its baseline (lastChapterId), silently resetting the track on every run.
		val handled = HashSet<Long>(size)
		// history
		if (AppSettings.TRACK_HISTORY in settings.trackSources) {
			val historyIds = db.getHistoryDao().findAllIds()
			for (mangaId in historyIds) {
				if (handled.add(mangaId) && !ids.remove(mangaId)) {
					dao.upsert(TrackEntity.create(mangaId))
					addedFromHistory++
				}
			}
		}
		// favorites
		if (AppSettings.TRACK_FAVOURITES in settings.trackSources) {
			val favoritesIds = db.getFavouritesDao().findIdsWithTrack()
			for (mangaId in favoritesIds) {
				if (handled.add(mangaId) && !ids.remove(mangaId)) {
					dao.upsert(TrackEntity.create(mangaId))
					addedFromFavourites++
				}
			}
		}
		// remove unused
		for (mangaId in ids) {
			dao.delete(mangaId)
		}
		val anchorsDao = db.getTrackingAnchorsDao()
		anchorsDao.insertMissingFromTracks()
		anchorsDao.updateZeroedFromTracks()
		if (addedFromHistory != 0 || addedFromFavourites != 0 || ids.isNotEmpty()) {
			Log.i(
				TAG,
				"updateTracks: existing=$size, +history=$addedFromHistory, +favourites=$addedFromFavourites, " +
					"removed=${ids.size}" +
					(if (ids.isEmpty()) "" else " (ids: ${ids.take(REMOVED_IDS_LOG_LIMIT)})") +
					" trackSources=${settings.trackSources}",
			)
		}
		size - ids.size
	}

	private suspend fun getOrCreateTrack(mangaId: Long): TrackEntity {
		return db.getTracksDao().find(mangaId) ?: TrackEntity.create(mangaId)
	}

	private fun TrackEntity.mergeWith(updates: MangaUpdates): TrackEntity {
		return when (updates) {
			is MangaUpdates.Failure -> TrackEntity(
				mangaId = mangaId,
				lastChapterId = lastChapterId,
				newChapters = newChapters,
				lastCheckTime = System.currentTimeMillis(),
				lastChapterDate = lastChapterDate,
				lastResult = TrackEntity.RESULT_FAILED,
				lastError = updates.error?.toString(),
			)

			is MangaUpdates.Success -> {
				val chapters = updates.manga.getChapters(updates.branch)
				TrackEntity(
					mangaId = mangaId,
					lastChapterId = chapters.lastOrNull()?.id ?: NO_ID,
					// Cap at the total chapter count: the unread counter can never exceed how many
					// chapters exist, even if a transient detection glitch tries to inflate it.
					newChapters = if (updates.isValid) {
						(newChapters + updates.newChapters.size).coerceIn(0, chapters.size)
					} else {
						0
					},
					lastCheckTime = System.currentTimeMillis(),
					lastChapterDate = updates.lastChapterDate().ifZero { lastChapterDate },
					lastResult = if (updates.isNotEmpty()) TrackEntity.RESULT_HAS_UPDATE else TrackEntity.RESULT_NO_UPDATE,
					lastError = null,
				)
			}
		}
	}

	private suspend fun gcIfNotCalled() {
		if (isGcCalled.compareAndSet(false, true)) {
			gc()
		}
	}

	private companion object {

		const val TAG = "Tracker"
		const val CURRENT_COUNTER_VERSION = 1
		const val MAX_STALE_UPDATE_DAYS = 90L
		const val REMOVED_IDS_LOG_LIMIT = 20

		// Enough for the release days StaleTrackPolicy looks at, unless several chapters land on most of them
		const val RELEASE_DATES_LIMIT = 64
	}
}

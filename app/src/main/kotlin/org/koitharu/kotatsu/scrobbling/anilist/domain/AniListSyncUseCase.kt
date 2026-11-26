package org.koitharu.kotatsu.scrobbling.anilist.domain

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.getPreferredBranch
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.history.data.toMangaHistory
import org.koitharu.kotatsu.scrobbling.anilist.data.AniListAuthExpiredException
import org.koitharu.kotatsu.scrobbling.anilist.data.AniListRepository
import org.koitharu.kotatsu.scrobbling.anilist.data.AniListSyncLogEntry
import org.koitharu.kotatsu.scrobbling.anilist.data.AniListSyncLogStore
import org.koitharu.kotatsu.scrobbling.anilist.data.AniListSyncStateStore
import org.koitharu.kotatsu.scrobbling.anilist.data.AniListSyncStatus
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.ui.config.ScrobblerConfigActivity
import org.koitharu.kotatsu.tracker.domain.TrackingRepository
import org.koitharu.kotatsu.tracker.work.TrackerNotificationHelper
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AniListSyncResult {
	data class Success(val updatedCount: Int) : AniListSyncResult
	data class Throttled(val lastSyncTime: Long) : AniListSyncResult
	data object NotAuthorized : AniListSyncResult
	data object AuthExpired : AniListSyncResult
	data object Disabled : AniListSyncResult
	data object AlreadyRunning : AniListSyncResult
	data class Error(val throwable: Throwable) : AniListSyncResult
}

@Singleton
class AniListSyncUseCase @Inject constructor(
	@ApplicationContext private val context: Context,
	private val db: MangaDatabase,
	private val aniListRepository: AniListRepository,
	private val mangaDataRepository: MangaDataRepository,
	private val trackingRepository: TrackingRepository,
	private val settings: AppSettings,
	private val syncLogStore: AniListSyncLogStore,
	private val syncStateStore: AniListSyncStateStore,
) {
	var clock: Clock = Clock.systemUTC()
	private val syncMutex = Mutex()

	suspend fun sync(force: Boolean = false): AniListSyncResult = withContext(Dispatchers.IO) {
		if (!aniListRepository.isAuthorized) {
			return@withContext AniListSyncResult.NotAuthorized
		}

		if (!settings.isAniListSyncEnabled && !force) {
			return@withContext AniListSyncResult.Disabled
		}

		val now = clock.millis()
		val lastSyncTime = settings.aniListLastSyncTime
		if (!force && lastSyncTime > 0 && (now - lastSyncTime < THROTTLE_DURATION_MS)) {
			return@withContext AniListSyncResult.Throttled(lastSyncTime)
		}

		if (!syncMutex.tryLock()) {
			return@withContext AniListSyncResult.AlreadyRunning
		}

		try {
			val user = aniListRepository.cachedUser ?: aniListRepository.loadUser()
			val linkedEntities = db.getScrobblingDao().findAllByScrobbler(ScrobblerService.ANILIST.id)
			if (linkedEntities.isEmpty()) {
				settings.aniListLastSyncTime = now
				return@withContext AniListSyncResult.Success(0)
			}

			// Group by targetId (mediaId) since one AniList title may be linked to multiple local sources
			val linksByMediaId = linkedEntities.groupBy { it.targetId }

			// Watermark: sync boundary tracked from AniList's data (updatedAt in seconds)
			val sinceTimestampSeconds = settings.aniListLastSyncRemoteUpdatedAt
			var maxRemoteUpdatedAtSec = sinceTimestampSeconds

			val updatedEntries = aniListRepository.fetchMediaListUpdatedSince(user.id, sinceTimestampSeconds)
			var totalUpdated = 0
			val newLogs = ArrayList<AniListSyncLogEntry>()

			for (entry in updatedEntries) {
				val entryUpdatedAtSec = entry.updatedAtSeconds
				if (entryUpdatedAtSec > maxRemoteUpdatedAtSec) {
					maxRemoteUpdatedAtSec = entryUpdatedAtSec
				}

				val links = linksByMediaId[entry.mediaId] ?: continue
				for (link in links) {
					val mangaId = link.mangaId
					val manga = mangaDataRepository.findMangaById(mangaId, withChapters = true) ?: continue
					val chapters = manga.chapters
					if (chapters.isNullOrEmpty()) {
						// Chapters not cached; skip for now
						continue
					}

					val existingHistory = db.getHistoryDao().find(mangaId)?.toMangaHistory()
					val anchor = db.getTrackingAnchorsDao().find(mangaId)
					val preferredBranch = anchor?.branch ?: manga.getPreferredBranch(null)

					val localProgress = AniListProgressMerger.calculateLocalProgress(
						existingHistory,
						chapters.filter { preferredBranch == null || it.branch == preferredBranch || it.branch == null }.ifEmpty { chapters },
					)

					// Check reread protection & per-title disabled status via syncStateStore
					val shouldPull = syncStateStore.shouldPull(
						mangaId = mangaId,
						remoteProgress = entry.progress,
						localProgress = localProgress,
					)
					if (!shouldPull) {
						continue
					}

					val mergeResult = AniListProgressMerger.merge(
						remoteProgress = entry.progress,
						remoteStatus = entry.status,
						currentHistory = existingHistory,
						cachedChapters = chapters,
						preferredBranch = preferredBranch,
					)

					when (mergeResult) {
						is AniListProgressMerger.MergeResult.Advance -> {
							val remoteTimestamp = entry.updatedAt?.takeIf { it > 0L } ?: now
							val historyEntity = HistoryEntity(
								mangaId = mangaId,
								createdAt = existingHistory?.createdAt?.toEpochMilli() ?: remoteTimestamp,
								updatedAt = remoteTimestamp,
								chapterId = mergeResult.targetChapter.id,
								page = 0,
								scroll = 0f,
								percent = mergeResult.percent,
								chaptersCount = chapters.size,
								deletedAt = 0L,
							)
							db.getHistoryDao().upsert(historyEntity)

							if (settings.isLiquidGlass) {
								trackingRepository.applyTrackingResult(
									mangaId = mangaId,
									preferredBranch = preferredBranch,
									isForwardRead = true,
									isImport = false,
								)
							}

							// Update scrobbling entity to mirror AniList progress
							db.getScrobblingDao().upsert(
								ScrobblingEntity(
									scrobbler = link.scrobbler,
									id = link.id,
									mangaId = link.mangaId,
									targetId = link.targetId,
									status = entry.status,
									chapter = entry.progress,
									comment = entry.notes ?: link.comment,
									rating = entry.score,
								),
							)

							// Record last seen remote state to protect against overwriting future rereads
							syncStateStore.updateLastSeen(
								mangaId = mangaId,
								mediaId = entry.mediaId,
								progress = entry.progress,
								updatedAtSec = entryUpdatedAtSec,
								isClamped = mergeResult.isClamped,
							)

							// Cancel pending new chapters notification for this manga
							runCatching {
								val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
								nm?.cancel(mangaId.hashCode())
								nm?.cancel(mangaId.toInt())
							}

							newLogs.add(
								AniListSyncLogEntry(
									mangaId = mangaId,
									title = manga.title,
									fromChapter = mergeResult.localProgress,
									toChapter = mergeResult.remoteProgress,
									timestamp = remoteTimestamp,
									isClamped = mergeResult.isClamped,
									status = if (mergeResult.isClamped) AniListSyncStatus.CLAMPED else AniListSyncStatus.SYNCED,
								),
							)
							totalUpdated++
						}

						is AniListProgressMerger.MergeResult.FlaggedMismatch -> {
							Log.w(
								TAG,
								"Skipped sync for \"${manga.title}\": remote progress ${entry.progress} " +
									"is far beyond source latest ${mergeResult.latestChapterNumber} (suspected wrong match)",
							)
							val remoteTimestamp = entry.updatedAt?.takeIf { it > 0L } ?: now
							newLogs.add(
								AniListSyncLogEntry(
									mangaId = mangaId,
									title = manga.title,
									fromChapter = localProgress,
									toChapter = entry.progress,
									timestamp = remoteTimestamp,
									isClamped = false,
									status = AniListSyncStatus.SKIPPED_FAR_AHEAD,
									sourceLatestChapter = mergeResult.latestChapterNumber,
									canApplyAnyway = true,
								),
							)
						}

						AniListProgressMerger.MergeResult.NoOp,
						AniListProgressMerger.MergeResult.NeedChapters -> Unit
					}
				}
			}

			if (newLogs.isNotEmpty()) {
				syncLogStore.addEntries(newLogs)
			}

			// Only advance the watermark after a pass completes successfully without errors!
			if (maxRemoteUpdatedAtSec > settings.aniListLastSyncRemoteUpdatedAt) {
				settings.aniListLastSyncRemoteUpdatedAt = maxRemoteUpdatedAtSec
			}
			settings.aniListLastSyncTime = now
			AniListSyncResult.Success(totalUpdated)
		} catch (e: AniListAuthExpiredException) {
			e.printStackTraceDebug()
			showAuthExpiredNotification()
			AniListSyncResult.AuthExpired
		} catch (e: Exception) {
			e.printStackTraceDebug()
			AniListSyncResult.Error(e)
		} finally {
			syncMutex.unlock()
		}
	}

	suspend fun forceApply(mangaId: Long, remoteProgress: Int): Boolean = withContext(Dispatchers.IO) {
		val manga = mangaDataRepository.findMangaById(mangaId, withChapters = true) ?: return@withContext false
		val chapters = manga.chapters ?: return@withContext false
		val existingHistory = db.getHistoryDao().find(mangaId)?.toMangaHistory()
		val anchor = db.getTrackingAnchorsDao().find(mangaId)
		val preferredBranch = anchor?.branch ?: manga.getPreferredBranch(null)

		val mergeResult = AniListProgressMerger.merge(
			remoteProgress = remoteProgress,
			remoteStatus = "CURRENT",
			currentHistory = existingHistory,
			cachedChapters = chapters,
			preferredBranch = preferredBranch,
			allowMismatch = true, // explicit user override
		)

		if (mergeResult !is AniListProgressMerger.MergeResult.Advance) {
			return@withContext false
		}

		val now = clock.millis()
		val historyEntity = HistoryEntity(
			mangaId = mangaId,
			createdAt = existingHistory?.createdAt?.toEpochMilli() ?: now,
			updatedAt = now,
			chapterId = mergeResult.targetChapter.id,
			page = 0,
			scroll = 0f,
			percent = mergeResult.percent,
			chaptersCount = chapters.size,
			deletedAt = 0L,
		)
		db.getHistoryDao().upsert(historyEntity)

		if (settings.isLiquidGlass) {
			trackingRepository.applyTrackingResult(
				mangaId = mangaId,
				preferredBranch = preferredBranch,
				isForwardRead = true,
				isImport = false,
			)
		}

		syncStateStore.updateLastSeen(
			mangaId = mangaId,
			mediaId = 0L,
			progress = remoteProgress,
			updatedAtSec = now / 1000L,
		)

		val existingEntries = syncLogStore.entries.value
		val match = existingEntries.firstOrNull { it.mangaId == mangaId && it.canApplyAnyway }
		if (match != null) {
			syncLogStore.updateEntry(
				match.copy(
					status = AniListSyncStatus.SYNCED,
					canApplyAnyway = false,
					toChapter = remoteProgress,
				),
			)
		}
		true
	}

	private fun showAuthExpiredNotification() {
		runCatching {
			val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
			val intent = Intent(Intent.ACTION_VIEW, Uri.parse("kotatsu://anilist-auth")).apply {
				setClass(context, ScrobblerConfigActivity::class.java)
				flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
			}
			val pi = PendingIntent.getActivity(
				context,
				ScrobblerService.ANILIST.id,
				intent,
				PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
			)
			val notification = NotificationCompat.Builder(context, TrackerNotificationHelper.CHANNEL_ID)
				.setSmallIcon(R.drawable.ic_notification)
				.setContentTitle(context.getString(R.string.anilist))
				.setContentText("AniList session has expired. Tap to log in again.")
				.setContentIntent(pi)
				.setAutoCancel(true)
				.build()
			nm.notify(NOTIFICATION_ID_AUTH_EXPIRED, notification)
		}.onFailure { it.printStackTraceDebug() }
	}

	companion object {
		private const val TAG = "AniListSync"
		private const val NOTIFICATION_ID_AUTH_EXPIRED = 99881
		private val THROTTLE_DURATION_MS = TimeUnit.MINUTES.toMillis(30)
	}
}

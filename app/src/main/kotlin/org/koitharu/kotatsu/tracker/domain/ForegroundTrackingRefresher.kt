package org.koitharu.kotatsu.tracker.domain

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.tracker.data.TrackingAnchorEntity
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ForegroundTrackingRefresher @Inject constructor(
	private val settings: AppSettings,
	private val trackingRepository: TrackingRepository,
	private val getTracksUseCase: GetTracksUseCase,
	private val checkNewChaptersUseCase: CheckNewChaptersUseCase,
	private val networkState: NetworkState,
	private val aniListSyncUseCase: dagger.Lazy<org.koitharu.kotatsu.scrobbling.anilist.domain.AniListSyncUseCase>,
) {
	private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
	private val _isRefreshing = MutableStateFlow(false)
	val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

	private val globalSemaphore = Semaphore(4)
	private val sourceSemaphores = ConcurrentHashMap<String, Semaphore>()
	private val lastAutoRefreshTime = java.util.concurrent.atomic.AtomicLong(0L)
	private val blockedInSession = ConcurrentHashMap.newKeySet<Long>()

	private fun getSourceSemaphore(sourceName: String): Semaphore {
		return sourceSemaphores.computeIfAbsent(sourceName) { Semaphore(2) }
	}

	fun triggerAutoRefresh(delayMs: Long = 2500L) {
		val now = System.currentTimeMillis()
		scope.launch {
			kotlinx.coroutines.delay(delayMs)
			if (settings.isLiquidGlass && now - lastAutoRefreshTime.get() >= AUTO_REFRESH_INTERVAL_MS) {
				lastAutoRefreshTime.set(System.currentTimeMillis())
				triggerRefresh(force = false)
			}
			runCatching {
				aniListSyncUseCase.get().sync(force = false)
			}
		}
	}

	fun triggerRefresh(force: Boolean = false) {
		if (!settings.isLiquidGlass) return
		if (settings.isTrackerWifiOnly && networkState.isMetered()) {
			Log.i(TAG, "Foreground refresh skipped: Wi-Fi only enabled on metered network")
			return
		}
		if (!networkState.isOnline()) {
			Log.i(TAG, "Foreground refresh skipped: network offline")
			return
		}
		if (!_isRefreshing.compareAndSet(false, true)) {
			Log.d(TAG, "Foreground refresh already in progress")
			return
		}

		scope.launch {
			val startTime = System.currentTimeMillis()
			try {
				val tracks = getTracksUseCase(limit = MAX_BATCH_SIZE)
				val now = System.currentTimeMillis()

				val eligibleTracks = tracks.filter { track ->
					if (force) return@filter true
					val anchor = trackingRepository.getAnchor(track.manga.id) ?: return@filter true
					if (anchor.status == TrackingAnchorEntity.STATUS_BROKEN) return@filter false
					if (anchor.status == TrackingAnchorEntity.STATUS_BLOCKED && track.manga.id in blockedInSession) {
						return@filter false
					}

					val failCount = anchor.failCount
					if (failCount > 0) {
						val baseHours = if (anchor.status == TrackingAnchorEntity.STATUS_BLOCKED) 4L else 1L
						val backoffHours = (baseHours shl (failCount - 1)).coerceAtMost(24L)
						val backoffMs = backoffHours * 3600_000L
						(now - anchor.lastCheckedAt) >= backoffMs
					} else {
						val lastChapterDate = track.lastChapterDate?.toEpochMilli()
						val minIntervalMs = when {
							lastChapterDate != null && (now - lastChapterDate) > SIXTY_DAYS_MS -> INTERVAL_HIATUS_MS
							lastChapterDate != null && (now - lastChapterDate) <= SEVEN_DAYS_MS -> INTERVAL_ACTIVE_MS
							else -> INTERVAL_DEFAULT_MS
						}
						(now - anchor.lastCheckedAt) >= minIntervalMs
					}
				}

				val skippedThrottle = tracks.size - eligibleTracks.size
				Log.i(TAG, "Foreground refresh running for ${eligibleTracks.size}/${tracks.size} eligible tracks ($skippedThrottle skipped by throttle)")

				val failedPerSource = ConcurrentHashMap<String, Int>()

				val jobs = eligibleTracks.map { track ->
					launch {
						val sourceSemaphore = getSourceSemaphore(track.manga.source.name)
						globalSemaphore.withPermit {
							sourceSemaphore.withPermit {
								runCatching {
									checkNewChaptersUseCase.invoke(track)
								}.onFailure { e ->
									Log.w(TAG, "Check failed for manga ${track.manga.id}: ${e.message}")
									failedPerSource.compute(track.manga.source.name) { _, count -> (count ?: 0) + 1 }
									if (e is org.koitharu.kotatsu.core.exceptions.CloudFlareException) {
										blockedInSession.add(track.manga.id)
									}
								}
							}
						}
					}
				}
				kotlinx.coroutines.joinAll(*jobs.toTypedArray())
				val durationMs = System.currentTimeMillis() - startTime
				Log.i(
					TAG,
					"Refresh summary: checked=${eligibleTracks.size}, skipped_throttle=$skippedThrottle, " +
						"failed_by_source=${failedPerSource.toMap()}, time_taken=${durationMs}ms",
				)
			} catch (e: Exception) {
				Log.w(TAG, "Foreground refresh error", e)
			} finally {
				_isRefreshing.value = false
			}
		}
	}

	companion object {
		private const val TAG = "ForegroundRefresher"
		private const val MAX_BATCH_SIZE = 40
		private const val AUTO_REFRESH_INTERVAL_MS = 10 * 60_000L // 10 minutes between auto-checks
		private const val INTERVAL_ACTIVE_MS = 15 * 60_000L // 15 minutes for releases within 7 days
		private const val INTERVAL_DEFAULT_MS = 60 * 60_000L // 1 hour
		private const val INTERVAL_HIATUS_MS = 12 * 60 * 60_000L // 12 hours for hiatus (>60 days)
		private const val SEVEN_DAYS_MS = 7 * 24 * 60 * 60_000L
		private const val SIXTY_DAYS_MS = 60 * 24 * 60 * 60_000L
	}
}

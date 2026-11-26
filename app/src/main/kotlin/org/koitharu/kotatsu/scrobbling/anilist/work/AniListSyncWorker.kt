package org.koitharu.kotatsu.scrobbling.anilist.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.Reusable
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.koitharu.kotatsu.scrobbling.anilist.domain.AniListSyncResult
import org.koitharu.kotatsu.scrobbling.anilist.domain.AniListSyncUseCase
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltWorker
class AniListSyncWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted params: WorkerParameters,
	private val syncUseCase: AniListSyncUseCase,
) : CoroutineWorker(context, params) {

	override suspend fun doWork(): Result {
		val result = syncUseCase.sync(force = false)
		return when (result) {
			is AniListSyncResult.Success,
			is AniListSyncResult.Throttled,
			is AniListSyncResult.Disabled,
			is AniListSyncResult.AlreadyRunning,
			is AniListSyncResult.NotAuthorized,
			is AniListSyncResult.AuthExpired -> Result.success()
			is AniListSyncResult.Error -> Result.retry()
		}
	}

	@Reusable
	class Scheduler @Inject constructor(
		private val workManager: WorkManager,
	) {
		fun schedule() {
			val constraints = Constraints.Builder()
				.setRequiredNetworkType(NetworkType.CONNECTED)
				.build()
			val request = PeriodicWorkRequestBuilder<AniListSyncWorker>(8, TimeUnit.HOURS)
				.setConstraints(constraints)
				.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
				.addTag(TAG)
				.build()
			workManager.enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.KEEP, request)
		}

		fun unschedule() {
			workManager.cancelUniqueWork(TAG)
		}
	}

	companion object {
		const val TAG = "anilist_progress_sync"
	}
}

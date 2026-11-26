package org.koitharu.kotatsu.scrobbling.anilist.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.scrobbling.anilist.domain.AniListImportUseCase
import org.koitharu.kotatsu.scrobbling.anilist.domain.model.AniListImportPlan

@HiltWorker
class AniListImportWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted params: WorkerParameters,
	private val importUseCase: AniListImportUseCase,
) : CoroutineWorker(context, params) {

	override suspend fun doWork(): Result {
		runCatching { setForeground(getForegroundInfo(0, 0, null, isResolving = true)) }

		return try {
			val plan = currentPlan ?: importUseCase.loadCachedPlan() ?: importUseCase.resolve { current, total, title ->
				runCatching { setForeground(getForegroundInfo(current, total, title, isResolving = true)) }
			}

			val count = importUseCase.apply(plan) { current, total ->
				runCatching { setForeground(getForegroundInfo(current, total, null, isResolving = false)) }
			}

			currentPlan = null
			notifyComplete(count)
			Result.success()
		} catch (e: Exception) {
			e.printStackTraceDebug()
			Result.failure()
		}
	}

	override suspend fun getForegroundInfo(): ForegroundInfo = getForegroundInfo(0, 0, null, isResolving = true)

	private fun getForegroundInfo(current: Int, total: Int, title: String?, isResolving: Boolean): ForegroundInfo {
		ensureNotificationChannel()

		val titleText = if (isResolving) {
			applicationContext.getString(R.string.anilist_import_resolving)
		} else {
			applicationContext.getString(R.string.anilist_import_in_progress)
		}

		val contentText = buildString {
			if (!title.isNullOrBlank()) {
				append(title).append(" · ")
			}
			if (total > 0) {
				append(current).append(" / ").append(total)
			}
		}

		val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
			.setContentTitle(titleText)
			.setContentText(contentText)
			.setSmallIcon(android.R.drawable.stat_notify_sync)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.setOngoing(true)
			.setSilent(true)
			.setProgress(total, current, total == 0)
			.build()

		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
		} else {
			ForegroundInfo(NOTIFICATION_ID, notification)
		}
	}

	@android.annotation.SuppressLint("MissingPermission")
	private fun notifyComplete(count: Int) {
		ensureNotificationChannel()

		val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
			.setContentTitle(applicationContext.getString(R.string.import_from_anilist))
			.setContentText(applicationContext.getString(R.string.anilist_import_complete, count))
			.setSmallIcon(R.drawable.ic_anilist)
			.setPriority(NotificationCompat.PRIORITY_DEFAULT)
			.setAutoCancel(true)
			.build()

		if (NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) {
			runCatching {
				NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID + 1, notification)
			}
		}
	}

	private fun ensureNotificationChannel() {
		val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
			.setName(applicationContext.getString(R.string.anilist_channel_name))
			.setShowBadge(false)
			.setSound(null, null)
			.build()
		NotificationManagerCompat.from(applicationContext).createNotificationChannel(channel)
	}

	companion object {
		private const val CHANNEL_ID = "anilist_import"
		private const val NOTIFICATION_ID = 4512
		const val TAG = "anilist_import_worker"

		var currentPlan: AniListImportPlan? = null

		fun enqueue(context: Context, plan: AniListImportPlan? = null) {
			currentPlan = plan
			val request = OneTimeWorkRequestBuilder<AniListImportWorker>()
				.addTag(TAG)
				.build()
			WorkManager.getInstance(context).enqueueUniqueWork(
				TAG,
				ExistingWorkPolicy.KEEP,
				request,
			)
		}
	}
}

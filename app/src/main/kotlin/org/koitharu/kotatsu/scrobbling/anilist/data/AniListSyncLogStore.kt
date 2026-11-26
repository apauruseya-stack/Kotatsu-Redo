package org.koitharu.kotatsu.scrobbling.anilist.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class AniListSyncStatus {
	SYNCED,
	CLAMPED,
	SKIPPED_FAR_AHEAD,
	SKIPPED_REREADING,
	SKIPPED_DISABLED,
}

data class AniListSyncLogEntry(
	val mangaId: Long,
	val title: String,
	val fromChapter: Int,
	val toChapter: Int,
	val timestamp: Long,
	val isClamped: Boolean = false,
	val status: AniListSyncStatus = if (isClamped) AniListSyncStatus.CLAMPED else AniListSyncStatus.SYNCED,
	val sourceLatestChapter: Float = 0f,
	val canApplyAnyway: Boolean = status == AniListSyncStatus.SKIPPED_FAR_AHEAD,
)

@Singleton
class AniListSyncLogStore @Inject constructor(
	@ApplicationContext private val context: Context,
) {
	private val logFile get() = File(context.filesDir, "anilist_sync_log.json")
	private val mutex = Mutex()
	private val _entries = MutableStateFlow<List<AniListSyncLogEntry>>(emptyList())
	val entries: StateFlow<List<AniListSyncLogEntry>> = _entries.asStateFlow()

	init {
		loadFromDisk()
	}

	suspend fun addEntries(newItems: List<AniListSyncLogEntry>) = mutex.withLock {
		if (newItems.isEmpty()) return@withLock
		val updated = (newItems + _entries.value).take(MAX_LOG_ENTRIES)
		_entries.value = updated
		saveToDisk(updated)
	}

	suspend fun addEntry(entry: AniListSyncLogEntry) = addEntries(listOf(entry))

	suspend fun updateEntry(updated: AniListSyncLogEntry) = mutex.withLock {
		val list = _entries.value.map {
			if (it.mangaId == updated.mangaId && it.timestamp == updated.timestamp) updated else it
		}
		_entries.value = list
		saveToDisk(list)
	}

	suspend fun clear() = mutex.withLock {
		_entries.value = emptyList()
		runCatching { logFile.delete() }
	}

	private fun loadFromDisk() {
		if (!logFile.exists()) return
		runCatching {
			val json = JSONArray(logFile.readText())
			val list = ArrayList<AniListSyncLogEntry>(json.length())
			for (i in 0 until json.length()) {
				val obj = json.getJSONObject(i)
				val statusStr = obj.optString("status", "")
				val isClamped = obj.optBoolean("isClamped", false)
				val status = runCatching { AniListSyncStatus.valueOf(statusStr) }.getOrDefault(
					if (isClamped) AniListSyncStatus.CLAMPED else AniListSyncStatus.SYNCED,
				)
				list.add(
					AniListSyncLogEntry(
						mangaId = obj.getLong("mangaId"),
						title = obj.getString("title"),
						fromChapter = obj.getInt("fromChapter"),
						toChapter = obj.getInt("toChapter"),
						timestamp = obj.getLong("timestamp"),
						isClamped = isClamped,
						status = status,
						sourceLatestChapter = obj.optDouble("sourceLatestChapter", 0.0).toFloat(),
						canApplyAnyway = obj.optBoolean("canApplyAnyway", status == AniListSyncStatus.SKIPPED_FAR_AHEAD),
					),
				)
			}
			_entries.value = list
		}.onFailure { it.printStackTraceDebug() }
	}

	private fun saveToDisk(list: List<AniListSyncLogEntry>) {
		runCatching {
			val json = JSONArray()
			for (entry in list) {
				val obj = JSONObject()
				obj.put("mangaId", entry.mangaId)
				obj.put("title", entry.title)
				obj.put("fromChapter", entry.fromChapter)
				obj.put("toChapter", entry.toChapter)
				obj.put("timestamp", entry.timestamp)
				obj.put("isClamped", entry.isClamped)
				obj.put("status", entry.status.name)
				obj.put("sourceLatestChapter", entry.sourceLatestChapter)
				obj.put("canApplyAnyway", entry.canApplyAnyway)
				json.put(obj)
			}
			logFile.writeText(json.toString())
		}.onFailure { it.printStackTraceDebug() }
	}

	companion object {
		const val MAX_LOG_ENTRIES = 50
	}
}

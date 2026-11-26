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

data class LinkSyncState(
	val mangaId: Long,
	val mediaId: Long,
	val lastSeenRemoteProgress: Int,
	val lastSeenRemoteUpdatedAtSec: Long,
	val isPullDisabled: Boolean = false,
	val isConfirmed: Boolean = true,
	val isClamped: Boolean = false,
)

@Singleton
class AniListSyncStateStore @Inject constructor(
	@ApplicationContext private val context: Context,
) {
	private val file get() = File(context.filesDir, "anilist_sync_state.json")
	private val mutex = Mutex()
	private val _states = MutableStateFlow<Map<Long, LinkSyncState>>(emptyMap())
	val states: StateFlow<Map<Long, LinkSyncState>> = _states.asStateFlow()

	init {
		loadFromDisk()
	}

	suspend fun getState(mangaId: Long): LinkSyncState? = mutex.withLock {
		_states.value[mangaId]
	}

	suspend fun updateLastSeen(
		mangaId: Long,
		mediaId: Long,
		progress: Int,
		updatedAtSec: Long,
		isClamped: Boolean = false,
	) = mutex.withLock {
		val current = _states.value[mangaId]
		val updated = (current ?: LinkSyncState(
			mangaId = mangaId,
			mediaId = mediaId,
			lastSeenRemoteProgress = progress,
			lastSeenRemoteUpdatedAtSec = updatedAtSec,
			isClamped = isClamped,
		)).copy(
			lastSeenRemoteProgress = progress,
			lastSeenRemoteUpdatedAtSec = updatedAtSec,
			isClamped = isClamped,
		)
		val newMap = _states.value + (mangaId to updated)
		_states.value = newMap
		saveToDisk(newMap)
	}

	suspend fun setPullDisabled(mangaId: Long, disabled: Boolean) = mutex.withLock {
		val current = _states.value[mangaId] ?: return@withLock
		val updated = current.copy(isPullDisabled = disabled)
		val newMap = _states.value + (mangaId to updated)
		_states.value = newMap
		saveToDisk(newMap)
	}

	suspend fun setConfirmed(mangaId: Long, confirmed: Boolean) = mutex.withLock {
		val current = _states.value[mangaId] ?: return@withLock
		val updated = current.copy(isConfirmed = confirmed)
		val newMap = _states.value + (mangaId to updated)
		_states.value = newMap
		saveToDisk(newMap)
	}

	suspend fun shouldPull(
		mangaId: Long,
		remoteProgress: Int,
		localProgress: Int,
	): Boolean = mutex.withLock {
		val state = _states.value[mangaId]
		if (state != null) {
			if (state.isPullDisabled || !state.isConfirmed) {
				return@withLock false
			}
			if (state.isClamped) {
				// Clamped title: allow catch-up when source uploads missing chapters
				return@withLock remoteProgress > localProgress
			}
			// Only pull if remote value has actually changed / advanced since last sync
			// (prevents overwriting local rereads when remote is static)
			if (remoteProgress <= state.lastSeenRemoteProgress) {
				return@withLock false
			}
		}
		// Forward-only rule against local
		return@withLock remoteProgress > localProgress
	}

	suspend fun clear() = mutex.withLock {
		_states.value = emptyMap()
		runCatching { file.delete() }
	}

	private fun loadFromDisk() {
		if (!file.exists()) return
		runCatching {
			val json = JSONArray(file.readText())
			val map = HashMap<Long, LinkSyncState>(json.length())
			for (i in 0 until json.length()) {
				val obj = json.getJSONObject(i)
				val mangaId = obj.getLong("mangaId")
				map[mangaId] = LinkSyncState(
					mangaId = mangaId,
					mediaId = obj.getLong("mediaId"),
					lastSeenRemoteProgress = obj.getInt("lastSeenRemoteProgress"),
					lastSeenRemoteUpdatedAtSec = obj.optLong("lastSeenRemoteUpdatedAtSec", 0L),
					isPullDisabled = obj.optBoolean("isPullDisabled", false),
					isConfirmed = obj.optBoolean("isConfirmed", true),
					isClamped = obj.optBoolean("isClamped", false),
				)
			}
			_states.value = map
		}.onFailure { it.printStackTraceDebug() }
	}

	private fun saveToDisk(map: Map<Long, LinkSyncState>) {
		runCatching {
			val json = JSONArray()
			for (state in map.values) {
				val obj = JSONObject()
				obj.put("mangaId", state.mangaId)
				obj.put("mediaId", state.mediaId)
				obj.put("lastSeenRemoteProgress", state.lastSeenRemoteProgress)
				obj.put("lastSeenRemoteUpdatedAtSec", state.lastSeenRemoteUpdatedAtSec)
				obj.put("isPullDisabled", state.isPullDisabled)
				obj.put("isConfirmed", state.isConfirmed)
				obj.put("isClamped", state.isClamped)
				json.put(obj)
			}
			file.writeText(json.toString())
		}.onFailure { it.printStackTraceDebug() }
	}
}

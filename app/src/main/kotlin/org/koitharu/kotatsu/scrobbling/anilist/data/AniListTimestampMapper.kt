package org.koitharu.kotatsu.scrobbling.anilist.data

import org.json.JSONObject
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

object AniListTimestampMapper {

	const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000L

	fun defaultFallbackTimestamp(clock: Clock = Clock.systemUTC()): Long {
		return clock.millis() - THIRTY_DAYS_MS
	}

	fun parseSeconds(sec: Long?): Long? {
		return if (sec != null && sec > 0L) sec * 1000L else null
	}

	fun parseFuzzyDate(dateObj: JSONObject?): Long? {
		if (dateObj == null) return null
		val year = dateObj.optInt("year", 0)
		if (year <= 0) return null
		val month = dateObj.optInt("month", 1).coerceIn(1, 12)
		val day = dateObj.optInt("day", 1).coerceIn(1, 28)
		return runCatching {
			LocalDate.of(year, month, day)
				.atStartOfDay(ZoneOffset.UTC)
				.toInstant()
				.toEpochMilli()
		}.getOrNull()
	}

	fun resolveTimestamp(
		updatedAtSec: Long?,
		completedAt: JSONObject? = null,
		startedAt: JSONObject? = null,
		createdAtSec: Long? = null,
		fallbackTimestamp: Long? = null,
	): Long? {
		return parseSeconds(updatedAtSec)
			?: parseFuzzyDate(completedAt)
			?: parseFuzzyDate(startedAt)
			?: parseSeconds(createdAtSec)
			?: fallbackTimestamp
	}

	fun resolveTimestamp(entry: JSONObject, fallbackTimestamp: Long? = null): Long? {
		val updatedAtSec = if (entry.has("updatedAt") && !entry.isNull("updatedAt")) {
			entry.optLong("updatedAt", 0L)
		} else {
			null
		}
		val createdAtSec = if (entry.has("createdAt") && !entry.isNull("createdAt")) {
			entry.optLong("createdAt", 0L)
		} else {
			null
		}
		val completedAt = entry.optJSONObject("completedAt")
		val startedAt = entry.optJSONObject("startedAt")
		return resolveTimestamp(updatedAtSec, completedAt, startedAt, createdAtSec, fallbackTimestamp)
	}
}

package org.koitharu.kotatsu.scrobbling.anilist.domain.model

import java.util.Locale

/**
 * Single source of truth for AniList category names across the application.
 */
object AniListCategoryNames {
	const val READING = "Reading"
	const val COMPLETED = "Completed"
	const val PAUSED = "Paused"
	const val DROPPED = "Dropped"
	const val PLANNING = "Planning"

	val ALL = listOf(READING, COMPLETED, PAUSED, DROPPED, PLANNING)

	fun fromStatus(status: String): String = when (status.uppercase(Locale.ROOT)) {
		"CURRENT", "REPEATING" -> READING
		"COMPLETED" -> COMPLETED
		"PAUSED" -> PAUSED
		"DROPPED" -> DROPPED
		"PLANNING", "PLAN_TO_READ", "READ_LATER" -> PLANNING
		else -> READING
	}

	fun matchesReading(title: String?): Boolean {
		return title != null && title.equals(READING, ignoreCase = true)
	}
}

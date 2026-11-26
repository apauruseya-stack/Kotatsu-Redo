package org.koitharu.kotatsu.scrobbling.anilist.domain.model

data class AniListMediaEntry(
	val entryId: Int,
	val mediaId: Long,
	val status: String,
	val progress: Int,
	val score: Float,
	val notes: String?,
	val preferredTitle: String,
	val allTitles: List<String>,
	val totalChapters: Int?,
	val coverUrl: String?,
	val siteUrl: String?,
	val format: String?,
	val updatedAt: Long? = null,
	val updatedAtSeconds: Long = (updatedAt ?: 0L) / 1000L,
	val isAdult: Boolean = false,
)

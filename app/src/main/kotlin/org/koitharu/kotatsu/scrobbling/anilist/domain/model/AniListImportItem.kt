package org.koitharu.kotatsu.scrobbling.anilist.domain.model

import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.scrobbling.anilist.domain.MatchConfidence

enum class ImportDecision {
	AUTO_ACCEPT,
	REVIEW,
	ALREADY_LINKED,
	NOT_FOUND,
	IGNORED,
}

data class AniListImportCandidate(
	val manga: Manga,
	val score: Float,
	val confidence: MatchConfidence,
	val reason: String?,
)

data class AniListImportItem(
	val entry: AniListMediaEntry,
	val decision: ImportDecision,
	val targetCategoryName: String,
	val matchedManga: Manga?,
	val alternativeCandidates: List<AniListImportCandidate> = emptyList(),
	val existingLocalMangaId: Long? = null,
	val isSelected: Boolean = true,
)

data class AniListImportPlan(
	val items: List<AniListImportItem>,
	val createdAt: Long = System.currentTimeMillis(),
	val skippedAdultCount: Int = 0,
	val userId: Long? = null,
) {
	val autoAcceptedCount: Int get() = items.count { it.decision == ImportDecision.AUTO_ACCEPT }
	val reviewCount: Int get() = items.count { it.decision == ImportDecision.REVIEW }
	val alreadyLinkedCount: Int get() = items.count { it.decision == ImportDecision.ALREADY_LINKED }
	val notFoundCount: Int get() = items.count { it.decision == ImportDecision.NOT_FOUND }
	val selectedCount: Int get() = items.count { it.isSelected && (it.matchedManga != null || it.existingLocalMangaId != null) }
}

package org.koitharu.kotatsu.scrobbling.anilist.domain

import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.levenshteinDistance
import org.koitharu.kotatsu.scrobbling.anilist.domain.model.AniListMediaEntry
import java.util.Locale
import kotlin.math.max

enum class MatchConfidence {
	HIGH,
	UNCERTAIN,
	NONE,
}

data class MatchResult(
	val manga: Manga,
	val score: Float,
	val confidence: MatchConfidence,
	val reason: String? = null,
)

object AniListTitleMatcher {

	private val SEQUEL_KEYWORDS = setOf(
		"season", "part", "ragnarok", "re", "sequel", "prequel", "gaiden",
		"side", "story", "2nd", "3rd", "4th", "ii", "iii", "iv", "v",
	)
	private val PUNCTUATION_REGEX = Regex("[^a-z0-9\\s]")
	private val WHITESPACE_REGEX = Regex("\\s+")

	fun evaluate(aniListEntry: AniListMediaEntry, candidate: Manga): MatchResult {
		val candidateTitles = buildList {
			add(candidate.title)
			addAll(candidate.altTitles)
		}.filter { it.isNotBlank() }
		var bestScore = 0f
		var bestCandidateTitle = candidate.title
		var bestAniTitle = aniListEntry.preferredTitle

		for (aniTitle in aniListEntry.allTitles) {
			for (candTitle in candidateTitles) {
				val score = computeSimilarity(aniTitle, candTitle)
				if (score > bestScore) {
					bestScore = score
					bestCandidateTitle = candTitle
					bestAniTitle = aniTitle
				}
			}
		}

		// Check for sequel / subtitle mismatches against the best-matched AniList title
		val hasSequelMismatch = hasSequelTokenMismatch(bestAniTitle, bestCandidateTitle)
		if (hasSequelMismatch) {
			bestScore *= 0.65f
		}

		// Check chapter discrepancy if AniList has total chapters
		val chapterDiscrepancy = checkChapterDiscrepancy(aniListEntry.totalChapters, candidate.chapters?.size)
		if (chapterDiscrepancy) {
			bestScore *= 0.75f
		}

		val confidence = when {
			bestScore >= 0.85f && !hasSequelMismatch && !chapterDiscrepancy -> MatchConfidence.HIGH
			bestScore >= 0.60f -> MatchConfidence.UNCERTAIN
			else -> MatchConfidence.NONE
		}

		val reason = when {
			hasSequelMismatch -> "Possible sequel or subtitle variation"
			chapterDiscrepancy -> "Chapter count discrepancy with AniList"
			confidence == MatchConfidence.UNCERTAIN -> "Partial title match (${(bestScore * 100).toInt()}%)"
			else -> null
		}

		return MatchResult(candidate, bestScore, confidence, reason)
	}

	fun computeSimilarity(s1: String, s2: String): Float {
		val n1 = normalize(s1)
		val n2 = normalize(s2)
		if (n1.isEmpty() || n2.isEmpty()) return 0f
		if (n1 == n2) return 1.0f

		val maxLen = max(n1.length, n2.length)
		val dist = n1.levenshteinDistance(n2)
		val levenshteinScore = 1f - (dist.toFloat() / maxLen)

		// Token set similarity
		val tokens1 = n1.split(WHITESPACE_REGEX).toSet()
		val tokens2 = n2.split(WHITESPACE_REGEX).toSet()
		val intersection = tokens1.intersect(tokens2).size
		val union = tokens1.union(tokens2).size
		val jaccard = if (union > 0) intersection.toFloat() / union else 0f

		return (levenshteinScore * 0.6f + jaccard * 0.4f).coerceIn(0f, 1f)
	}

	private fun hasSequelTokenMismatch(bestAniTitle: String, candidateTitle: String): Boolean {
		val candTokens = normalize(candidateTitle).split(WHITESPACE_REGEX).toSet()
		val matchedAniTokens = normalize(bestAniTitle).split(WHITESPACE_REGEX).toSet()

		val candSequelTokens = candTokens.filter { it in SEQUEL_KEYWORDS || isNumberOrRoman(it) }.toSet()
		val aniSequelTokens = matchedAniTokens.filter { it in SEQUEL_KEYWORDS || isNumberOrRoman(it) }.toSet()

		return candSequelTokens != aniSequelTokens
	}

	private fun isNumberOrRoman(token: String): Boolean {
		return token.toIntOrNull() != null || token in setOf("ii", "iii", "iv", "v", "vi")
	}

	private fun checkChapterDiscrepancy(expectedChapters: Int?, sourceChapters: Int?): Boolean {
		if (expectedChapters == null || expectedChapters <= 0 || sourceChapters == null || sourceChapters <= 0) {
			return false
		}
		// If difference is more than 3x and count is significant
		if (expectedChapters < 20 && sourceChapters > 100) return true
		if (expectedChapters > 100 && sourceChapters < 20) return true
		return false
	}

	private fun normalize(text: String): String {
		return text.lowercase(Locale.ROOT)
			.replace(PUNCTUATION_REGEX, " ")
			.replace(WHITESPACE_REGEX, " ")
			.trim()
	}
}

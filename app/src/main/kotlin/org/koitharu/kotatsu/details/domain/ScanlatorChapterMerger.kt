package org.koitharu.kotatsu.details.domain

import org.koitharu.kotatsu.parsers.model.MangaChapter
import java.util.Locale

object ScanlatorPriorities {

	private val PRIORITY_PATTERNS = listOf(
		Regex("(?i).*official.*"),
		Regex("(?i).*webtoon.*"),
		Regex("(?i).*asura.*"),
		Regex("(?i).*tapas.*"),
		Regex("(?i).*tappytoon.*"),
		Regex("(?i).*flame.*"),
	)

	fun getPriority(scanlator: String?): Int {
		if (scanlator == null) return 1000
		val trimmed = scanlator.trim()
		for ((index, regex) in PRIORITY_PATTERNS.withIndex()) {
			if (regex.matches(trimmed)) {
				return index
			}
		}
		return 100
	}

	fun isAsuraOrOfficial(scanlator: String?): Boolean {
		if (scanlator == null) return false
		val s = scanlator.trim().lowercase(Locale.ROOT)
		return s.contains("asura") || s.contains("official") || s.contains("webtoon")
	}

	fun pickBestDefault(scanlators: Collection<String>, chapters: Collection<MangaChapter>): String? {
		if (scanlators.isEmpty()) return null
		val asura = scanlators.firstOrNull { it.contains("asura", ignoreCase = true) }
		val official = scanlators.firstOrNull {
			it.contains("official", ignoreCase = true) || it.contains("webtoon", ignoreCase = true)
		}

		if (asura != null) return asura
		if (official != null) return official

		val bestByPriority = scanlators.minByOrNull { getPriority(it) }
		if (bestByPriority != null && getPriority(bestByPriority) < 100) {
			return bestByPriority
		}

		val counts = chapters.groupingBy { it.scanlator?.trim().orEmpty() }.eachCount()
		return scanlators.maxByOrNull { counts[it] ?: 0 } ?: scanlators.firstOrNull()
	}
}

object ScanlatorChapterMerger {

	fun mergeChapters(
		chapters: List<MangaChapter>,
		selectedScanlator: String?,
		preferredScanlator: String?,
	): List<MangaChapter> {
		val distinctScanlators = chapters.mapNotNull { it.scanlator?.trim() }
			.filter { it.isNotBlank() }
			.distinct()

		if (distinctScanlators.size <= 1) {
			return chapters
		}

		// Specific scanlator filter
		if (!selectedScanlator.isNullOrEmpty() && !selectedScanlator.equals("ALL", ignoreCase = true)) {
			val filtered = chapters.filter { it.scanlator?.trim().equals(selectedScanlator.trim(), ignoreCase = true) }
			if (filtered.isNotEmpty()) {
				return filtered
			}
		}

		// Hybrid "All" Mode:
		val effectivePreferred = preferredScanlator
			?: ScanlatorPriorities.pickBestDefault(distinctScanlators, chapters)
			?: distinctScanlators.first()

		fun chapterKey(ch: MangaChapter): String {
			return if (ch.number > 0f) {
				"v:${ch.volume}:n:${ch.number}"
			} else {
				"t:${ch.title?.trim()?.lowercase(Locale.ROOT) ?: ch.id.toString()}"
			}
		}

		val groupedByKey = chapters.groupBy { chapterKey(it) }
		val result = ArrayList<MangaChapter>(groupedByKey.size)

		for ((_, groupChapters) in groupedByKey) {
			// 1. Try to pick from preferred scanlator
			val fromPreferred = groupChapters.firstOrNull {
				it.scanlator?.trim().equals(effectivePreferred.trim(), ignoreCase = true)
			}
			if (fromPreferred != null) {
				result.add(fromPreferred)
				continue
			}

			// 2. Fall back to best available group (e.g. newer chapter released by another group)
			val bestFallback = groupChapters.minWithOrNull(
				compareBy<MangaChapter> { ScanlatorPriorities.getPriority(it.scanlator) }
					.thenBy { if (it.uploadDate > 0) it.uploadDate else Long.MAX_VALUE }
					.thenBy { it.id }
			) ?: groupChapters.first()

			result.add(bestFallback)
		}

		val isDescending = if (chapters.size >= 2) {
			val firstNum = chapters.first().number
			val lastNum = chapters.last().number
			firstNum > lastNum
		} else {
			false
		}

		return if (isDescending) {
			result.sortedWith(compareByDescending<MangaChapter> { it.number }.thenByDescending { it.id })
		} else {
			result.sortedWith(compareBy<MangaChapter> { it.number }.thenBy { it.id })
		}
	}
}

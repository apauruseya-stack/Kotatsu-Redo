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

	fun pickBestDefault(groups: Collection<String>, chapters: Collection<MangaChapter>): String? {
		if (groups.isEmpty()) return null
		val asura = groups.firstOrNull { it.contains("asura", ignoreCase = true) }
		val official = groups.firstOrNull {
			it.contains("official", ignoreCase = true) || it.contains("webtoon", ignoreCase = true)
		}

		if (asura != null) return asura
		if (official != null) return official

		val bestByPriority = groups.minByOrNull { getPriority(it) }
		if (bestByPriority != null && getPriority(bestByPriority) < 100) {
			return bestByPriority
		}

		val counts = chapters.groupingBy {
			it.branch?.trim()?.takeIf { b -> b in groups }
				?: it.scanlator?.trim()?.takeIf { s -> s in groups }
				?: ""
		}.eachCount()
		return groups.maxByOrNull { counts[it] ?: 0 } ?: groups.firstOrNull()
	}
}

object ScanlatorChapterMerger {

	fun getChapterGroup(chapter: MangaChapter, useBranch: Boolean): String? {
		return if (useBranch) {
			chapter.branch?.trim()?.takeIf { it.isNotEmpty() } ?: chapter.scanlator?.trim()?.takeIf { it.isNotEmpty() }
		} else {
			chapter.scanlator?.trim()?.takeIf { it.isNotEmpty() } ?: chapter.branch?.trim()?.takeIf { it.isNotEmpty() }
		}
	}

	fun mergeChapters(
		chapters: List<MangaChapter>,
		selectedScanlator: String? = null,
		preferredScanlator: String? = null,
		useBranch: Boolean = false,
	): List<MangaChapter> {
		val distinctGroups = chapters.mapNotNull { getChapterGroup(it, useBranch) }
			.filter { it.isNotBlank() }
			.distinct()

		if (distinctGroups.size <= 1) {
			return chapters
		}

		// Specific group filter
		if (!selectedScanlator.isNullOrEmpty() && !selectedScanlator.equals("ALL", ignoreCase = true)) {
			val filtered = chapters.filter {
				getChapterGroup(it, useBranch).equals(selectedScanlator.trim(), ignoreCase = true)
			}
			if (filtered.isNotEmpty()) {
				return filtered
			}
		}

		// Hybrid "All" Mode:
		val effectivePreferred = preferredScanlator
			?: ScanlatorPriorities.pickBestDefault(distinctGroups, chapters)
			?: distinctGroups.first()

		fun chapterKey(ch: MangaChapter): String {
			return if (ch.number >= 0f) {
				"n:${ch.number}"
			} else {
				"t:${ch.title?.trim()?.lowercase(Locale.ROOT) ?: ch.id.toString()}"
			}
		}

		val groupedByKey = chapters.groupBy { chapterKey(it) }
		val result = ArrayList<MangaChapter>(groupedByKey.size)

		for ((_, groupChapters) in groupedByKey) {
			// 1. Try to pick from preferred group
			val fromPreferred = groupChapters.firstOrNull {
				getChapterGroup(it, useBranch).equals(effectivePreferred.trim(), ignoreCase = true)
			}
			if (fromPreferred != null) {
				result.add(fromPreferred)
				continue
			}

			// 2. Fall back to best available group (e.g. newer chapter released by another group)
			val bestFallback = groupChapters.minWithOrNull(
				compareBy<MangaChapter> { ScanlatorPriorities.getPriority(getChapterGroup(it, useBranch)) }
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

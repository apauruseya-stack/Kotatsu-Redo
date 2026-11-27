package org.koitharu.kotatsu.scrobbling.anilist.domain

import org.koitharu.kotatsu.core.model.MangaHistory
import org.koitharu.kotatsu.parsers.model.MangaChapter

object AniListProgressMerger {

	sealed interface MergeResult {
		/** Remote progress is behind or equal to local progress, or zero/volume-only. No action needed. */
		data object NoOp : MergeResult

		/** Remote progress is ahead of local progress and should advance local history. */
		data class Advance(
			val targetChapter: MangaChapter,
			val percent: Float,
			val isClamped: Boolean,
			val localProgress: Int,
			val remoteProgress: Int,
		) : MergeResult

		/** Remote progress is far beyond (> 1.5x) source's latest chapter. Suspected bad link / mismatched manga. */
		data class FlaggedMismatch(
			val remoteProgress: Int,
			val latestChapterNumber: Float,
		) : MergeResult

		/** Chapters are not cached for this manga yet; details need to be fetched first. */
		data object NeedChapters : MergeResult
	}

	fun merge(
		remoteProgress: Int,
		remoteStatus: String?,
		currentHistory: MangaHistory?,
		cachedChapters: List<MangaChapter>,
		preferredBranch: String? = null,
		allowMismatch: Boolean = false,
	): MergeResult {
		if (cachedChapters.isEmpty()) {
			return MergeResult.NeedChapters
		}

		if (remoteProgress <= 0) {
			return MergeResult.NoOp
		}

		val branchChapters = run {
			val filtered = cachedChapters.filter {
				preferredBranch == null || it.branch == preferredBranch || it.branch == null
			}
			if (filtered.isNotEmpty()) filtered else cachedChapters
		}

		val localProgress = calculateLocalProgress(currentHistory, branchChapters)

		// Forward-only policy: remote <= local is a no-op
		if (remoteProgress <= localProgress) {
			return MergeResult.NoOp
		}

		val latestChapter = branchChapters.last()
		val latestNum = getChapterNumber(latestChapter).let {
			if (it > 0f) it else branchChapters.size.toFloat()
		}

		// Guard: If remote progress is far beyond (> 1.5x) the source's latest chapter,
		// skip and flag it unless user chose "Apply anyway" (allowMismatch = true).
		// Compare ONLY against source latest (1.5x check), NEVER against distance from local!
		if (!allowMismatch && remoteProgress > (latestNum * 1.5f) && remoteProgress > (latestNum + 5)) {
			return MergeResult.FlaggedMismatch(remoteProgress, latestNum)
		}

		var matchedIndex = -1
		for (i in branchChapters.indices) {
			val num = getChapterNumber(branchChapters[i])
			if (num > 0f && num <= remoteProgress.toFloat()) {
				matchedIndex = i
			}
		}
		if (matchedIndex == -1) {
			matchedIndex = (remoteProgress - 1).coerceIn(0, branchChapters.lastIndex)
		}

		// If remote is slightly ahead of the source (e.g. source hasn't uploaded latest yet),
		// clamp to the latest available chapter.
		if (remoteProgress > latestNum) {
			return MergeResult.Advance(
				targetChapter = latestChapter,
				percent = 1.0f,
				isClamped = true,
				localProgress = localProgress,
				remoteProgress = remoteProgress,
			)
		}

		// When latest chapter is a fractional release (e.g. 141.5 / 141.1 / Epilogue) and AniList integer progress
		// has reached the base chapter (141), or user marked COMPLETED on AniList, advance and mark completed on the latest chapter.
		val isLatestFractionalOfCurrent = matchedIndex == branchChapters.lastIndex - 1 &&
			getChapterNumber(latestChapter) > 0f &&
			getChapterNumber(latestChapter) < (remoteProgress + 1.0f)

		val isFinished = remoteStatus.equals("COMPLETED", ignoreCase = true) ||
			matchedIndex >= branchChapters.lastIndex ||
			isLatestFractionalOfCurrent

		return if (isFinished) {
			val target = if (isLatestFractionalOfCurrent) latestChapter else branchChapters[matchedIndex]
			MergeResult.Advance(
				targetChapter = target,
				percent = 1.0f,
				isClamped = false,
				localProgress = localProgress,
				remoteProgress = remoteProgress,
			)
		} else {
			val nextIndex = matchedIndex + 1
			val calculatedPercent = (matchedIndex + 1).toFloat() / branchChapters.size.toFloat()
			MergeResult.Advance(
				targetChapter = branchChapters[nextIndex],
				percent = calculatedPercent,
				isClamped = false,
				localProgress = localProgress,
				remoteProgress = remoteProgress,
			)
		}
	}

	fun calculateLocalProgress(
		currentHistory: MangaHistory?,
		chapters: List<MangaChapter>,
	): Int {
		if (currentHistory == null || chapters.isEmpty()) {
			return 0
		}

		val currentChapterIndex = chapters.indexOfFirst { it.id == currentHistory.chapterId }
		if (currentChapterIndex == -1) {
			return 0
		}

		val currentChapter = chapters[currentChapterIndex]
		val chapterNum = getChapterNumber(currentChapter)
		return if (chapterNum > 0f) chapterNum.toInt() else (currentChapterIndex + 1)
	}

	fun getChapterNumber(chapter: MangaChapter): Float {
		if (chapter.number > 0f) return chapter.number
		val title = chapter.title ?: return 0f
		val match = Regex("(?i)chapter\\s*(\\d+(?:\\.\\d+)?)").find(title)
		if (match != null) {
			return match.groupValues[1].toFloatOrNull() ?: 0f
		}
		return 0f
	}
}

package org.koitharu.kotatsu.details.ui.model

import org.koitharu.kotatsu.core.model.MangaHistory
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.data.ReadingTime

data class HistoryInfo(
	val totalChapters: Int,
	val currentChapter: Int,
	val history: MangaHistory?,
	val isIncognitoMode: Boolean,
	val isChapterMissing: Boolean,
	val canDownload: Boolean,
	val estimatedTime: ReadingTime?,
) {
	val isValid: Boolean
		get() = totalChapters >= 0

	val canContinue
		get() = currentChapter >= 0

	val percent: Float
		get() = if (history != null && (canContinue || isChapterMissing)) {
			history.percent
		} else {
			0f
		}
}

fun HistoryInfo(
	manga: MangaDetails?,
	branch: String?,
	history: MangaHistory?,
	isIncognitoMode: Boolean,
	estimatedTime: ReadingTime?,
): HistoryInfo {
	val chapters = if (manga?.chapters?.isEmpty() == true) {
		emptyList()
	} else if (branch.isNullOrEmpty() || branch.equals("ALL", ignoreCase = true)) {
		manga?.allChapters
	} else {
		manga?.chapters?.get(branch)
	}
	val currentChapter = if (history != null && !chapters.isNullOrEmpty()) {
		val directIndex = chapters.indexOfFirst { it.id == history.chapterId }
		if (directIndex >= 0) {
			directIndex
		} else {
			val historyChapter = manga?.allChapters?.find { it.id == history.chapterId }
			if (historyChapter != null) {
				if (historyChapter.number > 0f) {
					val numIndex = chapters.indexOfFirst { it.number == historyChapter.number }
					if (numIndex >= 0) {
						numIndex
					} else {
						chapters.indexOfLast { it.number > 0f && it.number <= historyChapter.number }
					}
				} else if (!historyChapter.title.isNullOrEmpty()) {
					chapters.indexOfFirst { it.title == historyChapter.title }
				} else {
					-1
				}
			} else {
				-1
			}
		}
	} else {
		-2
	}
	return HistoryInfo(
		totalChapters = chapters?.size ?: -1,
		currentChapter = currentChapter,
		history = history,
		isIncognitoMode = isIncognitoMode,
		isChapterMissing = history != null && manga?.isLoaded == true && manga.allChapters.none { it.id == history.chapterId },
		canDownload = manga?.isLocal == false,
		estimatedTime = estimatedTime,
	)
}

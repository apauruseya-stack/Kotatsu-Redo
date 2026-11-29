package org.koitharu.kotatsu.details.ui

import android.content.Context
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.bookmarks.domain.Bookmark
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.details.ui.model.toListItem
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.util.mapToSet

fun MangaDetails.mapChapters(
	currentChapterId: Long,
	newCount: Int,
	branch: String?,
	bookmarks: List<Bookmark>,
	isGrid: Boolean,
	isDownloadedOnly: Boolean,
	selectedScanlator: String? = null,
	preferredScanlator: String? = null,
): List<ChapterListItem> {
	val distinctBranches = chapters.keys.filterNotNull().filter { it.isNotBlank() }
	val isBranchGroup = distinctBranches.size > 1
	val isAllBranch = branch.isNullOrEmpty() || branch.equals("ALL", ignoreCase = true)

	val rawRemoteChapters = if (isAllBranch) {
		allChapters
	} else {
		chapters[branch].orEmpty()
	}

	val selectedGroup = if (isBranchGroup) {
		if (isAllBranch) null else branch
	} else {
		selectedScanlator
	}

	val remoteChapters = org.koitharu.kotatsu.details.domain.ScanlatorChapterMerger.mergeChapters(
		chapters = rawRemoteChapters,
		selectedScanlator = selectedGroup,
		preferredScanlator = preferredScanlator,
		useBranch = isBranchGroup,
	)
	val localChapters = if (isAllBranch) {
		local?.manga?.chapters.orEmpty()
	} else {
		local?.manga?.getChapters(branch).orEmpty()
	}
	if (remoteChapters.isEmpty() && localChapters.isEmpty()) {
		return emptyList()
	}
	val bookmarked = bookmarks.mapToSet { it.chapterId }
	val newFrom = if (newCount == 0 || remoteChapters.isEmpty()) Int.MAX_VALUE else remoteChapters.size - newCount
	val ids = buildSet(maxOf(remoteChapters.size, localChapters.size)) {
		remoteChapters.mapTo(this) { it.id }
		localChapters.mapTo(this) { it.id }
	}
	val result = ArrayList<ChapterListItem>(ids.size)
	val localMap = if (localChapters.isNotEmpty()) {
		localChapters.associateByTo(LinkedHashMap(localChapters.size)) { it.id }
	} else {
		null
	}
	var isUnread = currentChapterId !in ids
	val effectiveCurrentChapterId = if (currentChapterId in ids) {
		currentChapterId
	} else if (currentChapterId != 0L) {
		val historyChapter = allChapters.find { it.id == currentChapterId }
		if (historyChapter != null) {
			val branchChapters = if (remoteChapters.isNotEmpty()) remoteChapters else localChapters
			if (historyChapter.number > 0f) {
				branchChapters.firstOrNull { it.number == historyChapter.number }?.id
					?: branchChapters.lastOrNull { it.number > 0f && it.number <= historyChapter.number }?.id
			} else if (!historyChapter.title.isNullOrEmpty()) {
				branchChapters.firstOrNull { it.title == historyChapter.title }?.id
			} else {
				null
			}
		} else {
			null
		}
	} else {
		null
	}
	if (effectiveCurrentChapterId != null && effectiveCurrentChapterId in ids) {
		isUnread = false
	}
	if (!isDownloadedOnly || local?.manga?.chapters == null) {
		for (chapter in remoteChapters) {
			val local = localMap?.remove(chapter.id)
			if (chapter.id == effectiveCurrentChapterId) {
				isUnread = true
			}
			result += (local ?: chapter).toListItem(
				isCurrent = chapter.id == effectiveCurrentChapterId,
				isUnread = isUnread,
				isNew = isUnread && result.size >= newFrom,
				isDownloaded = local != null,
				isBookmarked = chapter.id in bookmarked,
				isGrid = isGrid,
			)
		}
	}
	if (!localMap.isNullOrEmpty()) {
		for (chapter in localMap.values) {
			if (chapter.id == effectiveCurrentChapterId) {
				isUnread = true
			}
			result += chapter.toListItem(
				isCurrent = chapter.id == effectiveCurrentChapterId,
				isUnread = isUnread,
				isNew = false,
				isDownloaded = !isLocal,
				isBookmarked = chapter.id in bookmarked,
				isGrid = isGrid,
			)
		}
	}
	return result
}

fun List<ChapterListItem>.withVolumeHeaders(context: Context): MutableList<ListModel> {
	var prevVolume = 0
	val result = ArrayList<ListModel>((size * 1.4).toInt())
	for (item in this) {
		val chapter = item.chapter
		if (chapter.volume != prevVolume) {
			val text = if (chapter.volume == 0) {
				context.getString(R.string.volume_unknown)
			} else {
				context.getString(R.string.volume_, chapter.volume)
			}
			result.add(ListHeader(text))
			prevVolume = chapter.volume
		}
		result.add(item)
	}
	return result
}

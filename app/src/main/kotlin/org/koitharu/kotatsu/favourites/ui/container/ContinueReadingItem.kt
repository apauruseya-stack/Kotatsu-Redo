package org.koitharu.kotatsu.favourites.ui.container

import org.koitharu.kotatsu.history.domain.model.MangaWithHistory
import org.koitharu.kotatsu.parsers.model.MangaChapter

data class ContinueReadingItem(
	val mangaWithHistory: MangaWithHistory,
	val chapter: MangaChapter?,
	val newChaptersCount: Int = 0,
)

package org.koitharu.kotatsu.favourites.ui.container

import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.tracker.data.TrackingAnchorEntity

data class NewChapterRailItem(
	val manga: Manga,
	val anchor: TrackingAnchorEntity,
	val subtitle: String? = null,
	val isNewToday: Boolean = false,
)

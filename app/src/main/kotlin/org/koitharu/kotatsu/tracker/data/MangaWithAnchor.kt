package org.koitharu.kotatsu.tracker.data

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.core.db.entity.MangaTagsEntity
import org.koitharu.kotatsu.core.db.entity.TagEntity
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.db.entity.toMangaTags

class MangaWithAnchor(
	@Embedded val anchor: TrackingAnchorEntity,
	@Relation(
		parentColumn = "manga_id",
		entityColumn = "manga_id",
	)
	val manga: MangaEntity,
	@Relation(
		parentColumn = "manga_id",
		entityColumn = "tag_id",
		associateBy = Junction(MangaTagsEntity::class),
	)
	val tags: List<TagEntity>,
)

fun MangaWithAnchor.toManga() = manga.toManga(tags.toMangaTags(), null)

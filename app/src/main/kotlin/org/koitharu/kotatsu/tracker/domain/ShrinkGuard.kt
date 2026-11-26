package org.koitharu.kotatsu.tracker.domain

import org.koitharu.kotatsu.parsers.model.MangaChapter

object ShrinkGuard {

	const val MIN_CHAPTERS_FOR_SHRINK_CHECK = 10
	const val SHRINK_THRESHOLD_RATIO = 0.70 // 30% reduction

	fun computeVolumeNumberHash(chapters: Collection<MangaChapter>): Long {
		var hash = 0L
		for (ch in chapters) {
			val vol = ch.volume.toLong()
			val num = if (ch.number > 0f) {
				java.lang.Float.floatToIntBits(ch.number).toLong()
			} else {
				ch.url.hashCode().toLong()
			}
			val itemHash = (vol * 31L) + num
			hash += itemHash
		}
		return hash
	}

	sealed interface ShrinkDecision {
		data object AcceptNormal : ShrinkDecision
		data object RejectEmpty : ShrinkDecision
		data class PendingConfirmation(val newCount: Int, val hash: Long) : ShrinkDecision
		data object ConfirmedShrink : ShrinkDecision
	}

	fun evaluate(
		cachedCount: Int,
		newChapters: List<MangaChapter>,
		pendingShrinkCount: Int?,
		pendingShrinkHash: Long?,
	): ShrinkDecision {
		if (newChapters.isEmpty()) {
			return ShrinkDecision.RejectEmpty
		}

		val newCount = newChapters.size
		val isSignificantShrink = cachedCount >= MIN_CHAPTERS_FOR_SHRINK_CHECK &&
			newCount < (cachedCount * SHRINK_THRESHOLD_RATIO).toInt()

		if (!isSignificantShrink) {
			return ShrinkDecision.AcceptNormal
		}

		val newHash = computeVolumeNumberHash(newChapters)
		return if (pendingShrinkCount == newCount && pendingShrinkHash == newHash) {
			ShrinkDecision.ConfirmedShrink
		} else {
			ShrinkDecision.PendingConfirmation(newCount, newHash)
		}
	}
}

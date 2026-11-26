package org.koitharu.kotatsu.favourites.ui.container

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.formatChapterPillLabel
import org.koitharu.kotatsu.core.ui.glass.SpringPressHelper
import org.koitharu.kotatsu.core.ui.glass.addSpringPress
import org.koitharu.kotatsu.databinding.ItemContinueHeroCardBinding
import org.koitharu.kotatsu.list.domain.ReadingProgress
import java.util.Collections
import java.util.WeakHashMap

/**
 * Adapter for the "Continue reading" hero rail on the Favorites screen.
 * - Aspect ratio 2:3 cards
 * - Glass chapter pill with green indicator dot
 * - Shared pulsing halo animation on the play button (only runs when screen is active)
 * - Spring touch physics (cubic-bezier(.34, 1.56, .5, 1) release bounce)
 */
class ContinueReadingAdapter(
	private val onItemClick: (ContinueReadingItem) -> Unit,
) : ListAdapter<ContinueReadingItem, ContinueReadingAdapter.HeroCardViewHolder>(DiffCallback) {

	private val attachedHolders = Collections.newSetFromMap(WeakHashMap<HeroCardViewHolder, Boolean>())
	private var pulseAnimator: ValueAnimator? = null

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeroCardViewHolder {
		val binding = ItemContinueHeroCardBinding.inflate(
			LayoutInflater.from(parent.context),
			parent,
			false,
		)
		return HeroCardViewHolder(binding, onItemClick)
	}

	override fun onBindViewHolder(holder: HeroCardViewHolder, position: Int) {
		holder.bind(getItem(position))
	}

	override fun onViewAttachedToWindow(holder: HeroCardViewHolder) {
		super.onViewAttachedToWindow(holder)
		attachedHolders.add(holder)
	}

	override fun onViewDetachedFromWindow(holder: HeroCardViewHolder) {
		super.onViewDetachedFromWindow(holder)
		attachedHolders.remove(holder)
	}

	fun startPulse(parentView: View) {
		if (!SpringPressHelper.areAnimationsEnabled(parentView)) return
		if (pulseAnimator != null) return

		pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
			duration = 2600L
			repeatCount = ValueAnimator.INFINITE
			interpolator = LinearInterpolator()
			addUpdateListener { anim ->
				val progress = anim.animatedValue as Float
				// Pulse halo: scale 0.8 -> 1.5, alpha 0.9 -> 0
				val scale = 0.8f + progress * 0.7f
				val alpha = (1f - progress) * 0.9f
				for (holder in attachedHolders) {
					holder.updateHalo(scale, alpha)
				}
			}
			start()
		}
	}

	fun stopPulse() {
		pulseAnimator?.cancel()
		pulseAnimator = null
		for (holder in attachedHolders) {
			holder.resetHalo()
		}
	}

	class HeroCardViewHolder(
		val binding: ItemContinueHeroCardBinding,
		private val onItemClick: (ContinueReadingItem) -> Unit,
	) : RecyclerView.ViewHolder(binding.root) {

		private var currentItem: ContinueReadingItem? = null

		init {
			binding.cardHero.addSpringPress(targetScale = 0.94f) {
				currentItem?.let(onItemClick)
			}
			binding.layoutPlayButton.addSpringPress(targetScale = 0.86f) {
				currentItem?.let(onItemClick)
			}
		}

		@SuppressLint("SetTextI18n")
		fun bind(item: ContinueReadingItem) {
			currentItem = item
			val manga = item.mangaWithHistory.manga
			val history = item.mangaWithHistory.history
			val chapter = item.chapter

			// Cover image
			binding.ivCover.setImageAsync(manga.coverUrl, manga)

			// Title
			binding.tvTitle.text = manga.title

			// Percent (exact semantics from HistoryEntity)
			val percentInt = ReadingProgress.formatPercentInt(history.percent)
			binding.pbProgress.progress = percentInt
			binding.tvPercent.text = ReadingProgress.formatPercent(history.percent, binding.root.context) ?: "0%"

			val basePillLabel = chapter?.formatChapterPillLabel(binding.root.context)
				?: if (history.chaptersCount > 0) {
					binding.root.context.getString(R.string.chapter_number_short, history.chaptersCount.toString())
				} else {
					null
				}
			val pillLabel = if (item.newChaptersCount > 0) {
				if (!basePillLabel.isNullOrEmpty()) {
					"$basePillLabel  •  +${item.newChaptersCount}"
				} else {
					"+${item.newChaptersCount} new"
				}
			} else {
				basePillLabel
			}
			if (!pillLabel.isNullOrEmpty()) {
				binding.layoutChapterPill.isVisible = true
				binding.tvChapter.text = pillLabel
			} else {
				binding.layoutChapterPill.isVisible = false
			}
		}

		fun updateHalo(scale: Float, alpha: Float) {
			binding.viewPlayHalo.run {
				isVisible = true
				scaleX = scale
				scaleY = scale
				this.alpha = alpha
			}
		}

		fun resetHalo() {
			binding.viewPlayHalo.run {
				isVisible = false
				scaleX = 1f
				scaleY = 1f
				alpha = 0f
			}
		}
	}

	private object DiffCallback : DiffUtil.ItemCallback<ContinueReadingItem>() {
		override fun areItemsTheSame(oldItem: ContinueReadingItem, newItem: ContinueReadingItem): Boolean {
			return oldItem.mangaWithHistory.manga.id == newItem.mangaWithHistory.manga.id
		}

		override fun areContentsTheSame(oldItem: ContinueReadingItem, newItem: ContinueReadingItem): Boolean {
			return oldItem.mangaWithHistory.history == newItem.mangaWithHistory.history &&
				oldItem.mangaWithHistory.manga.title == newItem.mangaWithHistory.manga.title &&
				oldItem.chapter?.id == newItem.chapter?.id
		}
	}
}

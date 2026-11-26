package org.koitharu.kotatsu.favourites.ui.container

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.glass.addSpringPress
import org.koitharu.kotatsu.databinding.ItemNewChapterRailCardBinding
import org.koitharu.kotatsu.tracker.data.TrackingAnchorEntity

class NewChaptersRailAdapter(
	private val onItemClick: (NewChapterRailItem) -> Unit,
	private val onDismissClick: (NewChapterRailItem) -> Unit,
	private val onItemLongClick: ((NewChapterRailItem, View) -> Unit)? = null,
) : ListAdapter<NewChapterRailItem, NewChaptersRailAdapter.RailCardViewHolder>(DiffCallback) {

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RailCardViewHolder {
		val binding = ItemNewChapterRailCardBinding.inflate(
			LayoutInflater.from(parent.context),
			parent,
			false,
		)
		return RailCardViewHolder(binding, onItemClick, onDismissClick, onItemLongClick)
	}

	override fun onBindViewHolder(holder: RailCardViewHolder, position: Int) {
		holder.bind(getItem(position))
	}

	class RailCardViewHolder(
		val binding: ItemNewChapterRailCardBinding,
		private val onItemClick: (NewChapterRailItem) -> Unit,
		private val onDismissClick: (NewChapterRailItem) -> Unit,
		private val onItemLongClick: ((NewChapterRailItem, View) -> Unit)? = null,
	) : RecyclerView.ViewHolder(binding.root) {

		private var currentItem: NewChapterRailItem? = null

		init {
			binding.cardRail.addSpringPress(targetScale = 0.94f) {
				currentItem?.let(onItemClick)
			}
			binding.cardRail.setOnLongClickListener { v ->
				currentItem?.let { item ->
					if (onItemLongClick != null) {
						onItemLongClick.invoke(item, v)
					} else {
						onDismissClick(item)
					}
					true
				} ?: false
			}
			binding.btnDismiss.addSpringPress(targetScale = 0.86f) {
				currentItem?.let(onDismissClick)
			}
		}

		@SuppressLint("SetTextI18n")
		fun bind(item: NewChapterRailItem) {
			currentItem = item
			val manga = item.manga
			val anchor = item.anchor

			// Cover image
			binding.ivCover.setImageAsync(manga.coverUrl, manga)

			// Title
			binding.tvTitle.text = manga.title

			// Badge text: +N
			val count = anchor.newReleasesCount
			binding.tvBadge.text = "+$count"

			// Subtitle (formatted chapter range or relative time if available)
			val subtitleText = item.subtitle
				?: anchor.latestNumberAtLastRead?.takeIf { it > 0f }?.let { latestNum ->
					val numStr = if (latestNum % 1f == 0f) latestNum.toInt().toString() else latestNum.toString()
					binding.root.context.getString(R.string.chapter_number_short, numStr)
				}

			if (!subtitleText.isNullOrEmpty()) {
				binding.tvSubtitle.text = subtitleText
				binding.tvSubtitle.isVisible = true
			} else {
				binding.tvSubtitle.isVisible = false
			}

			// "NEW today" glow token: accent cyan stroke when released within last 24h
			if (item.isNewToday) {
				binding.cardRail.strokeColor = 0x803EC6E8.toInt()
			} else {
				binding.cardRail.strokeColor = 0x26FFFFFF.toInt()
			}

			// Dimmed overlay if status is FAILED or BLOCKED
			binding.viewDimmedOverlay.isVisible = anchor.status == TrackingAnchorEntity.STATUS_FAILED ||
				anchor.status == TrackingAnchorEntity.STATUS_BLOCKED
		}
	}

	private object DiffCallback : DiffUtil.ItemCallback<NewChapterRailItem>() {
		override fun areItemsTheSame(oldItem: NewChapterRailItem, newItem: NewChapterRailItem): Boolean {
			return oldItem.manga.id == newItem.manga.id
		}

		override fun areContentsTheSame(oldItem: NewChapterRailItem, newItem: NewChapterRailItem): Boolean {
			return oldItem == newItem
		}
	}
}

package org.koitharu.kotatsu.favourites.ui.container

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import androidx.appcompat.view.ActionMode
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.ui.glass.addSpringPress
import org.koitharu.kotatsu.core.ui.util.ActionModeListener
import org.koitharu.kotatsu.core.ui.util.RecyclerViewOwner
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.util.ext.addMenuProvider
import org.koitharu.kotatsu.core.util.ext.findCurrentPagerFragment
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.recyclerView
import org.koitharu.kotatsu.core.util.ext.setTabsEnabled
import org.koitharu.kotatsu.core.util.ext.setTextAndVisible
import org.koitharu.kotatsu.databinding.FragmentFavouritesContainerBinding
import org.koitharu.kotatsu.databinding.ItemEmptyStateBinding
import org.koitharu.kotatsu.main.ui.owners.BottomNavOwner
import org.koitharu.kotatsu.reader.ui.ReaderState

@AndroidEntryPoint
class FavouritesContainerFragment : BaseFragment<FragmentFavouritesContainerBinding>(),
	ActionModeListener,
	RecyclerViewOwner,
	ViewStub.OnInflateListener,
	View.OnClickListener {

	private val viewModel: FavouritesContainerViewModel by viewModels()
	private var continueReadingAdapter: ContinueReadingAdapter? = null

	override val recyclerView: RecyclerView?
		get() = (findCurrentFragment() as? RecyclerViewOwner)?.recyclerView

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = FragmentFavouritesContainerBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(binding: FragmentFavouritesContainerBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		val continueAdapter = ContinueReadingAdapter { item ->
			val history = item.mangaWithHistory.history
			val isCompleted = org.koitharu.kotatsu.list.domain.ReadingProgress.isCompleted(history.percent)
			val intentBuilder = ReaderIntent.Builder(requireContext())
				.manga(item.mangaWithHistory.manga)
			if (!isCompleted && history.chapterId > 0) {
				intentBuilder.state(
					ReaderState(
						chapterId = history.chapterId,
						page = history.page,
						scroll = history.scroll,
					),
				)
			}
			router.openReader(intentBuilder.build())
		}
		continueReadingAdapter = continueAdapter
		binding.rvContinueReading.adapter = continueAdapter
		binding.btnHistory.addSpringPress(targetScale = 0.94f) {
			val bottomNav = (activity as? BottomNavOwner)?.bottomNav
			if (bottomNav != null && bottomNav.menu.findItem(R.id.nav_history) != null) {
				bottomNav.selectedItemId = R.id.nav_history
			} else {
				router.openHistory()
			}
		}
		viewModel.continueReadingItems.observe(viewLifecycleOwner) { items ->
			continueAdapter.submitList(items)
			val shouldShow = viewModel.settings.isLiquidGlass && items.isNotEmpty()
			if (binding.layoutContinueReading.isVisible != shouldShow) {
				binding.layoutContinueReading.isVisible = shouldShow
				binding.favouritesAppbar.requestLayout()
			}
			if (shouldShow) {
				continueAdapter.startPulse(binding.root)
			} else {
				continueAdapter.stopPulse()
			}
		}

		val newChaptersAdapter = NewChaptersRailAdapter(
			onItemClick = { item ->
				viewLifecycleOwner.lifecycleScope.launch {
					val targetChapter = viewModel.getFirstUnreadChapter(item.manga)
					if (targetChapter != null) {
						val intent = ReaderIntent.Builder(requireContext())
							.manga(item.manga)
							.state(ReaderState(chapterId = targetChapter.id, page = 0, scroll = 0))
							.build()
						router.openReader(intent)
					} else {
						router.openDetails(item.manga)
					}
				}
			},
			onDismissClick = { item ->
				viewModel.dismissNewRelease(
					mangaId = item.manga.id,
					volume = item.anchor.latestVolumeAtLastRead,
					number = item.anchor.latestNumberAtLastRead,
				)
			},
			onItemLongClick = { item, anchorView ->
				val popup = androidx.appcompat.widget.PopupMenu(requireContext(), anchorView)
				val statusName = when (item.anchor.status) {
					org.koitharu.kotatsu.tracker.data.TrackingAnchorEntity.STATUS_OK -> "Source OK"
					org.koitharu.kotatsu.tracker.data.TrackingAnchorEntity.STATUS_BLOCKED -> "Source Blocked (Cloudflare)"
					org.koitharu.kotatsu.tracker.data.TrackingAnchorEntity.STATUS_BROKEN -> "Source Broken"
					else -> "Source Check Failed"
				}
				val checkedTimeStr = if (item.anchor.lastCheckedAt > 0L) {
					DateUtils.getRelativeTimeSpanString(item.anchor.lastCheckedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
				} else {
					"Never"
				}
				popup.menu.add(0, 0, 0, "$statusName  •  Checked $checkedTimeStr").setEnabled(false)
				popup.menu.add(0, 1, 1, R.string.dismiss_until_next_chapter)
				popup.menu.add(0, 2, 2, R.string.hide_from_new_rail)
				popup.menu.add(0, 3, 3, R.string.details)
				popup.setOnMenuItemClickListener { menuItem ->
					when (menuItem.itemId) {
						1 -> {
							viewModel.dismissNewRelease(
								mangaId = item.manga.id,
								volume = item.anchor.latestVolumeAtLastRead,
								number = item.anchor.latestNumberAtLastRead,
							)
							true
						}
						2 -> {
							viewModel.muteManga(item.manga.id)
							true
						}
						3 -> {
							router.openDetails(item.manga)
							true
						}
						else -> false
					}
				}
				popup.show()
			},
		)
		binding.rvNewChaptersRail.adapter = newChaptersAdapter
		binding.btnRefreshNewChapters.addSpringPress(targetScale = 0.86f) {
			viewModel.refreshTracker(force = true)
		}
		viewLifecycleOwner.lifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				viewModel.isRefreshing.collect { refreshing ->
					binding.progressNewChapters.isVisible = refreshing
					binding.btnRefreshNewChapters.isVisible = !refreshing
				}
			}
		}
		viewModel.newChaptersItems.observe(viewLifecycleOwner) { items ->
			newChaptersAdapter.submitList(items)
			val shouldShow = viewModel.settings.isLiquidGlass && items.isNotEmpty()
			if (binding.layoutNewChaptersRail.isVisible != shouldShow) {
				binding.layoutNewChaptersRail.isVisible = shouldShow
				binding.favouritesAppbar.requestLayout()
			}
			binding.textNewChaptersCount.text = "${items.size} ${getString(R.string.updates).lowercase()}"
		}

		val pagerAdapter = FavouritesContainerAdapter(this)
		binding.pager.adapter = pagerAdapter
		binding.pager.offscreenPageLimit = 1
		binding.pager.recyclerView?.isNestedScrollingEnabled = false
		TabLayoutMediator(
			binding.tabs,
			binding.pager,
			FavouritesTabConfigurationStrategy(pagerAdapter, viewModel, router),
		).attach()
		binding.stubEmpty.setOnInflateListener(this)
		actionModeDelegate.addListener(this)
		var isInitialPageSet = savedInstanceState != null
		viewModel.categories.observe(viewLifecycleOwner) { categories ->
			pagerAdapter.emit(categories)
			if (!isInitialPageSet && viewModel.settings.isLiquidGlass && categories.isNotEmpty()) {
				isInitialPageSet = true
				val readingTitle = getString(R.string.status_reading)
				val readingIndex = categories.indexOfFirst {
					it.title?.equals(readingTitle, ignoreCase = true) == true ||
						org.koitharu.kotatsu.scrobbling.anilist.domain.model.AniListCategoryNames.matchesReading(it.title)
				}
				if (readingIndex > 0) {
					binding.pager.post {
						binding.pager.setCurrentItem(readingIndex, false)
					}
				}
			}
		}
		viewModel.isEmpty.observe(viewLifecycleOwner, ::onEmptyStateChanged)
		addMenuProvider(FavouritesContainerMenuProvider(router))
		viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(binding.pager))
	}

	override fun onStart() {
		super.onStart()
		viewBinding?.let { binding ->
			if (viewModel.settings.isLiquidGlass && binding.layoutContinueReading.isVisible) {
				continueReadingAdapter?.startPulse(binding.root)
			}
		}
	}

	override fun onResume() {
		super.onResume()
		if (viewModel.settings.isLiquidGlass) {
			viewModel.refreshTracker(force = false)
		}
	}

	override fun onStop() {
		continueReadingAdapter?.stopPulse()
		super.onStop()
	}

	override fun onDestroyView() {
		continueReadingAdapter?.stopPulse()
		continueReadingAdapter = null
		actionModeDelegate.removeListener(this)
		super.onDestroyView()
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat = insets

	override fun onActionModeStarted(mode: ActionMode) {
		viewBinding?.run {
			pager.isUserInputEnabled = false
			tabs.setTabsEnabled(false)
		}
	}

	override fun onActionModeFinished(mode: ActionMode) {
		viewBinding?.run {
			pager.isUserInputEnabled = true
			tabs.setTabsEnabled(true)
		}
	}

	override fun onInflate(stub: ViewStub?, inflated: View) {
		val stubBinding = ItemEmptyStateBinding.bind(inflated)
		stubBinding.icon.setImageAsync(R.drawable.ic_empty_favourites)
		stubBinding.textPrimary.setText(R.string.text_empty_holder_primary)
		stubBinding.textSecondary.setTextAndVisible(R.string.empty_favourite_categories)
		stubBinding.buttonRetry.setTextAndVisible(R.string.manage)
		stubBinding.buttonRetry.setOnClickListener(this)
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_retry -> router.openFavoriteCategories()
		}
	}

	private fun onEmptyStateChanged(isEmpty: Boolean) {
		viewBinding?.run {
			pager.isGone = isEmpty
			tabs.isGone = isEmpty
			stubEmpty.isVisible = isEmpty
		}
	}

	private fun findCurrentFragment(): Fragment? {
		return childFragmentManager.findCurrentPagerFragment(
			viewBinding?.pager ?: return null,
		)
	}
}

package org.koitharu.kotatsu.details.ui.pager

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.details.ui.pager.chapters.ChaptersFragment

class ChaptersPagesAdapter(
	fragment: Fragment,
) : FragmentStateAdapter(fragment),
	TabLayoutMediator.TabConfigurationStrategy {

	override fun getItemCount(): Int = 1

	override fun createFragment(position: Int): Fragment = when (position) {
		0 -> ChaptersFragment()
		else -> throw IllegalArgumentException("Invalid position $position")
	}

	override fun onConfigureTab(tab: TabLayout.Tab, position: Int) {
		tab.setIcon(R.drawable.ic_list)
	}
}

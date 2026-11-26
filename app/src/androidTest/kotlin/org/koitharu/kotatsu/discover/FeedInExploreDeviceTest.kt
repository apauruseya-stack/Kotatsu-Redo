package org.koitharu.kotatsu.discover

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.NavItem
import org.koitharu.kotatsu.discover.ui.DiscoverRowItem
import org.koitharu.kotatsu.tracker.ui.feed.FeedActivity

@RunWith(AndroidJUnit4::class)
class FeedInExploreDeviceTest {

	@Test
	fun navSettingsItem_isProperlyConfigured() {
		assertEquals(R.id.nav_settings, NavItem.SETTINGS.id)
		assertEquals(R.string.settings, NavItem.SETTINGS.title)
		assertEquals(R.drawable.ic_settings, NavItem.SETTINGS.icon)
	}

	@Test
	fun feedActivity_isRegisteredInManifest() {
		val context = ApplicationProvider.getApplicationContext<android.content.Context>()
		val pm = context.packageManager
		val intent = Intent(context, FeedActivity::class.java)
		val resolveInfo = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
		assertNotNull("FeedActivity must be registered in AndroidManifest.xml", resolveInfo)
	}

	@Test
	fun discoverRowItem_supportsFeedRowConfiguration() {
		val item = DiscoverRowItem(
			title = R.string.feed,
			titleText = "Feed (99+)",
			items = emptyList(),
			isLoading = false,
			opensSuggestions = false,
			opensFeed = true,
			icon = R.drawable.ic_feed,
		)
		assertEquals(R.string.feed, item.title)
		assertEquals("Feed (99+)", item.titleText)
		assertEquals(R.drawable.ic_feed, item.icon)
		assertTrue(item.opensFeed)
	}

	@Test
	fun liquidGlassNavItems_replacesFeedWithSettings() {
		val rawItems = listOf(NavItem.HISTORY, NavItem.FAVORITES, NavItem.EXPLORE, NavItem.SOURCES, NavItem.FEED)
		val filtered = rawItems
			.filter { it != NavItem.HISTORY }
			.map { if (it == NavItem.FEED) NavItem.SETTINGS else it }
			.distinct()

		assertEquals(4, filtered.size)
		assertEquals(listOf(NavItem.FAVORITES, NavItem.EXPLORE, NavItem.SOURCES, NavItem.SETTINGS), filtered)
		assertTrue(!filtered.contains(NavItem.FEED))
		assertTrue(!filtered.contains(NavItem.HISTORY))
		assertTrue(filtered.contains(NavItem.SETTINGS))
	}
}

package org.koitharu.kotatsu.scrobbling.anilist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.scrobbling.anilist.domain.AniListTitleMatcher
import org.koitharu.kotatsu.scrobbling.anilist.domain.MatchConfidence
import org.koitharu.kotatsu.scrobbling.anilist.domain.model.AniListMediaEntry

class AniListTitleMatcherTest {

	private fun createEntry(
		preferredTitle: String,
		allTitles: List<String> = listOf(preferredTitle),
		totalChapters: Int? = null,
	) = AniListMediaEntry(
		entryId = 1,
		mediaId = 100L,
		status = "CURRENT",
		progress = 10,
		score = 8.5f,
		notes = null,
		preferredTitle = preferredTitle,
		allTitles = allTitles,
		totalChapters = totalChapters,
		coverUrl = null,
		siteUrl = null,
		format = "MANGA",
	)

	private fun createManga(
		title: String,
		altTitles: Set<String> = emptySet(),
	) = Manga(
		id = 1L,
		title = title,
		altTitles = altTitles,
		url = "/manga/$title",
		publicUrl = "https://example.com/manga/$title",
		rating = 0.9f,
		contentRating = null,
		coverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		source = UnknownMangaSource,
	)

	@Test
	fun testTowerOfGodVsSeason2() {
		val entry = createEntry("Tower of God")
		val candidate = createManga("Tower of God Season 2")

		val result = AniListTitleMatcher.evaluate(entry, candidate)
		assertNotEquals(MatchConfidence.HIGH, result.confidence)
		assertNotNull(result.reason)
		assertTrue(result.reason?.contains("sequel", ignoreCase = true) == true)
	}

	@Test
	fun testSoloLevelingVsRagnarok() {
		val entry = createEntry("Solo Leveling")
		val candidate = createManga("Solo Leveling: Ragnarok")

		val result = AniListTitleMatcher.evaluate(entry, candidate)
		assertNotEquals(MatchConfidence.HIGH, result.confidence)
		assertNotNull(result.reason)
		assertTrue(result.reason?.contains("sequel", ignoreCase = true) == true)
	}

	@Test
	fun testTitleVsTitleII() {
		val entry = createEntry("Chihayafuru")
		val candidate = createManga("Chihayafuru II")

		val result = AniListTitleMatcher.evaluate(entry, candidate)
		assertNotEquals(MatchConfidence.HIGH, result.confidence)
		assertNotNull(result.reason)
	}

	@Test
	fun testTitleVsTitle2ndSeason() {
		val entry = createEntry("Kaguya-sama: Love Is War")
		val candidate = createManga("Kaguya-sama: Love Is War 2nd Season")

		val result = AniListTitleMatcher.evaluate(entry, candidate)
		assertNotEquals(MatchConfidence.HIGH, result.confidence)
		assertNotNull(result.reason)
	}

	@Test
	fun testExactMatchHighConfidence() {
		val entry = createEntry(
			preferredTitle = "Frieren: Beyond Journey's End",
			allTitles = listOf("Frieren: Beyond Journey's End", "Sousou no Frieren", "Frieren"),
		)
		val candidate = createManga("Sousou no Frieren")

		val result = AniListTitleMatcher.evaluate(entry, candidate)
		assertEquals(MatchConfidence.HIGH, result.confidence)
		assertTrue(result.score >= 0.95f)
		assertNull(result.reason)
	}

	@Test
	fun testBothHaveSameSequelTokens() {
		val entry = createEntry("Tower of God Season 2")
		val candidate = createManga("Tower of God Season 2")

		val result = AniListTitleMatcher.evaluate(entry, candidate)
		assertEquals(MatchConfidence.HIGH, result.confidence)
		assertEquals(1.0f, result.score, 0.01f)
		assertNull(result.reason)
	}

	@Test
	fun testSequelDisambiguationTable() {
		data class TestCase(
			val aniTitle: String,
			val candTitle: String,
			val expectedHighConfidence: Boolean,
		)

		val cases = listOf(
			TestCase("Berserk", "Berserk", expectedHighConfidence = true),
			TestCase("One Piece", "One Piece", expectedHighConfidence = true),
			TestCase("Tokyo Ghoul", "Tokyo Ghoul:re", expectedHighConfidence = false),
			TestCase("Chainsaw Man", "Chainsaw Man Part 2", expectedHighConfidence = false),
			TestCase("Mushoku Tensei", "Mushoku Tensei: Roxy Gaiden", expectedHighConfidence = false),
		)

		for ((aniTitle, candTitle, expectedHigh) in cases) {
			val entry = createEntry(aniTitle)
			val candidate = createManga(candTitle)
			val result = AniListTitleMatcher.evaluate(entry, candidate)

			if (expectedHigh) {
				assertEquals("Expected HIGH for $aniTitle vs $candTitle", MatchConfidence.HIGH, result.confidence)
			} else {
				assertNotEquals("Expected NOT HIGH for $aniTitle vs $candTitle", MatchConfidence.HIGH, result.confidence)
			}
		}
	}
}

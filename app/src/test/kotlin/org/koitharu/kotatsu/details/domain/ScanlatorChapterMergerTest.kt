package org.koitharu.kotatsu.details.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.model.TestMangaSource
import org.koitharu.kotatsu.parsers.model.MangaChapter

class ScanlatorChapterMergerTest {

	private val testSource = TestMangaSource

	private fun createChapter(
		id: Long,
		number: Float,
		scanlator: String,
		title: String = "Chapter $number",
		volume: Int = 0,
		uploadDate: Long = 0L,
	) = MangaChapter(
		id = id,
		title = title,
		number = number,
		volume = volume,
		url = "/chapter/$id",
		scanlator = scanlator,
		uploadDate = uploadDate,
		branch = null,
		source = testSource,
	)

	@Test
	fun priorities_prefersAsuraAndOfficial() {
		val scanlators = listOf("Vortex Scans", "Asura Scans", "Random Translation")
		val best = ScanlatorPriorities.pickBestDefault(scanlators, emptyList())
		assertEquals("Asura Scans", best)

		val officialScanlators = listOf("Unknown Group", "Official Webtoon", "Random Fansub")
		val bestOfficial = ScanlatorPriorities.pickBestDefault(officialScanlators, emptyList())
		assertEquals("Official Webtoon", bestOfficial)
	}

	@Test
	fun smartHybrid_usesPreferredUntilLatest_thenFallsBackToNewerRelease() {
		// Asura Scans has Ch 1..108
		val asuraChapters = (1..108).map { num ->
			createChapter(
				id = num.toLong(),
				number = num.toFloat(),
				scanlator = "Asura Scans",
				uploadDate = 1000L + num,
			)
		}

		// Vortex Scans has released Ch 100..109 (Ch 109 is a newer release not yet on Asura)
		val vortexChapters = (100..109).map { num ->
			createChapter(
				id = 10000L + num,
				number = num.toFloat(),
				scanlator = "Vortex Scans",
				uploadDate = 2000L + num,
			)
		}

		val allRawChapters = asuraChapters + vortexChapters

		// In "All" mode (selectedScanlator = null, preferredScanlator = "Asura Scans")
		val merged = ScanlatorChapterMerger.mergeChapters(
			chapters = allRawChapters,
			selectedScanlator = null,
			preferredScanlator = "Asura Scans",
		)

		// Total chapters must be exactly 109 (no duplicates)
		assertEquals(109, merged.size)

		// Chapters 1..108 must come from Asura Scans
		for (i in 0 until 108) {
			val ch = merged[i]
			assertEquals((i + 1).toFloat(), ch.number, 0.001f)
			assertEquals("Asura Scans", ch.scanlator)
		}

		// Chapter 109 must come from Vortex Scans on top
		val latest = merged.last()
		assertEquals(109f, latest.number, 0.001f)
		assertEquals("Vortex Scans", latest.scanlator)
	}

	@Test
	fun smartHybrid_autoReplacesFallback_whenPreferredCatchesUp() {
		// Initially Asura had 1..108 and Vortex had 109
		// Now Asura catches up and also releases Ch 109!
		val asuraChapters = (1..109).map { num ->
			createChapter(
				id = num.toLong(),
				number = num.toFloat(),
				scanlator = "Asura Scans",
				uploadDate = 1000L + num,
			)
		}

		val vortexChapters = (100..109).map { num ->
			createChapter(
				id = 10000L + num,
				number = num.toFloat(),
				scanlator = "Vortex Scans",
				uploadDate = 2000L + num,
			)
		}

		val allRawChapters = asuraChapters + vortexChapters

		val merged = ScanlatorChapterMerger.mergeChapters(
			chapters = allRawChapters,
			selectedScanlator = null,
			preferredScanlator = "Asura Scans",
		)

		// Total chapters must be exactly 109
		assertEquals(109, merged.size)

		// Chapter 109 must now be automatically replaced by Asura Scans!
		val latest = merged.last()
		assertEquals(109f, latest.number, 0.001f)
		assertEquals("Asura Scans", latest.scanlator)
		assertEquals(109L, latest.id)
	}

	@Test
	fun smartHybrid_fillsEarlyGaps() {
		// Preferred group only started translating from Ch 10
		val preferredChapters = (10..50).map { num ->
			createChapter(
				id = num.toLong(),
				number = num.toFloat(),
				scanlator = "Asura Scans",
			)
		}

		// Another group translated Ch 1..20
		val fallbackChapters = (1..20).map { num ->
			createChapter(
				id = 5000L + num,
				number = num.toFloat(),
				scanlator = "Early Group",
			)
		}

		val merged = ScanlatorChapterMerger.mergeChapters(
			chapters = preferredChapters + fallbackChapters,
			selectedScanlator = null,
			preferredScanlator = "Asura Scans",
		)

		assertEquals(50, merged.size)
		// Chapters 1..9 filled by Early Group
		for (i in 0 until 9) {
			assertEquals("Early Group", merged[i].scanlator)
		}
		// Chapters 10..50 from Asura Scans
		for (i in 9 until 50) {
			assertEquals("Asura Scans", merged[i].scanlator)
		}
	}

	@Test
	fun specificScanlatorFilter_returnsOnlyThatGroup() {
		val asuraChapters = (1..10).map { num ->
			createChapter(id = num.toLong(), number = num.toFloat(), scanlator = "Asura Scans")
		}
		val vortexChapters = (5..15).map { num ->
			createChapter(id = 100L + num, number = num.toFloat(), scanlator = "Vortex Scans")
		}

		val allChapters = asuraChapters + vortexChapters

		val asuraOnly = ScanlatorChapterMerger.mergeChapters(
			chapters = allChapters,
			selectedScanlator = "Asura Scans",
			preferredScanlator = "Asura Scans",
		)
		assertEquals(10, asuraOnly.size)
		assertTrue(asuraOnly.all { it.scanlator == "Asura Scans" })

		val vortexOnly = ScanlatorChapterMerger.mergeChapters(
			chapters = allChapters,
			selectedScanlator = "Vortex Scans",
			preferredScanlator = "Asura Scans",
		)
		assertEquals(11, vortexOnly.size)
		assertTrue(vortexOnly.all { it.scanlator == "Vortex Scans" })
	}

	@Test
	fun singleScanlator_returnsListDirectly() {
		val singleGroupChapters = (1..5).map { num ->
			createChapter(id = num.toLong(), number = num.toFloat(), scanlator = "Solo Scanlator")
		}
		val result = ScanlatorChapterMerger.mergeChapters(
			chapters = singleGroupChapters,
			selectedScanlator = null,
			preferredScanlator = null,
		)
		assertEquals(singleGroupChapters, result)
	}

	@Test
	fun branchBasedGroups_smartHybrid_worksWithBranches() {
		val asuraChapters = (1..134).map { num ->
			createChapter(
				id = num.toLong(),
				number = num.toFloat(),
				scanlator = "",
			).copy(branch = "Asura Scans")
		}
		val blozeChapters = (134..135).map { num ->
			createChapter(
				id = 1000L + num,
				number = num.toFloat(),
				scanlator = "",
			).copy(branch = "Bl0ze")
		}

		val all = asuraChapters + blozeChapters
		val merged = ScanlatorChapterMerger.mergeChapters(
			chapters = all,
			selectedScanlator = null,
			preferredScanlator = "Asura Scans",
			useBranch = true,
		)

		assertEquals(135, merged.size)
		assertEquals("Asura Scans", merged[133].branch)
		assertEquals(134f, merged[133].number, 0.001f)
		assertEquals("Bl0ze", merged[134].branch)
		assertEquals(135f, merged[134].number, 0.001f)
	}
}

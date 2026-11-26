package org.koitharu.kotatsu.tracker.domain

import android.util.Log
import org.koitharu.kotatsu.core.model.MangaHistory
import org.koitharu.kotatsu.parsers.model.MangaChapter

data class TrackingAnchor(
	val lastChapterId: Long = 0L,
	val lastReadVolume: Int? = null,
	val lastReadNumber: Float? = null,
	val latestVolumeAtLastRead: Int? = null,
	val latestNumberAtLastRead: Float? = null,
	val baselineVolume: Int? = null,
	val baselineNumber: Float? = null,
	val branch: String? = null,
	val newDetectedAt: Long = 0L,
	val failCount: Int = 0,
	val status: Int = 0,
)

data class NewChaptersResult(
	val count: Int,
	val hasNewReleases: Boolean,
	val newReleasesCount: Int,
	val latestChapter: MangaChapter?,
	val firstNewChapter: MangaChapter?,
	val rangeText: String?,
	val chaptersAfterPosition: List<MangaChapter>,
	val baselineToPersist: TrackingAnchor? = null,
)

object ChapterCounter {

	private const val TAG = "ChapterCounter"
	private const val MAX_VALID_CHAPTER_NUMBER = 10_000f

	// Multi-lingual chapter prefixes with Unicode case folding:
	// English: Chapter, Ch., Episode, Ep.
	// Spanish/Portuguese: Capítulo, Cap.
	// Russian/Ukrainian: Глава, Выпуск, Гл., Вып.
	private val MULTILANG_CHAPTER_REGEX = Regex(
		"""(?iu)(?:chapter|ch\.?|episode|ep\.?|cap[íi]tulo|cap\.?|глава|выпуск|вып\.?|гл\.?)\s*[-.:#]?\s*(\d+(?:\.\d+)?)"""
	)

	// East Asian: 화 (Korean), 話 / 话 (Japanese / Chinese), e.g. "105화", "第105話"
	private val CJK_CHAPTER_REGEX = Regex(
		"""(?iu)(?:第\s*)?(\d+(?:\.\d+)?)\s*(?:[話话화])"""
	)

	// Strict URL pattern with chapter keyword path/query segment
	private val STRICT_URL_CHAPTER_REGEX = Regex(
		"""(?iu)/(?:chapters?|ch|episodes?|ep)[-_/](\d+(?:\.\d+)?)(?:[/?#_.-]|$)"""
	)

	private val BARE_NUMBER_REGEX = Regex(
		"""^\s*#?\s*(\d+(?:\.\d+)?)\s*$"""
	)

	private val PART_REGEX = Regex(
		"""(?iu)\b(?:part|pt)[-.:\s#]*(\d+|[a-z])\b"""
	)

	private val SCANLATOR_TAG_REGEX = Regex(
		"""(?iu)\[.*?]|(?:\s*[-–—|]\s*(?:scanlation|scans?|comics?|team|group|translation|asura|flame|reaper|zero).*$)|\s*\(.*?\)"""
	)

	data class CanonicalKey(
		val volume: Int,
		val number: Float,
		val part: String?,
		val unnumberedTitle: String?,
	)

	data class CanonicalChapter(
		val key: CanonicalKey,
		val number: Float,
		val volume: Int,
		val candidates: MutableList<MangaChapter> = mutableListOf(),
		var representativeChapter: MangaChapter,
	)

	fun extractChapterNumber(
		chapter: MangaChapter,
		maxReliable: Float? = null,
		allowBareNumbers: Boolean = false,
	): Float {
		// 1. Model's own number field
		if (chapter.number > 0f && chapter.number < MAX_VALID_CHAPTER_NUMBER) {
			if (maxReliable == null || maxReliable <= 0f || chapter.number <= maxReliable * 5f) {
				return chapter.number
			}
		}

		// 2. Parser's numberString()
		chapter.numberString()?.toFloatOrNull()?.let { num ->
			if (num > 0f && num < MAX_VALID_CHAPTER_NUMBER) {
				if (maxReliable == null || maxReliable <= 0f || num <= maxReliable * 5f) {
					return num
				}
			}
		}

		// 3. Multi-language title parsing (requires explicit chapter keywords)
		val title = chapter.title
		if (!title.isNullOrBlank()) {
			MULTILANG_CHAPTER_REGEX.find(title)?.groupValues?.get(1)?.toFloatOrNull()?.let { num ->
				if (num > 0f && num < MAX_VALID_CHAPTER_NUMBER && (maxReliable == null || maxReliable <= 0f || num <= maxReliable * 5f)) {
					return num
				}
			}
			CJK_CHAPTER_REGEX.find(title)?.groupValues?.get(1)?.toFloatOrNull()?.let { num ->
				if (num > 0f && num < MAX_VALID_CHAPTER_NUMBER && (maxReliable == null || maxReliable <= 0f || num <= maxReliable * 5f)) {
					return num
				}
			}
			if (allowBareNumbers) {
				BARE_NUMBER_REGEX.find(title)?.groupValues?.get(1)?.toFloatOrNull()?.let { num ->
					if (num > 0f && num < MAX_VALID_CHAPTER_NUMBER && (maxReliable == null || maxReliable <= 0f || num <= maxReliable * 5f)) {
						return num
					}
				}
			}
		}

		// 4. Strict URL pattern (rejects bare ID segments like /chapter/4829301)
		val url = chapter.url
		if (url.isNotEmpty()) {
			STRICT_URL_CHAPTER_REGEX.find(url)?.groupValues?.get(1)?.toFloatOrNull()?.let { num ->
				if (num > 0f && num < MAX_VALID_CHAPTER_NUMBER && (maxReliable == null || maxReliable <= 0f || num <= maxReliable * 5f)) {
					return num
				}
			}
		}

		return 0f
	}

	fun extractPartKey(title: String?): String? {
		if (title.isNullOrBlank()) return null
		return PART_REGEX.find(title)?.groupValues?.get(1)?.lowercase()
	}

	fun normalizeUnnumberedTitle(title: String?): String {
		if (title.isNullOrBlank()) return ""
		return title.replace(SCANLATOR_TAG_REGEX, "")
			.replace(Regex("""\s+"""), " ")
			.trim()
			.lowercase()
	}

	fun detectPerVolumeNumbering(chapters: List<MangaChapter>): Boolean {
		// A series uses per-volume numbering if at least one pair exists where volume increases
		// and chapter number resets/drops back down to a low value (e.g. Vol 1 Ch 20 -> Vol 2 Ch 1).
		// Conversely, global numbering keeps chapter numbers increasing across volumes (e.g. Vol 1 Ch 105, Vol 2 Ch 106).
		var prevVol = 0
		var prevNum = 0f
		for (ch in chapters) {
			val vol = ch.volume
			val num = if (ch.number > 0f) ch.number else 0f
			if (vol > 0 && num > 0f) {
				if (prevVol in 1 until vol && num < prevNum && num <= 5f) {
					return true
				}
				prevVol = vol
				prevNum = num
			}
		}
		return false
	}

	fun computeNewChapters(
		chapters: List<MangaChapter>?,
		history: MangaHistory?,
		anchor: TrackingAnchor? = null,
		preferredBranch: String? = null,
	): NewChaptersResult {
		if (chapters.isNullOrEmpty()) {
			return NewChaptersResult(0, false, 0, null, null, null, emptyList())
		}

		// 1. Branch resolution (prioritize history branch -> anchor branch -> preferredBranch)
		val targetBranch: String? = when {
			history != null && history.chapterId > 0L -> {
				chapters.find { it.id == history.chapterId }?.branch
					?: anchor?.branch
					?: preferredBranch
					?: chapters.firstOrNull()?.branch
			}
			anchor?.branch != null -> anchor.branch
			anchor != null && anchor.lastChapterId > 0L -> {
				chapters.find { it.id == anchor.lastChapterId }?.branch
					?: preferredBranch
					?: chapters.firstOrNull()?.branch
			}
			else -> preferredBranch ?: chapters.firstOrNull()?.branch
		}

		val rawBranchChapters = chapters.filter { it.branch == targetBranch }
		if (rawBranchChapters.isEmpty()) {
			return NewChaptersResult(0, false, 0, null, null, null, emptyList())
		}

		// Check for broken parsers: warn if >= 30% of chapters have no number
		val zeroCount = rawBranchChapters.count { it.number <= 0f }
		if (rawBranchChapters.size >= 10 && (zeroCount.toDouble() / rawBranchChapters.size) > 0.30) {
			val sourceName = rawBranchChapters.firstOrNull()?.source?.name ?: "Unknown"
			Log.w(TAG, "Source \"$sourceName\" has $zeroCount / ${rawBranchChapters.size} unnumbered chapters (number <= 0).")
		}

		val maxReliable = rawBranchChapters
			.filter { it.number > 0.001f && it.number < MAX_VALID_CHAPTER_NUMBER }
			.maxOfOrNull { it.number }

		val allowBareNumbers = rawBranchChapters.none { it.number > 0f } &&
			rawBranchChapters.count { BARE_NUMBER_REGEX.matches(it.title.orEmpty()) } > rawBranchChapters.size * 0.5

		// Conservative descending-order check: only reverse when >= 2 numbered transitions exist and >= 80% descend.
		// Ignore identical adjacent numbers (from multiple scanlators/groups) so they don't dilute the ratio.
		var descendingCount = 0
		var totalDirectionalPairs = 0
		var prevNumberedNum = 0f
		for (ch in rawBranchChapters) {
			val num = extractChapterNumber(ch, maxReliable, allowBareNumbers)
			if (num > 0f) {
				if (prevNumberedNum > 0f && prevNumberedNum != num) {
					totalDirectionalPairs++
					if (prevNumberedNum > num) {
						descendingCount++
					}
				}
				prevNumberedNum = num
			}
		}
		val isDescending = totalDirectionalPairs >= 2 && (descendingCount.toDouble() / totalDirectionalPairs >= 0.80)
		val orderedChapters = if (isDescending) rawBranchChapters.reversed() else rawBranchChapters

		// Stage 1: Canonicalize list.
		// Detect whether this series uses per-volume numbering or global numbering.
		val isPerVolume = detectPerVolumeNumbering(orderedChapters)

		// Find user's preferred / last-read scanlator for tie-breaking
		val historyChapter = if (history != null && history.chapterId > 0L) {
			orderedChapters.find { it.id == history.chapterId }
		} else null
		val preferredScanlator = historyChapter?.scanlator?.lowercase()?.trim()

		val canonicalChapters = ArrayList<CanonicalChapter>()
		val keyToIndex = HashMap<CanonicalKey, Int>()

		for (ch in orderedChapters) {
			val num = extractChapterNumber(ch, maxReliable, allowBareNumbers)
			val vol = if (isPerVolume) ch.volume else 0
			val part = extractPartKey(ch.title)
			val normTitle = if (num <= 0f) normalizeUnnumberedTitle(ch.title) else null

			val key = CanonicalKey(
				volume = vol,
				number = num,
				part = part,
				unnumberedTitle = normTitle,
			)

			val existingIdx = keyToIndex[key]
			if (existingIdx != null) {
				val canonical = canonicalChapters[existingIdx]
				canonical.candidates.add(ch)
				// Prefer user's last-read scanlator, then earliest upload date
				val currentRep = canonical.representativeChapter
				val currentIsPreferred = currentRep.scanlator?.lowercase()?.trim() == preferredScanlator && !preferredScanlator.isNullOrBlank()
				val newIsPreferred = ch.scanlator?.lowercase()?.trim() == preferredScanlator && !preferredScanlator.isNullOrBlank()

				if (!currentIsPreferred) {
					if (newIsPreferred) {
						canonical.representativeChapter = ch
					} else if (ch.uploadDate in 1 until (if (currentRep.uploadDate > 0) currentRep.uploadDate else Long.MAX_VALUE)) {
						canonical.representativeChapter = ch
					}
				}
			} else {
				val canonical = CanonicalChapter(
					key = key,
					number = num,
					volume = vol,
					candidates = mutableListOf(ch),
					representativeChapter = ch,
				)
				keyToIndex[key] = canonicalChapters.size
				canonicalChapters.add(canonical)
			}
		}

		if (canonicalChapters.isEmpty()) {
			return NewChaptersResult(0, false, 0, null, null, null, emptyList())
		}

		// Stage 2: Anchor Logic on Canonical List
		val hasHistory = history != null && (history.chapterId > 0L || (anchor?.lastReadNumber != null && anchor.lastReadNumber > 0f))
		val isCurrentChapterStarted = history != null && (history.page > 0 || history.scroll > 0)
		var anchorIndex = -1
		var baselineToPersist: TrackingAnchor? = null

		if (hasHistory) {
			// Try finding by exact history chapter id in any candidate
			anchorIndex = canonicalChapters.indexOfFirst { canon ->
				canon.candidates.any { it.id == history?.chapterId }
			}

			if (anchorIndex < 0 && anchor?.lastReadNumber != null && anchor.lastReadNumber > 0f) {
				val targetNum = anchor.lastReadNumber
				val targetVol = if (isPerVolume) (anchor.lastReadVolume ?: 0) else 0
				anchorIndex = canonicalChapters.indexOfLast {
					it.number == targetNum && (!isPerVolume || it.volume == targetVol)
				}
				if (anchorIndex < 0) {
					anchorIndex = canonicalChapters.indexOfLast {
						it.number in 0.001f..targetNum && (!isPerVolume || it.volume == targetVol)
					}
				}
			}

			if (anchorIndex < 0) {
				// ID churn with no stored number: graceful fallback by history percent if valid, else index 0
				anchorIndex = if (history != null && history.percent > 0f && history.percent <= 1f) {
					(history.percent * canonicalChapters.size).toInt().coerceIn(0, canonicalChapters.lastIndex)
				} else {
					0
				}
			}

			// If existing user / restore has no snapshot, baseline snapshot to current latest so no flood occurs
			if (anchor?.latestNumberAtLastRead == null) {
				val latestCanonical = canonicalChapters.lastOrNull()
				baselineToPersist = TrackingAnchor(
					lastChapterId = history?.chapterId ?: 0L,
					lastReadVolume = if (anchorIndex in canonicalChapters.indices) canonicalChapters[anchorIndex].volume else null,
					lastReadNumber = if (anchorIndex in canonicalChapters.indices) canonicalChapters[anchorIndex].number else null,
					latestVolumeAtLastRead = latestCanonical?.volume,
					latestNumberAtLastRead = latestCanonical?.number,
					branch = targetBranch,
				)
			}
		} else {
			// No history: check if there's a stored baseline
			anchorIndex = when {
				anchor?.baselineNumber != null && anchor.baselineNumber > 0f -> {
					val targetNum = anchor.baselineNumber
					val targetVol = if (isPerVolume) (anchor.baselineVolume ?: 0) else 0
					val exact = canonicalChapters.indexOfLast {
						it.number == targetNum && (!isPerVolume || it.volume == targetVol)
					}
					if (exact >= 0) exact else canonicalChapters.indexOfLast {
						it.number in 0.001f..targetNum
					}.coerceAtLeast(0)
				}
				anchor?.lastChapterId != null && anchor.lastChapterId > 0L -> {
					canonicalChapters.indexOfFirst { canon ->
						canon.candidates.any { it.id == anchor.lastChapterId }
					}.coerceAtLeast(0)
				}
				else -> {
					// First check ever with no history and no baseline: baseline at latest chapter (0 new)
					val latestCanonical = canonicalChapters.lastOrNull()
					baselineToPersist = TrackingAnchor(
						lastChapterId = latestCanonical?.representativeChapter?.id ?: 0L,
						baselineVolume = latestCanonical?.volume,
						baselineNumber = latestCanonical?.number,
						latestVolumeAtLastRead = latestCanonical?.volume,
						latestNumberAtLastRead = latestCanonical?.number,
						branch = targetBranch,
					)
					canonicalChapters.lastIndex
				}
			}
		}

		// Read Keys Set: all canonical keys at or before anchor
		val readKeys = HashSet<CanonicalKey>()
		if (hasHistory && anchorIndex in canonicalChapters.indices) {
			for (i in 0 until anchorIndex) {
				readKeys.add(canonicalChapters[i].key)
			}
			if (isCurrentChapterStarted) {
				readKeys.add(canonicalChapters[anchorIndex].key)
			}
		} else if (!hasHistory && anchorIndex in canonicalChapters.indices) {
			for (i in 0..anchorIndex) {
				readKeys.add(canonicalChapters[i].key)
			}
		}

		// Slicing candidates
		val startIndex = if (anchorIndex < 0) {
			0
		} else if (!hasHistory) {
			anchorIndex + 1
		} else if (isCurrentChapterStarted) {
			anchorIndex + 1
		} else {
			anchorIndex
		}

		val rawCandidates = if (startIndex in canonicalChapters.indices) {
			canonicalChapters.subList(startIndex, canonicalChapters.size)
		} else {
			emptyList()
		}

		// Reject any candidate whose key is already in readKeys
		val candidateCanonicalChapters = rawCandidates.filter { it.key !in readKeys }

		val count = candidateCanonicalChapters.size
		if (count == 0) {
			return NewChaptersResult(0, false, 0, null, null, null, emptyList(), baselineToPersist)
		}

		val firstNew = candidateCanonicalChapters.first().representativeChapter
		val latest = candidateCanonicalChapters.last().representativeChapter
		val distinctChapters = candidateCanonicalChapters.map { it.representativeChapter }

		// Snapshot & New Releases
		val snapshotNumber = anchor?.latestNumberAtLastRead
		val hasSnapshot = snapshotNumber != null && snapshotNumber > 0f
		val newReleases: List<CanonicalChapter>

		if (hasSnapshot && snapshotNumber != null) {
			val snapVol = if (isPerVolume) (anchor.latestVolumeAtLastRead ?: 0) else 0
			val snapshotIndex = canonicalChapters.indexOfLast {
				it.number == snapshotNumber && (!isPerVolume || it.volume == snapVol || snapVol == 0 || it.volume == 0)
			}

			newReleases = if (snapshotIndex >= 0) {
				// Canonical chapters positioned after the snapshot chapter in list order
				candidateCanonicalChapters.filter { ch ->
					canonicalChapters.indexOf(ch) > snapshotIndex
				}
			} else {
				// Fallback to magnitude if snapshot chapter is not found by index
				candidateCanonicalChapters.filter { ch ->
					if (isPerVolume) {
						when {
							ch.volume > snapVol -> true
							ch.volume < snapVol -> false
							else -> ch.number > snapshotNumber
						}
					} else {
						ch.number > snapshotNumber
					}
				}
			}
		} else {
			newReleases = candidateCanonicalChapters
		}

		val hasNewReleases = if (hasSnapshot) newReleases.isNotEmpty() else true
		val newReleasesCount = if (hasSnapshot) newReleases.size else count

		val firstNum = candidateCanonicalChapters.first().number
		val latestNum = candidateCanonicalChapters.last().number
		val rangeText = when {
			firstNum > 0f && latestNum > 0f -> {
				if (firstNum == latestNum && candidateCanonicalChapters.first().volume == candidateCanonicalChapters.last().volume) {
					"Ch. ${formatNumber(latestNum)}"
				} else {
					"Ch. ${formatNumber(firstNum)}–${formatNumber(latestNum)}"
				}
			}
			latestNum > 0f -> "Ch. ${formatNumber(latestNum)}"
			else -> latest.title ?: "Ch. ?"
		}

		return NewChaptersResult(
			count = count,
			hasNewReleases = hasNewReleases,
			newReleasesCount = newReleasesCount,
			latestChapter = latest,
			firstNewChapter = firstNew,
			rangeText = rangeText,
			chaptersAfterPosition = distinctChapters,
			baselineToPersist = baselineToPersist,
		)
	}

	private fun formatNumber(number: Float): String {
		return if (number % 1f == 0f) {
			number.toInt().toString()
		} else {
			number.toString()
		}
	}
}

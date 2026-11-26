package org.koitharu.kotatsu.scrobbling.anilist

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.anilist.data.AniListTimestampMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class AniListTimestampMapperTest {

	@Test
	fun testUpdatedAtPresent() {
		val updatedAtSec = 1697900000L
		val expectedMillis = 1697900000000L
		val json = JSONObject().apply {
			put("updatedAt", updatedAtSec)
			put("completedAt", JSONObject().apply {
				put("year", 2020)
				put("month", 1)
				put("day", 1)
			})
		}

		val result = AniListTimestampMapper.resolveTimestamp(json)
		assertEquals(expectedMillis, result)
	}

	@Test
	fun testOnlyCompletedAtPresent() {
		val expectedMillis = LocalDate.of(2024, 5, 15)
			.atStartOfDay(ZoneOffset.UTC)
			.toInstant()
			.toEpochMilli()

		val json = JSONObject().apply {
			put("completedAt", JSONObject().apply {
				put("year", 2024)
				put("month", 5)
				put("day", 15)
			})
		}

		val result = AniListTimestampMapper.resolveTimestamp(json)
		assertEquals(expectedMillis, result)
	}

	@Test
	fun testPartialFuzzyDateYearOnly() {
		val expectedMillis = LocalDate.of(2023, 1, 1)
			.atStartOfDay(ZoneOffset.UTC)
			.toInstant()
			.toEpochMilli()

		val dateObj = JSONObject().apply {
			put("year", 2023)
		}

		val parsedDate = AniListTimestampMapper.parseFuzzyDate(dateObj)
		assertEquals(expectedMillis, parsedDate)

		val json = JSONObject().apply {
			put("completedAt", dateObj)
		}
		val result = AniListTimestampMapper.resolveTimestamp(json)
		assertEquals(expectedMillis, result)
	}

	@Test
	fun testAllNullWithoutFallbackReturnsNull() {
		val emptyJson = JSONObject()
		assertNull(AniListTimestampMapper.resolveTimestamp(emptyJson))

		val explicitNullJson = JSONObject().apply {
			put("updatedAt", JSONObject.NULL)
			put("completedAt", JSONObject.NULL)
		}
		assertNull(AniListTimestampMapper.resolveTimestamp(explicitNullJson))
	}

	@Test
	fun testAllNullWithFallbackReturnsFallback() {
		val emptyJson = JSONObject()
		val fallback = 1234567890L
		val withFallback = AniListTimestampMapper.resolveTimestamp(emptyJson, fallbackTimestamp = fallback)
		assertEquals(fallback, withFallback)
	}

	@Test
	fun testZeroValuesWithoutFallbackReturnsNull() {
		val jsonWithZeros = JSONObject().apply {
			put("updatedAt", 0L)
			put("createdAt", 0L)
			put("completedAt", JSONObject().apply {
				put("year", 0)
			})
			put("startedAt", JSONObject().apply {
				put("year", -1)
			})
		}
		assertNull(AniListTimestampMapper.resolveTimestamp(jsonWithZeros))
	}

	@Test
	fun testZeroValuesWithFallbackReturnsFallback() {
		val fallback = 9876543210L
		val jsonWithZeros = JSONObject().apply {
			put("updatedAt", 0L)
			put("createdAt", 0L)
			put("completedAt", JSONObject().apply {
				put("year", 0)
			})
			put("startedAt", JSONObject().apply {
				put("year", -1)
			})
		}
		val withFallback = AniListTimestampMapper.resolveTimestamp(jsonWithZeros, fallbackTimestamp = fallback)
		assertEquals(fallback, withFallback)
	}

	@Test
	fun testDefaultFallbackTimestampUsesInjectedClock() {
		val fixedInstant = Instant.parse("2024-06-01T12:00:00Z")
		val fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
		val expected = fixedInstant.toEpochMilli() - AniListTimestampMapper.THIRTY_DAYS_MS

		val actual = AniListTimestampMapper.defaultFallbackTimestamp(fixedClock)
		assertEquals(expected, actual)
	}
}

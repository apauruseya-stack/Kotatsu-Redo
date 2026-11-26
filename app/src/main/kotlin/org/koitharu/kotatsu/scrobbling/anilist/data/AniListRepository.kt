package org.koitharu.kotatsu.scrobbling.anilist.data

import android.content.Context
import java.util.Locale
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.parsers.exception.GraphQLException
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.toIntUp
import org.koitharu.kotatsu.scrobbling.anilist.domain.model.AniListMediaEntry
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerRepository
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerStorage
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerManga
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaInfo
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerType
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerUser
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

private const val REDIRECT_URI = "kotatsu://anilist-auth"
private const val BASE_URL = "https://anilist.co/api/v2/"
private const val ENDPOINT = "https://graphql.anilist.co"
private const val MANGA_PAGE_SIZE = 10
private const val REQUEST_QUERY = "query"
private const val REQUEST_MUTATION = "mutation"
private const val KEY_SCORE_FORMAT = "score_format"

@Singleton
open class AniListRepository @Inject constructor(
	@ApplicationContext private val context: Context,
	@ScrobblerType(ScrobblerService.ANILIST) private val okHttp: OkHttpClient,
	@ScrobblerType(ScrobblerService.ANILIST) private val storage: ScrobblerStorage,
	private val db: MangaDatabase,
) : ScrobblerRepository {

	private val clientId = context.getString(R.string.anilist_clientId)
	private val clientSecret = context.getString(R.string.anilist_clientSecret)

	override val oauthUrl: String
		get() = "${BASE_URL}oauth/authorize?client_id=$clientId&" +
			"redirect_uri=${REDIRECT_URI}&response_type=code"

	override val isAuthorized: Boolean
		get() = storage.accessToken != null

	private val shrinkRegex = Regex("\\t+")

	override suspend fun authorize(code: String?) {
		val body = FormBody.Builder()
		body.add("client_id", clientId)
		body.add("client_secret", clientSecret)
		if (code != null) {
			body.add("grant_type", "authorization_code")
			body.add("redirect_uri", REDIRECT_URI)
			body.add("code", code)
		} else {
			body.add("grant_type", "refresh_token")
			body.add("refresh_token", checkNotNull(storage.refreshToken))
		}
		val request = Request.Builder()
			.post(body.build())
			.url("${BASE_URL}oauth/token")
		val response = okHttp.newCall(request.build()).await().parseJson()
		storage.accessToken = response.getString("access_token")
		storage.refreshToken = response.getString("refresh_token")
		// Invalidate cached import plan on account change
		runCatching {
			java.io.File(context.cacheDir, "anilist_import_plan.json").delete()
		}
	}

	override suspend fun loadUser(): ScrobblerUser {
		val response = doRequest(
			REQUEST_QUERY,
			"""
			AniChartUser {
				user {
					id
					name
					avatar {
						medium
					}
					mediaListOptions {
						scoreFormat
					}
				}
			}
		""",
		)
		val jo = response.getJSONObject("data").getJSONObject("AniChartUser").getJSONObject("user")
		storage[KEY_SCORE_FORMAT] = jo.getJSONObject("mediaListOptions").getString("scoreFormat")
		return AniListUser(jo).also { storage.user = it }
	}

	override val cachedUser: ScrobblerUser?
		get() {
			return storage.user
		}

	override suspend fun unregister(mangaId: Long) {
		return db.getScrobblingDao().delete(ScrobblerService.ANILIST.id, mangaId)
	}

	override fun logout() {
		storage.clear()
		runCatching {
			java.io.File(context.cacheDir, "anilist_import_plan.json").delete()
		}
	}

	override suspend fun findManga(query: String, offset: Int): List<ScrobblerManga> {
		val page = (offset / MANGA_PAGE_SIZE.toFloat()).toIntUp() + 1
		val response = doRequest(
			REQUEST_QUERY,
			"""
			Page(page: $page, perPage: ${MANGA_PAGE_SIZE}) {
				media(type: MANGA, sort: SEARCH_MATCH, search: ${JSONObject.quote(query)}) {
					id
					title {
						userPreferred
						native
					}
					coverImage {
						medium
					}
					siteUrl
				}
			}
		""",
		)
		val data = response.getJSONObject("data").getJSONObject("Page").getJSONArray("media")
		return data.mapJSON { ScrobblerManga(it, query) }
	}

	override suspend fun createRate(mangaId: Long, scrobblerMangaId: Long) {
		val response = doRequest(
			REQUEST_MUTATION,
			"""
				SaveMediaListEntry(mediaId: $scrobblerMangaId) {
					id
					mediaId
					status
					notes
					score
					progress
				}
			""",
		)
		saveRate(response.getJSONObject("data").getJSONObject("SaveMediaListEntry"), mangaId)
	}

	override suspend fun updateRate(rateId: Int, mangaId: Long, chapter: Int) {
		val response = doRequest(
			REQUEST_MUTATION,
			"""
				SaveMediaListEntry(id: $rateId, progress: $chapter) {
					id
					mediaId
					status
					notes
					score
					progress
				}
			""",
		)
		saveRate(response.getJSONObject("data").getJSONObject("SaveMediaListEntry"), mangaId)
	}

	override suspend fun updateRate(rateId: Int, mangaId: Long, rating: Float, status: String?, comment: String?) {
		val scoreRaw = (rating * 100f).roundToInt()
		val statusString = status?.let { ", status: $it" }.orEmpty()
		val notesString = comment?.let { ", notes: ${JSONObject.quote(it)}" }.orEmpty()
		val response = doRequest(
			REQUEST_MUTATION,
			"""
				SaveMediaListEntry(id: $rateId, scoreRaw: $scoreRaw$statusString$notesString) {
					id
					mediaId
					status
					notes
					score
					progress
				}
			""",
		)
		saveRate(response.getJSONObject("data").getJSONObject("SaveMediaListEntry"), mangaId)
	}

	override suspend fun getMangaInfo(id: Long): ScrobblerMangaInfo {
		val response = doRequest(
			REQUEST_QUERY,
			"""
			Media(id: $id) {
				id
				title {
					userPreferred
				}
				coverImage {
					large
				}
				description
				siteUrl
			}
			""",
		)
		return ScrobblerMangaInfo(response.getJSONObject("data").getJSONObject("Media"))
	}

	suspend fun fetchMediaList(userId: Long): List<AniListMediaEntry> {
		val scoreFormat = ScoreFormat.of(storage[KEY_SCORE_FORMAT])
		val result = ArrayList<AniListMediaEntry>()
		var chunk = 1
		while (true) {
			val response = doRequest(
				REQUEST_QUERY,
				"""
				MediaListCollection(userId: $userId, type: MANGA, chunk: $chunk, perChunk: 500) {
					hasNextChunk
					lists {
						name
						isCustomList
						status
						entries {
							id
							mediaId
							status
							score
							progress
							notes
							updatedAt
							createdAt
							startedAt {
								year
								month
								day
							}
							completedAt {
								year
								month
								day
							}
							media {
								id
								isAdult
								format
								chapters
								siteUrl
								coverImage {
									large
									medium
								}
								title {
									userPreferred
									english
									romaji
									native
								}
								synonyms
							}
						}
					}
				}
				""",
			)
			val collection = response.getJSONObject("data").getJSONObject("MediaListCollection")
			val lists = collection.getJSONArray("lists")
			for (i in 0 until lists.length()) {
				val listObj = lists.getJSONObject(i)
				val listStatus = listObj.optString("status", "").ifEmpty { null }
				val listName = listObj.optString("name", "").trim()
				val resolvedListStatus = listStatus ?: when (listName.uppercase(Locale.ROOT)) {
					"PLANNING", "PLAN TO READ", "READ LATER" -> "PLANNING"
					"READING", "CURRENT" -> "CURRENT"
					"COMPLETED" -> "COMPLETED"
					"PAUSED", "ON HOLD" -> "PAUSED"
					"DROPPED" -> "DROPPED"
					else -> null
				}
				val entries = listObj.getJSONArray("entries")
				for (j in 0 until entries.length()) {
					val entry = entries.getJSONObject(j)
					val media = entry.getJSONObject("media")
					val isAdult = media.optBoolean("isAdult", false)
					val titleObj = media.getJSONObject("title")
					val userPreferred = titleObj.optString("userPreferred", "")
					val allTitles = LinkedHashSet<String>()
					if (userPreferred.isNotEmpty()) allTitles.add(userPreferred)
					titleObj.getStringOrNull("english")?.let { if (it.isNotEmpty()) allTitles.add(it) }
					titleObj.getStringOrNull("romaji")?.let { if (it.isNotEmpty()) allTitles.add(it) }
					titleObj.getStringOrNull("native")?.let { if (it.isNotEmpty()) allTitles.add(it) }
					val synonymsArr = media.optJSONArray("synonyms")
					if (synonymsArr != null) {
						for (s in 0 until synonymsArr.length()) {
							val syn = synonymsArr.optString(s, "").trim()
							if (syn.isNotEmpty()) allTitles.add(syn)
						}
					}

					val coverObj = media.optJSONObject("coverImage")
					val coverUrl = coverObj?.getStringOrNull("large") ?: coverObj?.getStringOrNull("medium")
					val totalChapters = if (media.has("chapters") && !media.isNull("chapters")) {
						media.getInt("chapters")
					} else {
						null
					}

					val entryStatus = entry.optString("status", "").ifEmpty {
						resolvedListStatus ?: when (listName.uppercase(Locale.ROOT)) {
							"PLANNING", "PLAN TO READ", "READ LATER" -> "PLANNING"
							"READING", "CURRENT" -> "CURRENT"
							"COMPLETED" -> "COMPLETED"
							"PAUSED", "ON HOLD" -> "PAUSED"
							"DROPPED" -> "DROPPED"
							else -> "CURRENT"
						}
					}
					val rawScore = entry.optDouble("score", 0.0).toFloat()
					val format = media.getStringOrNull("format")

					val updatedAt = AniListTimestampMapper.resolveTimestamp(entry)

					result.add(
						AniListMediaEntry(
							entryId = entry.getInt("id"),
							mediaId = entry.getLong("mediaId"),
							status = entryStatus,
							progress = entry.optInt("progress", 0),
							score = scoreFormat.normalize(rawScore),
							notes = entry.getStringOrNull("notes"),
							preferredTitle = userPreferred,
							allTitles = allTitles.toList(),
							totalChapters = totalChapters,
							coverUrl = coverUrl,
							siteUrl = media.getStringOrNull("siteUrl"),
							format = format,
							updatedAt = updatedAt,
							isAdult = isAdult,
						),
					)
				}
			}
			val hasNextChunk = collection.optBoolean("hasNextChunk", false)
			if (!hasNextChunk) {
				break
			}
			chunk++
		}
		return result.distinctBy { it.mediaId }
	}

	open suspend fun fetchMediaListUpdatedSince(userId: Long, sinceTimestampSeconds: Long): List<AniListMediaEntry> {
		val scoreFormat = ScoreFormat.of(storage[KEY_SCORE_FORMAT])
		val result = ArrayList<AniListMediaEntry>()
		var page = 1
		var stopPaging = false

		while (!stopPaging) {
			val response = doRequest(
				REQUEST_QUERY,
				"""
				Page(page: $page, perPage: 50) {
					pageInfo {
						hasNextPage
						currentPage
					}
					mediaList(userId: $userId, type: MANGA, sort: [UPDATED_TIME_DESC]) {
						id
						mediaId
						status
						score
						progress
						notes
						updatedAt
						createdAt
						startedAt {
							year
							month
							day
						}
						completedAt {
							year
							month
							day
						}
						media {
							id
							isAdult
							format
							chapters
							siteUrl
							coverImage {
								large
								medium
							}
							title {
								userPreferred
								english
								romaji
								native
							}
							synonyms
						}
					}
				}
				""",
			)
			val pageObj = response.getJSONObject("data").getJSONObject("Page")
			val pageInfo = pageObj.getJSONObject("pageInfo")
			val hasNextPage = pageInfo.optBoolean("hasNextPage", false)
			val entries = pageObj.optJSONArray("mediaList") ?: break

			if (entries.length() == 0) {
				break
			}

			for (i in 0 until entries.length()) {
				val entry = entries.getJSONObject(i)
				val updatedAtSec = if (entry.has("updatedAt") && !entry.isNull("updatedAt")) {
					entry.optLong("updatedAt", 0L)
				} else {
					0L
				}
				if (sinceTimestampSeconds > 0 && updatedAtSec > 0 && updatedAtSec < sinceTimestampSeconds) {
					stopPaging = true
					break
				}
				val media = entry.getJSONObject("media")
				val isAdult = media.optBoolean("isAdult", false)
				val titleObj = media.getJSONObject("title")
				val userPreferred = titleObj.optString("userPreferred", "")
				val allTitles = LinkedHashSet<String>()
				if (userPreferred.isNotEmpty()) allTitles.add(userPreferred)
				titleObj.getStringOrNull("english")?.let { if (it.isNotEmpty()) allTitles.add(it) }
				titleObj.getStringOrNull("romaji")?.let { if (it.isNotEmpty()) allTitles.add(it) }
				titleObj.getStringOrNull("native")?.let { if (it.isNotEmpty()) allTitles.add(it) }
				val synonymsArr = media.optJSONArray("synonyms")
				if (synonymsArr != null) {
					for (s in 0 until synonymsArr.length()) {
						val syn = synonymsArr.optString(s, "").trim()
						if (syn.isNotEmpty()) allTitles.add(syn)
					}
				}

				val coverObj = media.optJSONObject("coverImage")
				val coverUrl = coverObj?.getStringOrNull("large") ?: coverObj?.getStringOrNull("medium")
				val totalChapters = if (media.has("chapters") && !media.isNull("chapters")) {
					media.getInt("chapters")
				} else {
					null
				}
				val rawScore = entry.optDouble("score", 0.0).toFloat()
				val format = media.getStringOrNull("format")
				val updatedAtMs = AniListTimestampMapper.resolveTimestamp(entry)

				result.add(
					AniListMediaEntry(
						entryId = entry.getInt("id"),
						mediaId = entry.getLong("mediaId"),
						status = entry.optString("status", "CURRENT"),
						progress = entry.optInt("progress", 0),
						score = scoreFormat.normalize(rawScore),
						notes = entry.getStringOrNull("notes"),
						preferredTitle = userPreferred,
						allTitles = allTitles.toList(),
						totalChapters = totalChapters,
						coverUrl = coverUrl,
						siteUrl = media.getStringOrNull("siteUrl"),
						format = format,
						updatedAt = updatedAtMs,
						updatedAtSeconds = updatedAtSec,
						isAdult = isAdult,
					),
				)
			}

			if (!hasNextPage) {
				break
			}
			page++
		}
		return result.distinctBy { it.mediaId }
	}

	private suspend fun saveRate(json: JSONObject, mangaId: Long) {
		val scoreFormat = ScoreFormat.of(storage[KEY_SCORE_FORMAT])
		val entity = ScrobblingEntity(
			scrobbler = ScrobblerService.ANILIST.id,
			id = json.getInt("id"),
			mangaId = mangaId,
			targetId = json.getLong("mediaId"),
			status = json.getString("status"),
			chapter = json.getInt("progress"),
			comment = json.getString("notes"),
			rating = scoreFormat.normalize(json.getDouble("score").toFloat()),
		)
		db.getScrobblingDao().upsert(entity)
	}

	private fun ScrobblerManga(json: JSONObject, sourceTitle: String): ScrobblerManga {
		val title = json.getJSONObject("title")
		return ScrobblerManga(
			id = json.getLong("id"),
			name = title.getString("userPreferred"),
			altName = title.getStringOrNull("native"),
			cover = json.getJSONObject("coverImage").getString("medium"),
			url = json.getString("siteUrl"),
			isBestMatch = sourceTitle.let {
				title.keys().forEach { key ->
					if (title.getStringOrNull(key)?.equals(it, ignoreCase = true) == true) {
						return@let true
					}
				}
				false
			},
		)
	}

	private fun ScrobblerMangaInfo(json: JSONObject) = ScrobblerMangaInfo(
		id = json.getLong("id"),
		name = json.getJSONObject("title").getString("userPreferred"),
		cover = json.getJSONObject("coverImage").getString("large"),
		url = json.getString("siteUrl"),
		descriptionHtml = json.getString("description"),
	)

	@Suppress("FunctionName")
	private fun AniListUser(json: JSONObject) = ScrobblerUser(
		id = json.getLong("id"),
		nickname = json.getString("name"),
		avatar = json.getJSONObject("avatar").getStringOrNull("medium"),
		service = ScrobblerService.ANILIST,
	)

	private suspend fun doRequest(type: String, payload: String): JSONObject {
		val body = JSONObject()
		body.put("query", "$type { ${payload.shrink()} }")
		val mediaType = "application/json; charset=utf-8".toMediaType()
		val requestBody = body.toString().toRequestBody(mediaType)
		val request = Request.Builder()
			.post(requestBody)
			.url(ENDPOINT)
		var attempts = 0
		while (true) {
			attempts++
			val response = okHttp.newCall(request.build()).await()
			if (response.code == 401 || response.code == 403) {
				throw AniListAuthExpiredException("AniList authorization expired (HTTP ${response.code})")
			}
			if (response.code == 429 && attempts <= 3) {
				val retryAfterSeconds = response.header("Retry-After")?.toLongOrNull() ?: 5L
				delay((retryAfterSeconds * 1000L).coerceIn(1000L, 60000L))
				continue
			}
			val json = response.parseJson()
			json.optJSONArray("errors")?.let {
				if (it.length() != 0) {
					for (e in 0 until it.length()) {
						val errObj = it.optJSONObject(e)
						val msg = errObj?.optString("message", "") ?: ""
						val status = errObj?.optInt("status", 0) ?: 0
						if (status == 401 || status == 403 ||
							msg.contains("Invalid token", ignoreCase = true) ||
							msg.contains("Unauthenticated", ignoreCase = true) ||
							msg.contains("Unauthorized", ignoreCase = true)
						) {
							throw AniListAuthExpiredException("AniList authorization expired: $msg")
						}
					}
					throw GraphQLException(it)
				}
			}
			return json
		}
	}

	private fun String.shrink() = replace(shrinkRegex, " ")
}

open class AniListAuthExpiredException(
	message: String = "AniList authorization expired. Please log in again.",
	cause: Throwable? = null,
) : java.io.IOException(message, cause)

package org.koitharu.kotatsu.tracker.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@Dao
abstract class TrackingAnchorsDao {

	@Query("SELECT * FROM tracking_anchors WHERE manga_id = :mangaId")
	abstract suspend fun find(mangaId: Long): TrackingAnchorEntity?

	@Query("SELECT * FROM tracking_anchors WHERE manga_id = :mangaId")
	abstract fun observe(mangaId: Long): Flow<TrackingAnchorEntity?>

	@Upsert
	abstract suspend fun upsert(entity: TrackingAnchorEntity)

	@Transaction
	@Query("SELECT * FROM tracking_anchors WHERE new_releases_count > 0 AND status NOT IN (3, 4) ORDER BY new_detected_at DESC LIMIT :limit")
	abstract fun observeNewReleases(limit: Int): Flow<List<MangaWithAnchor>>

	@Transaction
	@Query(
		"""
		SELECT * FROM tracking_anchors 
		WHERE new_releases_count > 0 
			AND status NOT IN (3, 4)
			AND (:includeNsfw = 1 OR (SELECT nsfw FROM manga WHERE manga.manga_id = tracking_anchors.manga_id) = 0)
			AND EXISTS (
				SELECT 1 FROM favourites 
				WHERE favourites.manga_id = tracking_anchors.manga_id 
					AND favourites.deleted_at = 0 
					AND favourites.category_id IN (:categoryIds)
			)
		ORDER BY new_detected_at DESC 
		LIMIT :limit
		""",
	)
	abstract fun observeNewReleasesByCategories(
		categoryIds: Collection<Long>,
		includeNsfw: Boolean,
		limit: Int,
	): Flow<List<MangaWithAnchor>>

	@Transaction
	@Query(
		"""
		SELECT * FROM tracking_anchors 
		WHERE new_releases_count > 0 
			AND status NOT IN (3, 4)
			AND (:includeNsfw = 1 OR (SELECT nsfw FROM manga WHERE manga.manga_id = tracking_anchors.manga_id) = 0)
			AND EXISTS (
				SELECT 1 FROM favourites 
				WHERE favourites.manga_id = tracking_anchors.manga_id 
					AND favourites.deleted_at = 0
			)
		ORDER BY new_detected_at DESC 
		LIMIT :limit
		""",
	)
	abstract fun observeAllNewReleases(
		includeNsfw: Boolean,
		limit: Int,
	): Flow<List<MangaWithAnchor>>

	fun observeRailManga(
		categoryIds: Collection<Long>?,
		includeNsfw: Boolean,
		limit: Int = 20,
	): Flow<List<MangaWithAnchor>> {
		return if (categoryIds == null) {
			observeAllNewReleases(includeNsfw, limit)
		} else if (categoryIds.isEmpty()) {
			flowOf(emptyList())
		} else {
			observeNewReleasesByCategories(categoryIds, includeNsfw, limit)
		}
	}

	@Query(
		"""
		UPDATE tracking_anchors 
		SET last_read_volume = :volume,
			last_read_number = :number,
			branch = COALESCE(:branch, branch)
		WHERE manga_id = :mangaId
		""",
	)
	abstract suspend fun updateLastRead(mangaId: Long, volume: Int?, number: Float?, branch: String?)

	@Query(
		"""
		UPDATE tracking_anchors
		SET latest_vol_at_last_read = :volume,
			latest_num_at_last_read = :number
		WHERE manga_id = :mangaId
			AND (
				latest_vol_at_last_read IS NULL
				OR :volume > latest_vol_at_last_read
				OR (:volume = latest_vol_at_last_read AND :number > latest_num_at_last_read)
			)
		""",
	)
	abstract suspend fun updateSnapshotForward(mangaId: Long, volume: Int, number: Float): Int

	@Query(
		"""
		UPDATE tracking_anchors
		SET latest_vol_at_last_read = :volume,
			latest_num_at_last_read = :number,
			new_releases_count = 0
		WHERE manga_id = :mangaId
		""",
	)
	abstract suspend fun dismissUntil(mangaId: Long, volume: Int?, number: Float?)

	@Query(
		"""
		UPDATE tracking_anchors
		SET pending_shrink_count = :count,
			pending_shrink_hash = :hash
		WHERE manga_id = :mangaId
		""",
	)
	abstract suspend fun updatePendingShrink(mangaId: Long, count: Int, hash: Long)

	@Query(
		"""
		UPDATE tracking_anchors
		SET pending_shrink_count = NULL,
			pending_shrink_hash = NULL
		WHERE manga_id = :mangaId
		""",
	)
	abstract suspend fun clearPendingShrink(mangaId: Long)

	@Query("DELETE FROM tracking_anchors WHERE manga_id = :mangaId")
	abstract suspend fun delete(mangaId: Long)

	@Query("SELECT manga_id FROM tracking_anchors")
	abstract suspend fun findAllIds(): LongArray

	@Query("DELETE FROM tracking_anchors")
	abstract suspend fun clear()

	@Query(
		"DELETE FROM tracking_anchors WHERE manga_id NOT IN " +
			"(SELECT manga_id FROM history WHERE deleted_at = 0 " +
			"UNION SELECT manga_id FROM favourites WHERE deleted_at = 0)",
	)
	abstract suspend fun gc()

	@Query(
		"""
		INSERT OR IGNORE INTO tracking_anchors (
			manga_id,
			new_releases_count,
			new_detected_at,
			last_checked_at,
			status,
			fail_count
		)
		SELECT 
			tracks.manga_id,
			tracks.chapters_new,
			CASE WHEN tracks.last_check_time > 0 THEN tracks.last_check_time ELSE (strftime('%s', 'now') * 1000) END,
			tracks.last_check_time,
			0,
			0
		FROM tracks
		WHERE tracks.chapters_new > 0
		""",
	)
	abstract suspend fun insertMissingFromTracks()

	@Query(
		"""
		UPDATE tracking_anchors
		SET new_releases_count = (SELECT tracks.chapters_new FROM tracks WHERE tracks.manga_id = tracking_anchors.manga_id)
		WHERE new_releases_count = 0 
			AND EXISTS (SELECT 1 FROM tracks WHERE tracks.manga_id = tracking_anchors.manga_id AND tracks.chapters_new > 0)
		""",
	)
	abstract suspend fun updateZeroedFromTracks()

	@Query("UPDATE tracking_anchors SET status = 4 WHERE manga_id = :mangaId")
	abstract suspend fun mute(mangaId: Long)

	@Query("UPDATE tracking_anchors SET status = 0 WHERE manga_id = :mangaId AND status = 4")
	abstract suspend fun unmute(mangaId: Long)

	@Query("SELECT * FROM tracking_anchors WHERE new_releases_count > 0")
	abstract suspend fun findAllWithNewReleases(): List<TrackingAnchorEntity>
}

package org.koitharu.kotatsu.tracker.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.koitharu.kotatsu.core.db.entity.MangaEntity

@Entity(
	tableName = "tracking_anchors",
	indices = [
		Index(value = ["new_releases_count", "new_detected_at"]),
	],
	foreignKeys = [
		ForeignKey(
			entity = MangaEntity::class,
			parentColumns = ["manga_id"],
			childColumns = ["manga_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
)
data class TrackingAnchorEntity(
	@PrimaryKey(autoGenerate = false)
	@ColumnInfo(name = "manga_id") val mangaId: Long,
	@ColumnInfo(name = "last_read_volume") val lastReadVolume: Int?,
	@ColumnInfo(name = "last_read_number") val lastReadNumber: Float?,
	@ColumnInfo(name = "latest_vol_at_last_read") val latestVolumeAtLastRead: Int?,
	@ColumnInfo(name = "latest_num_at_last_read") val latestNumberAtLastRead: Float?,
	@ColumnInfo(name = "baseline_volume") val baselineVolume: Int?,
	@ColumnInfo(name = "baseline_number") val baselineNumber: Float?,
	@ColumnInfo(name = "branch") val branch: String?,
	@ColumnInfo(name = "new_releases_count") val newReleasesCount: Int,
	@ColumnInfo(name = "new_detected_at") val newDetectedAt: Long,
	@ColumnInfo(name = "last_checked_at") val lastCheckedAt: Long,
	@ColumnInfo(name = "status") val status: Int,
	@ColumnInfo(name = "fail_count") val failCount: Int,
	@ColumnInfo(name = "pending_shrink_count") val pendingShrinkCount: Int?,
	@ColumnInfo(name = "pending_shrink_hash") val pendingShrinkHash: Long?,
) {
	companion object {
		const val STATUS_OK = 0
		const val STATUS_FAILED = 1
		const val STATUS_BLOCKED = 2
		const val STATUS_BROKEN = 3
		const val STATUS_MUTED = 4

		fun create(mangaId: Long, branch: String? = null) = TrackingAnchorEntity(
			mangaId = mangaId,
			lastReadVolume = null,
			lastReadNumber = null,
			latestVolumeAtLastRead = null,
			latestNumberAtLastRead = null,
			baselineVolume = null,
			baselineNumber = null,
			branch = branch,
			newReleasesCount = 0,
			newDetectedAt = 0L,
			lastCheckedAt = 0L,
			status = STATUS_OK,
			failCount = 0,
			pendingShrinkCount = null,
			pendingShrinkHash = null,
		)
	}
}

fun TrackingAnchorEntity.toTrackingAnchor() = org.koitharu.kotatsu.tracker.domain.TrackingAnchor(
	lastChapterId = 0L,
	lastReadVolume = lastReadVolume,
	lastReadNumber = lastReadNumber,
	latestVolumeAtLastRead = latestVolumeAtLastRead,
	latestNumberAtLastRead = latestNumberAtLastRead,
	baselineVolume = baselineVolume,
	baselineNumber = baselineNumber,
	branch = branch,
	newDetectedAt = newDetectedAt,
	failCount = failCount,
	status = status,
)

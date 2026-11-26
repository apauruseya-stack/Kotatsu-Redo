package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration28To29 : Migration(28, 29) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""CREATE TABLE IF NOT EXISTS `tracking_anchors` (
				`manga_id` INTEGER NOT NULL PRIMARY KEY,
				`last_read_volume` INTEGER,
				`last_read_number` REAL,
				`latest_vol_at_last_read` INTEGER,
				`latest_num_at_last_read` REAL,
				`baseline_volume` INTEGER,
				`baseline_number` REAL,
				`branch` TEXT,
				`new_releases_count` INTEGER NOT NULL DEFAULT 0,
				`new_detected_at` INTEGER NOT NULL DEFAULT 0,
				`last_checked_at` INTEGER NOT NULL DEFAULT 0,
				`status` INTEGER NOT NULL DEFAULT 0,
				`fail_count` INTEGER NOT NULL DEFAULT 0,
				`pending_shrink_count` INTEGER,
				`pending_shrink_hash` INTEGER,
				FOREIGN KEY(`manga_id`) REFERENCES `manga`(`manga_id`) ON DELETE CASCADE
			)""",
		)
		db.execSQL(
			"""CREATE INDEX IF NOT EXISTS `index_tracking_anchors_new_releases_count_new_detected_at` 
				ON `tracking_anchors` (`new_releases_count`, `new_detected_at`)""",
		)
		db.execSQL(
			"""INSERT OR IGNORE INTO `tracking_anchors` (
				`manga_id`, `new_releases_count`, `new_detected_at`, `last_checked_at`, `status`, `fail_count`
			)
			SELECT `manga_id`, `chapters_new`, 
				CASE WHEN `last_check_time` > 0 THEN `last_check_time` ELSE 0 END, 
				`last_check_time`, 0, 0
			FROM `tracks`
			WHERE `chapters_new` > 0""",
		)
	}
}

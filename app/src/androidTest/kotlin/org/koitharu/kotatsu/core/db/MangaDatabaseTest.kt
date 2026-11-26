package org.koitharu.kotatsu.core.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.db.migrations.Migration28To29

@RunWith(AndroidJUnit4::class)
class MangaDatabaseTest {

	@get:Rule
	val helper: MigrationTestHelper = MigrationTestHelper(
		InstrumentationRegistry.getInstrumentation(),
		MangaDatabase::class.java,
	)

	private val migrations = getDatabaseMigrations(InstrumentationRegistry.getInstrumentation().targetContext)

	@Test
	fun versions() {
		assertEquals(1, migrations.first().startVersion)
		repeat(migrations.size) { i ->
			assertEquals(i + 1, migrations[i].startVersion)
			assertEquals(i + 2, migrations[i].endVersion)
		}
		assertEquals(DATABASE_VERSION, migrations.last().endVersion)
	}

	@Test
	fun migrateAll() {
		helper.createDatabase(TEST_DB, 1).close()
		for (migration in migrations) {
			helper.runMigrationsAndValidate(
				TEST_DB,
				migration.endVersion,
				true,
				migration,
			).close()
		}
	}

	@Test
	fun prePopulate() {
		val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
		helper.createDatabase(TEST_DB, DATABASE_VERSION).use {
			DatabasePrePopulateCallback(resources).onCreate(it)
		}
	}

	@Test
	fun migrate28To29() {
		// Create v28 database with real data
		helper.createDatabase(TEST_DB, 28).apply {
			execSQL(
				"""
				INSERT INTO manga (manga_id, title, url, public_url, rating, nsfw, cover_url, source)
				VALUES (1, 'Frieren', '/manga/1', 'https://example.org/1', 4.9, 0, 'https://example.org/cover.jpg', 'MANGA_DEX')
				""",
			)
			execSQL(
				"""
				INSERT INTO history (manga_id, created_at, updated_at, chapter_id, page, scroll, percent, deleted_at, chapters)
				VALUES (1, 1000, 2000, 10, 0, 0, 0.78, 0, 120)
				""",
			)
			close()
		}

		// Run Migration28To29 and validate schema against exported 29.json
		val db = helper.runMigrationsAndValidate(TEST_DB, 29, true, Migration28To29())

		// Verify existing data preserved
		val cursor = db.query("SELECT manga_id, title FROM manga WHERE manga_id = 1")
		org.junit.Assert.assertTrue(cursor.moveToFirst())
		assertEquals("Frieren", cursor.getString(1))
		cursor.close()

		// Verify new table tracking_anchors exists and is usable
		db.execSQL(
			"""
			INSERT INTO tracking_anchors (manga_id, last_read_number, latest_num_at_last_read, new_releases_count, new_detected_at, last_checked_at, status, fail_count)
			VALUES (1, 80.0, 105.0, 2, 3000, 3000, 0, 0)
			""",
		)
		val anchorCursor = db.query("SELECT last_read_number, new_releases_count FROM tracking_anchors WHERE manga_id = 1")
		org.junit.Assert.assertTrue(anchorCursor.moveToFirst())
		assertEquals(80.0, anchorCursor.getDouble(0), 0.001)
		assertEquals(2, anchorCursor.getInt(1))
		anchorCursor.close()

		db.close()
	}

	private companion object {

		const val TEST_DB = "test-db"
	}
}

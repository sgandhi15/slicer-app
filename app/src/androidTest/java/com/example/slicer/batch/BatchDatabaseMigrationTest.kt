package com.example.slicer.batch

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchDatabaseMigrationTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BatchDatabase::class.java
    )

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(TEST_DATABASE_NAME)
    }

    @Test
    fun migrationFrom1To2AddsSourceLifecycleIndex() {
        migrationHelper.createDatabase(TEST_DATABASE_NAME, 1).close()

        migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            2,
            true,
            BatchDatabase.MIGRATION_1_2
        ).use { database ->
            val indexExists = database.query("PRAGMA index_list(`batch_items`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                generateSequence { cursor.takeIf { it.moveToNext() } }
                    .any { it.getString(nameIndex) == "index_batch_items_sourceUri_state" }
            }
            assertTrue(indexExists)
        }
    }

    @Test
    fun migrationFrom2To3AddsDurableProcessingColumns() {
        migrationHelper.createDatabase(TEST_DATABASE_NAME, 2).use { database ->
            database.execSQL(
                """
                INSERT INTO batches (
                    id, createdAt, completedAt, quality, destinationUri, state,
                    cancellationRequested, totalCount, completedCount, compressedCount,
                    copiedCount, failedCount, cancelledCount, totalInputBytes,
                    totalOutputBytes, totalSavedBytes, lastError
                ) VALUES ('batch', 1, NULL, 80, 'content://destination', 'Running',
                    0, 2, 1, 1, 0, 0, 0, 200, 60, 40, NULL)
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO batch_items (
                    id, batchId, position, sourceUri, inputName, outputName, outputUri,
                    state, attemptCount, inputBytes, outputBytes, message, actualOutputName
                ) VALUES
                    ('complete', 'batch', 0, 'content://complete', 'complete.jpg', 'complete.jpg',
                        'content://output', 'Compressed', 1, 100, 60, NULL, 'complete.jpg'),
                    ('processing', 'batch', 1, 'content://processing', 'processing.jpg',
                        'processing.jpg', NULL, 'Processing', 1, 100, -1, NULL, NULL)
                """.trimIndent()
            )
        }

        migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            3,
            true,
            BatchDatabase.MIGRATION_2_3
        ).use { database ->
            val defaults = database.query("PRAGMA table_info(`batch_items`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
                buildMap {
                    while (cursor.moveToNext()) put(cursor.getString(nameIndex), cursor.getString(defaultIndex))
                }
            }
            assertEquals("0", defaults["savedBytes"])
            assertEquals("-1", defaults["width"])
            assertEquals("-1", defaults["height"])
            assertEquals("0", defaults["outputCleanupPending"])
            val migrated = database.query(
                "SELECT id, savedBytes, outputCleanupPending FROM batch_items ORDER BY position"
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(Triple(cursor.getString(0), cursor.getLong(1), cursor.getInt(2)))
                    }
                }
            }
            assertEquals(listOf(Triple("complete", 40L, 0), Triple("processing", 0L, 1)), migrated)
        }
    }

    @Test
    fun migrationFrom1To3AppliesEveryMigration() {
        migrationHelper.createDatabase(TEST_DATABASE_NAME, 1).close()

        migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            3,
            true,
            BatchDatabase.MIGRATION_1_2,
            BatchDatabase.MIGRATION_2_3
        ).close()
    }

    private companion object {
        const val TEST_DATABASE_NAME = "batch-migration-test"
    }
}

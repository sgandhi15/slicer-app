package com.example.slicer.batch

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
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

    private companion object {
        const val TEST_DATABASE_NAME = "batch-migration-test"
    }
}

package com.example.slicer.batch

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.slicer.core.VideoQuality

@Database(
    entities = [BatchEntity::class, BatchItemEntity::class],
    version = 5,
    exportSchema = true
)
@TypeConverters(BatchConverters::class)
internal abstract class BatchDatabase : RoomDatabase() {
    abstract fun batchDao(): BatchDao

    companion object {
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_batch_items_sourceUri_state`
                    ON `batch_items` (`sourceUri`, `state`)
                    """.trimIndent()
                )
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `batch_items` ADD COLUMN `savedBytes` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `batch_items` ADD COLUMN `width` INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE `batch_items` ADD COLUMN `height` INTEGER NOT NULL DEFAULT -1")
                db.execSQL(
                    "ALTER TABLE `batch_items` ADD COLUMN `outputCleanupPending` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    """
                    UPDATE `batch_items`
                    SET `savedBytes` = `inputBytes` - `outputBytes`
                    WHERE `state` IN ('Compressed', 'CopiedOriginal')
                        AND `outputBytes` >= 0 AND `inputBytes` > `outputBytes`
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE `batch_items`
                    SET `outputCleanupPending` = 1
                    WHERE `state` = 'Processing' AND `outputUri` IS NULL
                    """.trimIndent()
                )
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `batches` ADD COLUMN `maxDimension` INTEGER NOT NULL DEFAULT 0")
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `batches` ADD COLUMN `videoQuality` TEXT NOT NULL DEFAULT 'Balanced'")
                db.execSQL("ALTER TABLE `batches` ADD COLUMN `videoShortSide` INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun create(context: Context): BatchDatabase = Room.databaseBuilder(
            context.applicationContext,
            BatchDatabase::class.java,
            "slicer-batches.db"
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()
    }
}

internal class BatchConverters {
    @TypeConverter
    fun batchStateToString(value: BatchState): String = value.name

    @TypeConverter
    fun stringToBatchState(value: String): BatchState = BatchState.valueOf(value)

    @TypeConverter
    fun itemStateToString(value: BatchItemState): String = value.name

    @TypeConverter
    fun stringToItemState(value: String): BatchItemState = BatchItemState.valueOf(value)

    @TypeConverter
    fun videoQualityToString(value: VideoQuality): String = value.name

    @TypeConverter
    fun stringToVideoQuality(value: String): VideoQuality =
        VideoQuality.entries.firstOrNull { it.name == value } ?: VideoQuality.Balanced
}

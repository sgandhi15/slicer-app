package com.example.slicer.batch

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [BatchEntity::class, BatchItemEntity::class],
    version = 2,
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

        fun create(context: Context): BatchDatabase = Room.databaseBuilder(
            context.applicationContext,
            BatchDatabase::class.java,
            "slicer-batches.db"
        )
            .addMigrations(MIGRATION_1_2)
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
}

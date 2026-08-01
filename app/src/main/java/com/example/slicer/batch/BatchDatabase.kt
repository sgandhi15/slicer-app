package com.example.slicer.batch

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

@Database(
    entities = [BatchEntity::class, BatchItemEntity::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(BatchConverters::class)
internal abstract class BatchDatabase : RoomDatabase() {
    abstract fun batchDao(): BatchDao

    companion object {
        fun create(context: Context): BatchDatabase = Room.databaseBuilder(
            context.applicationContext,
            BatchDatabase::class.java,
            "slicer-batches.db"
        ).build()
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

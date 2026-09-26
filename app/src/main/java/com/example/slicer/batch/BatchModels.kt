package com.example.slicer.batch

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

private const val BATCH_WORK_NAME_PREFIX = "slicer-batch-"
private const val BATCH_CLEANUP_WORK_NAME_PREFIX = "slicer-batch-cleanup-"

internal fun batchWorkName(batchId: String): String = "$BATCH_WORK_NAME_PREFIX$batchId"

internal fun batchCleanupWorkName(batchId: String): String = "$BATCH_CLEANUP_WORK_NAME_PREFIX$batchId"

internal enum class BatchState {
    Queued,
    Running,
    Retrying,
    NeedsDestination,
    Cancelling,
    Completed,
    CompletedWithFailures,
    Cancelled,
    Failed;

    val isActive: Boolean
        get() = this == Queued || this == Running || this == Retrying || this == Cancelling

    val isTerminal: Boolean
        get() = this == Completed || this == CompletedWithFailures || this == Cancelled || this == Failed
}

internal enum class BatchItemState {
    Queued,
    Processing,
    Compressed,
    CopiedOriginal,
    Failed,
    Cancelled;

    val isTerminal: Boolean
        get() = this != Queued && this != Processing
}

@Entity(tableName = "batches")
internal data class BatchEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val completedAt: Long? = null,
    val quality: Int,
    val destinationUri: String,
    val state: BatchState = BatchState.Queued,
    val cancellationRequested: Boolean = false,
    val totalCount: Int,
    val completedCount: Int = 0,
    val compressedCount: Int = 0,
    val copiedCount: Int = 0,
    val failedCount: Int = 0,
    val cancelledCount: Int = 0,
    val totalInputBytes: Long = 0L,
    val totalOutputBytes: Long = 0L,
    val totalSavedBytes: Long = 0L,
    val lastError: String? = null
)

@Entity(
    tableName = "batch_items",
    foreignKeys = [
        ForeignKey(
            entity = BatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["batchId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("batchId"),
        Index(value = ["batchId", "position"], unique = true),
        Index(value = ["sourceUri", "state"])
    ]
)
internal data class BatchItemEntity(
    @PrimaryKey val id: String,
    val batchId: String,
    val position: Int,
    val sourceUri: String,
    val inputName: String? = null,
    val outputName: String? = null,
    val outputUri: String? = null,
    val state: BatchItemState = BatchItemState.Queued,
    val attemptCount: Int = 0,
    val inputBytes: Long = -1L,
    val outputBytes: Long = -1L,
    @ColumnInfo(defaultValue = "0") val savedBytes: Long = 0L,
    @ColumnInfo(defaultValue = "-1") val width: Int = -1,
    @ColumnInfo(defaultValue = "-1") val height: Int = -1,
    val message: String? = null,
    val actualOutputName: String? = null,
    @ColumnInfo(defaultValue = "0") val outputCleanupPending: Boolean = false
)

internal data class BatchItemSummary(
    val completedCount: Int,
    val compressedCount: Int,
    val copiedCount: Int,
    val failedCount: Int,
    val cancelledCount: Int,
    val totalInputBytes: Long,
    val totalOutputBytes: Long,
    val totalSavedBytes: Long
)

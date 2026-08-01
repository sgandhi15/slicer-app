package com.example.slicer

import android.app.Application
import com.example.slicer.batch.BatchDatabase
import com.example.slicer.batch.BatchRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SlicerApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal val database by lazy { BatchDatabase.create(this) }
    internal val batchRepository by lazy { BatchRepository(this, database.batchDao()) }

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch { batchRepository.resumeIncompleteBatches() }
    }
}

package com.example.slicer.telemetry

import com.example.slicer.core.LOG_TAG
import com.example.slicer.processing.MozJpegNative

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

private val mozEncoderTelemetryLogged = AtomicBoolean(false)
private val mozEncoderEventTelemetryLogged = AtomicBoolean(false)

internal fun logEncoderRuntimeStatus() {
    if (mozEncoderTelemetryLogged.compareAndSet(false, true)) {
        Log.i(LOG_TAG, "Encoder telemetry: ${MozJpegNative.runtimeStatusMessage()}")
    }
}

internal fun logMozJpegEventTelemetry(message: String) {
    if (mozEncoderEventTelemetryLogged.compareAndSet(false, true)) {
        Log.w(LOG_TAG, message)
    }
}

package com.example.slicer.telemetry

import com.example.slicer.core.LOG_TAG
import com.example.slicer.processing.MozJpegNative

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

private val mozEncoderTelemetryLogged = AtomicBoolean(false)
private val mozEncoderFallbackTelemetryLogged = AtomicBoolean(false)

internal fun logEncoderRuntimeStatus() {
    if (mozEncoderTelemetryLogged.compareAndSet(false, true)) {
        Log.i(LOG_TAG, "Encoder telemetry: ${MozJpegNative.runtimeStatusMessage()}")
    }
}

internal fun logMozJpegFallbackTelemetry(message: String) {
    if (mozEncoderFallbackTelemetryLogged.compareAndSet(false, true)) {
        Log.w(LOG_TAG, message)
    }
}

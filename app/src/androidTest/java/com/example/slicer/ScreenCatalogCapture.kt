package com.example.slicer

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.example.slicer.batch.BatchEntity
import com.example.slicer.batch.BatchItemEntity
import com.example.slicer.batch.BatchItemState
import com.example.slicer.batch.BatchState
import com.example.slicer.core.DEFAULT_OUTPUT_FOLDER_URI
import com.example.slicer.home.HomeViewModel
import com.example.slicer.storage.ImageSelectionOutcome
import com.example.slicer.storage.ImageSelectionValidator
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in documentation capture. Fixtures and database resets are restricted to an emulator. */
class ScreenCatalogCapture {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val application get() = compose.activity.application as SlicerApplication
    private val files get() = File(application.filesDir, "screen-catalog").apply { mkdirs() }
    private val photos = mutableListOf<Uri>()
    private lateinit var compressed: Uri
    private var sequence = 0L

    @Test
    fun captureScreenCatalog() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("screenCatalog") == "true")
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone")) {
            "Screen catalog fixtures must only run on an emulator."
        }
        listOf("autumn", "bridge", "winter", "surfer", "autumn-compressed").forEach { name ->
            val file = File(application.filesDir, "$name.jpg")
            instrumentation.context.assets.open("screen-catalog/$name.jpg").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            if (name == "autumn-compressed") compressed = Uri.fromFile(file)
            else photos += Uri.fromFile(file)
        }
        runBlocking { application.database.clearAllTables() }
        application.getSharedPreferences("slicer_preferences", 0).edit().clear().commit()
        compose.activityRule.scenario.recreate()
        screen("slicer-welcome-screen")
        capture("01-welcome")
        compose.onNodeWithText("Get started").performClick()
        screen("slicer-setup-screen")
        capture("02-selection-empty")

        compose.onNodeWithText("Select photos & videos").performClick()
        systemWindow("com.google.android.documentsui")
        capture("03-system-image-picker", settle = false)
        instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        screen("slicer-setup-screen")

        // Hold validation so its transient UI can be documented consistently.
        val validationGate = CompletableDeferred<Unit>()
        val viewModel = HomeViewModel(application, SavedStateHandle(), ImageSelectionValidator {
            validationGate.await()
            ImageSelectionOutcome.Supported
        })
        compose.runOnIdle {
            val key = compose.activity.viewModelStore.keys().single { it.endsWith(HomeViewModel::class.java.name) }
            compose.activity.viewModelStore.put(key, viewModel)
        }
        compose.activityRule.scenario.recreate()
        compose.runOnIdle { viewModel.validateImageSelection(photos) }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("image-validation-progress").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        capture("04a-selection-validating")
        validationGate.complete(Unit)
        compose.waitUntil(10_000) { viewModel.selectionState.value.selectedImages.size == 4 }
        compose.onNodeWithText("Selected files").assertIsDisplayed()
        capture("04-selection-populated")
        compose.onNodeWithText("Continue to quality").performClick()
        screen("slicer-quality-screen")
        capture("05-quality-balanced")
        compose.onNodeWithTag("quality-preset-small").performClick()
        capture("05a-quality-small")
        compose.onNodeWithTag("quality-preset-best").performClick()
        capture("05b-quality-best")
        compose.onNodeWithText("Custom quality").performClick()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(67f) }
        capture("06-quality-custom")
        compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }
        capture("07-quality-destination")
        compose.onNodeWithText("Change destination").performClick()
        systemWindow("com.google.android.documentsui")
        capture("08-system-folder-picker", settle = false)
        instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        screen("slicer-quality-screen")

        // The notification prompt is Android-owned UI, shown by the app before its first batch.
        compose.runOnIdle {
            compose.activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 901)
        }
        systemWindow("com.google.android.permissioncontroller")
        capture("09-system-notification-permission", settle = false)
        val deny = instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("allow")
            .first { it.text?.toString()?.startsWith("Don") == true }
        check(deny.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        screen("slicer-quality-screen")
        compose.activityRule.scenario.recreate()

        seed(BatchState.Queued, completed = 0)
        capture("10-processing-preparing")
        seed(BatchState.Running, completed = 2)
        capture("11-processing-active")
        seed(BatchState.Retrying, completed = 2, error = "The output folder is temporarily unavailable. Try again.")
        capture("12-processing-retry")
        seed(BatchState.NeedsDestination, completed = 2, error = "Access to the output folder was lost. Choose a new folder to continue.")
        capture("13-processing-destination-lost")
        seed(BatchState.Cancelling, completed = 2)
        capture("14-processing-cancelling")

        seed(BatchState.Completed, completed = 4)
        compose.onNodeWithTag("slicer-result-row-item-0").assertIsDisplayed()
        capture("15-results-completed")
        compose.onNodeWithTag("slicer-result-row-item-0").performClick()
        screen("slicer-compare-screen")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Loading comparison images").fetchSemanticsNodes().isEmpty()
        }
        capture("16-comparison-ready")
        compose.onNodeWithText("Back").performClick()

        seed(BatchState.CompletedWithFailures, completed = 4)
        capture("17-results-mixed")
        seed(BatchState.Cancelled, completed = 4)
        capture("18-results-cancelled")
        seed(BatchState.Failed, completed = 0, error = "The batch could not be started. Check the output folder and try again.")
        capture("19-results-failed")
        seed(BatchState.Completed, completed = 0)
        capture("20-results-empty")
        seed(BatchState.Completed, completed = 4, missingPreview = true)
        compose.onNodeWithTag("slicer-result-row-item-0").performClick()
        screen("slicer-compare-screen")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Loading comparison images").fetchSemanticsNodes().isEmpty()
        }
        capture("21-comparison-unavailable")
    }

    private fun screen(tag: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun capture(name: String, settle: Boolean = true) {
        if (settle) {
            compose.waitForIdle()
            SystemClock.sleep(1200)
        }
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(files, "$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }

    private fun systemWindow(packageName: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() != packageName) {
            check(SystemClock.elapsedRealtime() < deadline) { "Expected system UI: $packageName" }
            SystemClock.sleep(100)
        }
        instrumentation.uiAutomation.waitForIdle(300, 10_000)
        SystemClock.sleep(800)
    }

    private fun seed(state: BatchState, completed: Int, error: String? = null, missingPreview: Boolean = false) {
        val mixed = state == BatchState.CompletedWithFailures
        val cancelled = state == BatchState.Cancelled
        val itemStates = when {
            mixed -> listOf(BatchItemState.Compressed, BatchItemState.CopiedOriginal, BatchItemState.Failed, BatchItemState.Compressed)
            cancelled -> listOf(BatchItemState.Compressed, BatchItemState.CopiedOriginal, BatchItemState.Cancelled, BatchItemState.Cancelled)
            completed == 4 -> List(4) { BatchItemState.Compressed }
            else -> List(4) { if (it < completed) BatchItemState.Compressed else if (it == completed) BatchItemState.Processing else BatchItemState.Queued }
        }
        val batchId = "catalog-${++sequence}"
        val source = if (missingPreview) "file:///missing/catalog.jpg" else photos.first().toString()
        val batch = BatchEntity(
            id = batchId,
            createdAt = System.currentTimeMillis(),
            completedAt = if (state.isTerminal) System.currentTimeMillis() else null,
            quality = 80,
            destinationUri = DEFAULT_OUTPUT_FOLDER_URI.toString(),
            state = state,
            totalCount = 4,
            completedCount = completed,
            compressedCount = itemStates.count { it == BatchItemState.Compressed },
            copiedCount = itemStates.count { it == BatchItemState.CopiedOriginal },
            failedCount = itemStates.count { it == BatchItemState.Failed },
            cancelledCount = itemStates.count { it == BatchItemState.Cancelled },
            totalInputBytes = completed * 2_400_000L,
            totalOutputBytes = completed * 920_000L,
            totalSavedBytes = completed * 1_480_000L,
            lastError = error
        )
        val items = itemStates.mapIndexed { index, itemState ->
            BatchItemEntity(
                id = "item-$index",
                batchId = batchId,
                position = index,
                sourceUri = if (index == 0) source else photos[index].toString(),
                inputName = listOf("autumn.jpg", "bridge.jpg", "winter.jpg", "surfer.jpg")[index],
                outputUri = if (missingPreview) "file:///missing/output.jpg" else compressed.toString(),
                state = itemState,
                inputBytes = 2_400_000L,
                outputBytes = if (itemState == BatchItemState.CopiedOriginal) 2_400_000L else 920_000L,
                message = when (itemState) {
                    BatchItemState.Failed -> "Could not read this JPEG."
                    BatchItemState.Cancelled -> "Cancelled before processing."
                    else -> null
                }
            )
        }
        runBlocking {
            application.database.clearAllTables()
            application.database.batchDao().createBatch(batch, items)
        }
        compose.activityRule.scenario.recreate()
        screen(if (state.isTerminal) "slicer-results-screen" else "slicer-processing-screen")
        if (completed > 0 && state.isTerminal) {
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("slicer-result-row-item-0").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            }
        }
    }
}

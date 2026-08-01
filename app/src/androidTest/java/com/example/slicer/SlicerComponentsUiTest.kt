package com.example.slicer

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.example.slicer.core.BatchFileResult
import com.example.slicer.core.ProcessingDecision
import com.example.slicer.core.ProcessingProgress
import com.example.slicer.home.BatchSetupContent
import com.example.slicer.home.BatchResultsPanel
import com.example.slicer.home.QualityStepContent
import com.example.slicer.ui.theme.SlicerTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SlicerComponentsUiTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun qualityStepSelectsWholePresetCard() {
        var selectedQuality = 80

        composeTestRule.setContent {
            SlicerTheme {
                Column {
                    QualityStepContent(
                        quality = selectedQuality,
                        controlsEnabled = true,
                        onQualityChange = { selectedQuality = it }
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Compression quality").assertIsDisplayed()
        composeTestRule.onNodeWithTag("quality-preset-small").performClick()
        composeTestRule.runOnIdle { assertTrue(selectedQuality == 55) }
    }

    @Test
    fun emptyBatchPickerOpensImageSelection() {
        var pickerClicked = false

        composeTestRule.setContent {
            SlicerTheme {
                    BatchSetupContent(
                        selectedImages = emptyList(),
                        status = null,
                        isValidating = false,
                        validatedCount = 0,
                        validationTotal = 0,
                        controlsEnabled = true,
                    onSelectImages = { pickerClicked = true },
                    onRemoveImage = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("batch-image-picker").performClick()
        composeTestRule.runOnIdle { assertTrue(pickerClicked) }
    }

    @Test
    fun selectedBatchShowsGridAndRemovesOneImage() {
        val firstUri = Uri.parse("content://com.example.slicer/first.jpg")
        val secondUri = Uri.parse("content://com.example.slicer/second.jpg")
        var removedUri: Uri? = null

        composeTestRule.setContent {
            SlicerTheme {
                    BatchSetupContent(
                        selectedImages = listOf(firstUri, secondUri),
                        status = null,
                        isValidating = false,
                        validatedCount = 0,
                        validationTotal = 0,
                        controlsEnabled = true,
                    onSelectImages = {},
                    onRemoveImage = { removedUri = it }
                )
            }
        }

        composeTestRule.onNodeWithText("Selected images").assertIsDisplayed()
        composeTestRule.onNodeWithTag("selected-image-$firstUri").assertIsDisplayed()
        composeTestRule.onNodeWithTag("selected-image-$secondUri").assertIsDisplayed()
        composeTestRule.onNodeWithTag("remove-image-$firstUri").performClick()
        composeTestRule.runOnIdle { assertTrue(removedUri == firstUri) }
    }

    @Test
    fun selectionValidationShowsProgress() {
        composeTestRule.setContent {
            SlicerTheme {
                BatchSetupContent(
                    selectedImages = emptyList(),
                    status = null,
                    isValidating = true,
                    validatedCount = 2,
                    validationTotal = 5,
                    controlsEnabled = true,
                    onSelectImages = {},
                    onRemoveImage = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("image-validation-progress").assertIsDisplayed()
        composeTestRule.onNodeWithText("Validating 2 of 5").assertIsDisplayed()
    }

    @Test
    fun failedResultRowIsNotClickable() {
        composeTestRule.setContent {
            SlicerTheme {
                BatchResultsPanel(
                    results = listOf(
                        BatchFileResult(
                            id = "failed",
                            inputUri = Uri.parse("content://com.example.slicer/failed.jpg"),
                            inputName = "Failed photo",
                            outputUri = null,
                            decision = ProcessingDecision.Failed,
                            inputBytes = 1200L,
                            outputBytes = 0L,
                            message = "Could not process"
                        )
                    ),
                    summary = ProcessingProgress(total = 1, completed = 1, failedCount = 1),
                    totalInputBytes = 1200L,
                    totalOutputBytes = 0L,
                    totalSavedBytes = 0L,
                    onResultClick = {}
                )
            }
        }

        composeTestRule.onAllNodesWithText("Failed").assertCountEquals(2)
        composeTestRule.onNodeWithTag("slicer-result-row-failed").assertHasNoClickAction()
    }

    @Test
    fun successfulResultRowIsClickable() {
        var clicked = false

        composeTestRule.setContent {
            SlicerTheme {
                BatchResultsPanel(
                    results = listOf(
                        BatchFileResult(
                            id = "success",
                            inputUri = Uri.parse("content://com.example.slicer/success.jpg"),
                            inputName = "Result photo",
                            outputUri = Uri.parse("content://com.example.slicer/success-out.jpg"),
                            decision = ProcessingDecision.Compressed,
                            inputBytes = 1200L,
                            outputBytes = 600L,
                            message = null
                        )
                    ),
                    summary = ProcessingProgress(total = 1, completed = 1, compressedCount = 1),
                    totalInputBytes = 1200L,
                    totalOutputBytes = 600L,
                    totalSavedBytes = 600L,
                    onResultClick = { clicked = true }
                )
            }
        }

        composeTestRule.onNodeWithTag("slicer-result-row-success").performClick()
        assertTrue(clicked)
    }

    @Test
    fun resultListScrollsToAStableItemId() {
        val results = List(50) { index ->
            BatchFileResult(
                id = "result-$index",
                inputUri = Uri.parse("content://com.example.slicer/source/$index.jpg"),
                inputName = "Image $index",
                outputUri = Uri.parse("content://com.example.slicer/output/$index.jpg"),
                decision = ProcessingDecision.Compressed,
                inputBytes = 1200L,
                outputBytes = 600L
            )
        }
        composeTestRule.setContent {
            SlicerTheme {
                BatchResultsPanel(
                    results = results,
                    summary = ProcessingProgress(total = 50, completed = 50, compressedCount = 50),
                    totalInputBytes = 60_000L,
                    totalOutputBytes = 30_000L,
                    totalSavedBytes = 30_000L,
                    onResultClick = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("slicer-result-list")
            .performScrollToNode(hasTestTag("slicer-result-row-result-49"))
        composeTestRule.onNodeWithTag("slicer-result-row-result-49").assertIsDisplayed()
    }

    @Test
    fun resultSummaryUsesPersistedCounters() {
        val result = BatchFileResult(
            id = "summary-result",
            inputUri = Uri.parse("content://com.example.slicer/source/summary.jpg"),
            inputName = "Summary image",
            outputUri = Uri.parse("content://com.example.slicer/output/summary.jpg"),
            decision = ProcessingDecision.Compressed,
            inputBytes = 1200L,
            outputBytes = 600L
        )
        composeTestRule.setContent {
            SlicerTheme {
                BatchResultsPanel(
                    results = listOf(result),
                    summary = ProcessingProgress(
                        total = 12,
                        completed = 12,
                        compressedCount = 7,
                        copiedCount = 3,
                        failedCount = 2
                    ),
                    totalInputBytes = 12_000L,
                    totalOutputBytes = 7_000L,
                    totalSavedBytes = 5_000L,
                    onResultClick = {}
                )
            }
        }

        composeTestRule.onNodeWithText("7").assertIsDisplayed()
        composeTestRule.onNodeWithText("3").assertIsDisplayed()
        composeTestRule.onNodeWithText("2").assertIsDisplayed()
    }
}

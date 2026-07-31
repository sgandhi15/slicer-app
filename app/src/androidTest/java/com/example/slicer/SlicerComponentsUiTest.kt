package com.example.slicer

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.slicer.core.BatchFileResult
import com.example.slicer.core.ProcessingDecision
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
    fun qualityStepShowsBatchAndSelectsWholePresetCard() {
        var selectedQuality = 80
        var editBatchClicked = false

        composeTestRule.setContent {
            SlicerTheme {
                Column {
                    QualityStepContent(
                        quality = selectedQuality,
                        controlsEnabled = true,
                        selectedCount = 3,
                        outputFolderName = "Pictures/Slicer",
                        onQualityChange = { selectedQuality = it },
                        onEditBatch = { editBatchClicked = true }
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Step 2 of 2 - Choose quality").assertIsDisplayed()
        composeTestRule.onNodeWithText("3 images").assertIsDisplayed()
        composeTestRule.onNodeWithTag("quality-preset-small").performClick()
        composeTestRule.runOnIdle { assertTrue(selectedQuality == 55) }
        composeTestRule.onNodeWithText("Edit batch").performClick()
        composeTestRule.runOnIdle { assertTrue(editBatchClicked) }
    }

    @Test
    fun failedResultRowIsNotClickable() {
        composeTestRule.setContent {
            SlicerTheme {
                BatchResultsPanel(
                    results = listOf(
                        BatchFileResult(
                            inputUri = Uri.parse("content://com.example.slicer/failed.jpg"),
                            inputName = "Failed photo",
                            outputUri = null,
                            decision = ProcessingDecision.Failed,
                            inputBytes = 1200L,
                            outputBytes = 0L,
                            message = "Could not process"
                        )
                    ),
                    onResultClick = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Failed").assertIsDisplayed()
        composeTestRule.onNodeWithTag("slicer-result-row-0").assertHasNoClickAction()
    }

    @Test
    fun successfulResultRowIsClickable() {
        var clicked = false

        composeTestRule.setContent {
            SlicerTheme {
                BatchResultsPanel(
                    results = listOf(
                        BatchFileResult(
                            inputUri = Uri.parse("content://com.example.slicer/success.jpg"),
                            inputName = "Result photo",
                            outputUri = Uri.parse("content://com.example.slicer/success-out.jpg"),
                            decision = ProcessingDecision.Compressed,
                            inputBytes = 1200L,
                            outputBytes = 600L,
                            message = null
                        )
                    ),
                    onResultClick = { clicked = true }
                )
            }
        }

        composeTestRule.onNodeWithTag("slicer-result-row-0").performClick()
        assertTrue(clicked)
    }
}

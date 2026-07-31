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

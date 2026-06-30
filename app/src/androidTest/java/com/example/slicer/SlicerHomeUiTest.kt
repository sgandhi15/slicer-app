package com.example.slicer

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Before
import com.example.slicer.ui.theme.SlicerTheme

import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PREFS_FILE_NAME = "slicer_preferences"
private const val PREFS_WELCOME_SCREEN_SEEN_KEY = "welcome_screen_seen"

@RunWith(AndroidJUnit4::class)
class SlicerHomeUiTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetWelcomeState() {
        val activity = composeTestRule.activity
        activity.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREFS_WELCOME_SCREEN_SEEN_KEY, false)
            .apply()
    }

    @Test
    fun welcomeScreenIsShownByDefault() {
        composeTestRule.onNodeWithTag("slicer-welcome-screen", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Welcome to Slicer").assertIsDisplayed()
        composeTestRule.onNodeWithText("Get started").assertIsDisplayed()
        assertTrue(
            "Unexpected fallback text should not appear on welcome screen.",
            composeTestRule.onAllNodesWithText("Process locally. No uploads.").fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun setupScreenAppearsAfterWelcome() {
        composeTestRule.onNodeWithText("Get started").performClick()
        composeTestRule.onNodeWithTag("slicer-setup-screen", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Batch setup").assertIsDisplayed()
        composeTestRule.onNodeWithText("Start Compression").assertIsDisplayed()
            .assertIsNotEnabled()
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

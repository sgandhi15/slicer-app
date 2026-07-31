package com.example.slicer

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Before

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
            .commit()
        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()
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
        composeTestRule.onNodeWithText("Continue to quality").assertIsDisplayed()
            .assertIsNotEnabled()
    }

}

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
import com.example.slicer.batch.BatchEntity
import com.example.slicer.batch.BatchItemEntity
import com.example.slicer.batch.BatchItemState
import com.example.slicer.batch.BatchState
import kotlinx.coroutines.runBlocking
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
        runBlocking {
            (activity.application as SlicerApplication).database.clearAllTables()
        }
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
        composeTestRule.onNodeWithText("Select images").assertIsDisplayed()
        composeTestRule.onNodeWithText("Continue to quality").assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun completedBatchIsRestoredFromRoomAfterActivityRecreation() {
        val application = composeTestRule.activity.application as SlicerApplication
        runBlocking {
            application.database.batchDao().createBatch(
                BatchEntity(
                    id = "restored-batch",
                    createdAt = 1L,
                    completedAt = 2L,
                    quality = 80,
                    destinationUri = "content://destination",
                    state = BatchState.Completed,
                    totalCount = 1,
                    completedCount = 1,
                    compressedCount = 1,
                    totalInputBytes = 100L,
                    totalOutputBytes = 60L,
                    totalSavedBytes = 40L
                ),
                listOf(
                    BatchItemEntity(
                        id = "restored-item",
                        batchId = "restored-batch",
                        position = 0,
                        sourceUri = "content://source/image.jpg",
                        inputName = "image.jpg",
                        outputName = "image.jpg",
                        outputUri = "content://output/image.jpg",
                        state = BatchItemState.Compressed,
                        inputBytes = 100L,
                        outputBytes = 60L,
                        actualOutputName = "image.jpg"
                    )
                )
            )
        }
        composeTestRule.activity.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREFS_WELCOME_SCREEN_SEEN_KEY, true)
            .commit()

        composeTestRule.activityRule.scenario.recreate()

        composeTestRule.onNodeWithTag("slicer-results-screen", useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Done. 1 files written.").assertIsDisplayed()
        composeTestRule.onNodeWithText("image.jpg").assertIsDisplayed()
    }

}

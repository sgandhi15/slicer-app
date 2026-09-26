package com.example.slicer

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.example.slicer.ui.theme.SlicerButton
import com.example.slicer.ui.theme.SlicerOutlinedButton
import com.example.slicer.ui.theme.SlicerTextButton
import com.example.slicer.ui.theme.SlicerTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Verify rendered glyphs rather than only checking theme token values. */
class SlicerStyleTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun lightThemeDisablesAutomaticDarkRecoloring() {
        val attributes = rule.activity.obtainStyledAttributes(
            R.style.Theme_Slicer,
            intArrayOf(android.R.attr.forceDarkAllowed)
        )
        try {
            assertFalse("Android must not recolor the app's explicit palette", attributes.getBoolean(0, true))
        } finally {
            attributes.recycle()
        }
    }

    @Test
    fun buttonLabelsRenderBlackInEnabledAndDisabledStates() {
        rule.setContent {
            SlicerTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxWidth().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Heading", style = MaterialTheme.typography.headlineLarge)
                        Text("Secondary label", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SlicerButton(onClick = {}) { Text("Primary action") }
                        SlicerButton(onClick = {}, enabled = false) { Text("Disabled primary") }
                        SlicerOutlinedButton(onClick = {}) { Text("Secondary action") }
                        SlicerOutlinedButton(onClick = {}, enabled = false) { Text("Disabled secondary") }
                        SlicerTextButton(onClick = {}) { Text("Text action") }
                        SlicerTextButton(onClick = {}, enabled = false) { Text("Disabled text action") }
                    }
                }
            }
        }
        rule.waitForIdle()
        // Keep a screenshot of only the test app's content for visual review.
        val image = rule.onRoot().captureToImage()
        File(rule.activity.filesDir, "style-audit.png").outputStream().use {
            image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        listOf(
            "Heading", "Secondary label", "Primary action", "Disabled primary",
            "Secondary action", "Disabled secondary", "Text action", "Disabled text action"
        ).forEach { label ->
            val pixels = rule.onNodeWithText(label, useUnmergedTree = true).captureToImage().toPixelMap()
            var blackPixels = 0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                if (pixels[x, y] == Color.Black) blackPixels++
            }
            assertTrue("$label must contain solid black glyph pixels; found $blackPixels", blackPixels > 10)
        }
        rule.onNodeWithText("Disabled primary").assertIsNotEnabled()
        rule.onNodeWithText("Disabled secondary").assertIsNotEnabled()
        rule.onNodeWithText("Disabled text action").assertIsNotEnabled()
    }
}

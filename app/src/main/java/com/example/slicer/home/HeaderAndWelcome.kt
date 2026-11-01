package com.example.slicer.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.slicer.R
import com.example.slicer.ui.theme.SlicerButton
import com.example.slicer.ui.theme.slicerPageBackground

@Composable
internal fun WelcomeScreen(
    onContinue: () -> Unit,
    entranceReady: Boolean = true,
    logoReady: Boolean = true,
    logoStartBounds: Rect? = null,
    onLogoPositioned: ((Rect) -> Unit)? = null
) {
    val contentProgress = animateFloatAsState(
        targetValue = if (entranceReady) 1f else 0f,
        animationSpec = tween(durationMillis = 300, delayMillis = 120, easing = FastOutSlowInEasing),
        label = "welcome-group-reveal"
    )
    var logoTargetBounds by remember { mutableStateOf<Rect?>(null) }
    val logoMotion by animateFloatAsState(
        targetValue = if (logoReady) 1f else 0f,
        // Hold the origin during the native splash crossfade, then move the same image to its final layout.
        animationSpec = tween(durationMillis = 240, delayMillis = 60, easing = FastOutSlowInEasing),
        label = "welcome-logo-handoff"
    )
    Column(
        modifier = Modifier.fillMaxSize()
            .testTag("slicer-welcome-screen")
            .slicerPageBackground { contentProgress.value }
            .statusBarsPadding().navigationBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.Start
    ) {
        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.Center
            ) {
                Spacer(Modifier.height(24.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Measure an unmoving anchor, not the transformed image, to avoid geometry feedback.
                    Box(
                        modifier = Modifier.size(56.dp).onGloballyPositioned {
                            val bounds = it.boundsInWindow()
                            logoTargetBounds = bounds
                            onLogoPositioned?.invoke(bounds)
                        }
                    ) {
                        Image(
                            painterResource(R.drawable.logo),
                            contentDescription = "Slicer logo",
                            modifier = Modifier.fillMaxSize()
                                .graphicsLayer {
                                    alpha = if (logoReady) 1f else 0f
                                    val origin = logoStartBounds
                                    val destination = logoTargetBounds
                                    if (origin != null && destination != null && destination.width > 0f) {
                                        val remaining = 1f - logoMotion
                                        val originScale = origin.width / destination.width
                                        scaleX = 1f + (originScale - 1f) * remaining
                                        scaleY = scaleX
                                        translationX = (origin.center.x - destination.center.x) * remaining
                                        translationY = (origin.center.y - destination.center.y) * remaining
                                    }
                                }
                                .then(if (!logoReady) Modifier.clearAndSetSemantics {} else Modifier)
                                .background(colorResource(R.color.slicer_lime), RoundedCornerShape(22))
                        )
                    }
                    Text(
                        "Slicer",
                        modifier = Modifier.welcomeReveal({ contentProgress.value }, entranceReady),
                        style = MaterialTheme.typography.headlineLarge
                    )
                }
                Spacer(Modifier.height(32.dp))
                Text(
                    "Smaller files.\nMore room.",
                    modifier = Modifier.fillMaxWidth().welcomeReveal({ contentProgress.value }, entranceReady),
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontSize = 40.sp,
                        lineHeight = 46.sp,
                        letterSpacing = (-0.5).sp
                    )
                )
                Spacer(Modifier.height(24.dp))
            }
        }
        SlicerButton(
            onClick = onContinue,
            enabled = entranceReady,
            modifier = Modifier.fillMaxWidth().height(56.dp)
                .welcomeReveal({ contentProgress.value }, entranceReady)
        ) {
            Text("Get started")
        }
    }
}

/** Animate drawing only, keeping the final layout measured so the page never jumps. */
private fun Modifier.welcomeReveal(
    progress: () -> Float,
    visible: Boolean
): Modifier {
    // Read the animation inside the layer, so the entrance never recomposes the page.
    return graphicsLayer {
        val value = progress()
        alpha = value
        translationY = 8.dp.toPx() * (1f - value)
    }.then(if (!visible) Modifier.clearAndSetSemantics {} else Modifier)
}

package com.example.slicer.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

@Composable
internal fun AppHeader() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Slicer",
            style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = (-0.6).sp),
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}

@Composable
internal fun WelcomeScreen(onContinue: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("slicer-welcome-screen"),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CompressionIllustration(
                    modifier = Modifier
                        .size(width = 240.dp, height = 220.dp)
                        .padding(16.dp)
                )

                Spacer(modifier = Modifier.height(36.dp))

                Text(
                    text = "Welcome to Slicer",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Make images lighter, without giving up privacy.",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    textAlign = TextAlign.Center
                )
            }

            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Get started", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun CompressionIllustration(modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        CompressionPreviewCard(
            width = 160.dp,
            height = 160.dp,
            isOptimized = false,
            modifier = Modifier
                .align(Alignment.TopStart)
                .graphicsLayer { alpha = 0.56f }
        )

        CompressionPreviewCard(
            width = 128.dp,
            height = 128.dp,
            isOptimized = true,
            modifier = Modifier.align(Alignment.BottomEnd)
        )
    }
}

@Composable
private fun CompressionPreviewCard(
    width: Dp,
    height: Dp,
    isOptimized: Boolean,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    Surface(
        modifier = modifier.size(width = width, height = height),
        shape = MaterialTheme.shapes.small,
        color = colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = if (isOptimized) colorScheme.primary else colorScheme.outline
        )
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        ) {
            drawRect(colorScheme.surfaceVariant)
            drawCircle(
                color = colorScheme.primary.copy(alpha = if (isOptimized) 0.42f else 0.24f),
                radius = size.minDimension * 0.1f,
                center = center.copy(
                    x = size.width * 0.72f,
                    y = size.height * 0.28f
                )
            )

            val mountain = Path().apply {
                moveTo(0f, size.height)
                lineTo(size.width * 0.34f, size.height * 0.48f)
                lineTo(size.width * 0.56f, size.height * 0.72f)
                lineTo(size.width * 0.72f, size.height * 0.56f)
                lineTo(size.width, size.height)
                close()
            }
            drawPath(
                path = mountain,
                color = colorScheme.onPrimaryContainer.copy(
                    alpha = if (isOptimized) 0.88f else 0.56f
                )
            )
        }
    }
}

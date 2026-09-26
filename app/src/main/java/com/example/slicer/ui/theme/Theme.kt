package com.example.slicer.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = SlicerPrimary,
    onPrimary = SlicerOnPrimary,
    secondary = SlicerSecondary,
    onSecondary = SlicerSurface,
    tertiary = SlicerTertiary,
    onTertiary = SlicerSurface,
    background = SlicerBackground,
    onBackground = SlicerOnSurface,
    surface = SlicerSurface,
    onSurface = SlicerOnSurface,
    surfaceVariant = SlicerSurfaceVariant,
    onSurfaceVariant = SlicerOnSurfaceVariant,
    outline = SlicerOutline,
    outlineVariant = SlicerOutline,
    surfaceTint = SlicerSurface,
    error = SlicerError,
    onError = SlicerOnError,
    primaryContainer = SlicerPrimaryContainer,
    onPrimaryContainer = SlicerOnPrimaryContainer,
    secondaryContainer = SlicerSecondaryContainer,
    onSecondaryContainer = SlicerOnSurface,
    tertiaryContainer = SlicerTertiaryContainer,
    onTertiaryContainer = SlicerOnSurface
)

@Composable
fun SlicerTheme(content: @Composable () -> Unit) {
    val colorScheme = LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SlicerTypography,
        shapes = SlicerShapes
    ) {
        CompositionLocalProvider(LocalContentColor provides colorScheme.onSurface, content = content)
    }
}

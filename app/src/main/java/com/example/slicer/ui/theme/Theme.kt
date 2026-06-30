package com.example.slicer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = SlicerPrimary,
    onPrimary = SlicerPrimaryText,
    secondary = SlicerSecondary,
    onSecondary = SlicerPrimaryText,
    tertiary = SlicerTertiary,
    onTertiary = SlicerPrimaryText,
    background = SlicerBackground,
    onBackground = SlicerOnSurface,
    surface = SlicerSurface,
    onSurface = SlicerOnSurface,
    surfaceVariant = SlicerSurfaceVariant,
    onSurfaceVariant = SlicerOnSurfaceVariant,
    outline = SlicerOutline,
    error = SlicerError,
    onError = SlicerOnError,
    primaryContainer = SlicerPrimaryContainer,
    onPrimaryContainer = SlicerOnSurface,
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
        shapes = SlicerShapes,
        content = content
    )
}

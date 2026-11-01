package com.example.slicer.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val InkColorScheme = darkColorScheme(
    primary = SlicerPrimary,
    onPrimary = SlicerOnPrimary,
    secondary = SlicerSecondary,
    onSecondary = SlicerOnPrimary,
    tertiary = SlicerTertiary,
    onTertiary = SlicerOnPrimary,
    background = SlicerBackground,
    onBackground = SlicerOnSurface,
    surface = SlicerSurface,
    onSurface = SlicerOnSurface,
    surfaceVariant = SlicerSurfaceVariant,
    onSurfaceVariant = SlicerOnSurfaceVariant,
    outline = SlicerOutline,
    outlineVariant = SlicerSurfaceVariant,
    surfaceTint = SlicerSurface,
    // Keep elevated surfaces neutral so lime and lilac remain deliberate accents.
    surfaceDim = SlicerSurface,
    surfaceBright = SlicerSurface,
    surfaceContainerLowest = SlicerSurface,
    surfaceContainerLow = SlicerSurface,
    surfaceContainer = SlicerSurface,
    surfaceContainerHigh = SlicerSurface,
    surfaceContainerHighest = SlicerSurface,
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
    val colorScheme = InkColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SlicerTypography,
        shapes = SlicerShapes
    ) {
        CompositionLocalProvider(LocalContentColor provides colorScheme.onSurface, content = content)
    }
}

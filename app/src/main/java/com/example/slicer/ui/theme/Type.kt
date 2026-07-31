package com.example.slicer.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.example.slicer.R

private val DmSerifDisplay = FontFamily(
    Font(R.font.dm_serif_display_regular, FontWeight.Normal)
)

private val DefaultTypography = Typography()

val SlicerTypography = Typography(
    displayLarge = DefaultTypography.displayLarge.copy(fontFamily = DmSerifDisplay),
    displayMedium = DefaultTypography.displayMedium.copy(fontFamily = DmSerifDisplay),
    displaySmall = DefaultTypography.displaySmall.copy(fontFamily = DmSerifDisplay),
    headlineLarge = DefaultTypography.headlineLarge.copy(fontFamily = DmSerifDisplay),
    headlineMedium = DefaultTypography.headlineMedium.copy(fontFamily = DmSerifDisplay),
    headlineSmall = DefaultTypography.headlineSmall.copy(fontFamily = DmSerifDisplay)
)

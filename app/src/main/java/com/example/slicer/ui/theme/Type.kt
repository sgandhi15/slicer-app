package com.example.slicer.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Base = Typography()
private val Heading = FontFamily.SansSerif

val SlicerTypography = Typography(
    displayLarge = Base.displayLarge.copy(fontFamily = Heading, fontWeight = FontWeight.Black),
    displayMedium = Base.displayMedium.copy(fontFamily = Heading, fontWeight = FontWeight.Black),
    displaySmall = Base.displaySmall.copy(fontFamily = Heading, fontWeight = FontWeight.Black),
    headlineLarge = Base.headlineLarge.copy(fontFamily = Heading, fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
    headlineMedium = Base.headlineMedium.copy(fontFamily = Heading, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
    headlineSmall = Base.headlineSmall.copy(fontFamily = Heading, fontWeight = FontWeight.ExtraBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.Bold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.Bold),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.Bold),
    labelSmall = Base.labelSmall.copy(fontFamily = FontFamily.Monospace),
    bodySmall = Base.bodySmall.copy(fontFamily = FontFamily.Monospace)
)

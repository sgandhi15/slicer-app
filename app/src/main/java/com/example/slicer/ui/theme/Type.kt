package com.example.slicer.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.example.slicer.R

// Both files are variable fonts, so each weight is an instance of the same file.
// Compose only picks from declared weights, so list every weight the UI asks for.
private val Weights = listOf(
    FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold,
    FontWeight.Bold, FontWeight.ExtraBold, FontWeight.Black
)

/** Fraunces with its soft, wonky shapes turned fully on. */
@OptIn(ExperimentalTextApi::class)
private fun fraunces(weight: FontWeight, opticalSize: Float) = Font(
    R.font.fraunces,
    weight = weight,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight.weight),
        FontVariation.Setting("opsz", opticalSize),
        FontVariation.Setting("SOFT", 100f),
        FontVariation.Setting("WONK", 1f)
    )
)

@OptIn(ExperimentalTextApi::class)
private fun figtree(weight: FontWeight) = Font(
    R.font.figtree,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
)

/** Large cut for headlines and big numbers. */
private val Display = FontFamily(Weights.map { fraunces(it, opticalSize = 144f) })

/** Mid-size cut so section titles keep their shape without getting cramped. */
private val Title = FontFamily(Weights.map { fraunces(it, opticalSize = 48f) })

private val Body = FontFamily(Weights.map(::figtree))

/** For counts and saved sizes, so numbers carry the same personality as headings. */
internal val SlicerNumbers = Display

private val Base = Typography()

private fun TextStyle.body(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: TextUnit = 0.sp) =
    copy(fontFamily = Body, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = tracking)

val SlicerTypography = Typography(
    displayLarge = Base.displayLarge.copy(fontFamily = Display, fontWeight = FontWeight.Black),
    displayMedium = Base.displayMedium.copy(fontFamily = Display, fontWeight = FontWeight.Black),
    displaySmall = Base.displaySmall.copy(fontFamily = Display, fontWeight = FontWeight.Black),
    headlineLarge = Base.headlineLarge.copy(fontFamily = Display, fontWeight = FontWeight.Black),
    headlineMedium = Base.headlineMedium.copy(fontFamily = Display, fontWeight = FontWeight.Black),
    headlineSmall = Base.headlineSmall.copy(fontFamily = Display, fontWeight = FontWeight.Black),
    titleLarge = Base.titleLarge.copy(fontFamily = Title, fontWeight = FontWeight.Black, fontSize = 24.sp, lineHeight = 30.sp),
    // Everything below is a step larger than Material's defaults so buttons and labels read easily.
    titleMedium = Base.titleMedium.body(17, 24, FontWeight.SemiBold),
    titleSmall = Base.titleSmall.body(15, 22, FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.body(17, 26),
    bodyMedium = Base.bodyMedium.body(15, 22),
    bodySmall = Base.bodySmall.body(13, 18),
    labelLarge = Base.labelLarge.body(17, 24, FontWeight.Bold),
    labelMedium = Base.labelMedium.body(14, 20, FontWeight.SemiBold),
    labelSmall = Base.labelSmall.body(12, 16, FontWeight.SemiBold, tracking = 0.2.sp)
)

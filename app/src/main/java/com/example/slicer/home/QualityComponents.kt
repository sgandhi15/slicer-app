package com.example.slicer.home

import com.example.slicer.core.FULL_SIZE
import com.example.slicer.core.MAX_DIMENSION_OPTIONS
import com.example.slicer.core.MAX_QUALITY
import com.example.slicer.core.MIN_QUALITY
import com.example.slicer.core.QUALITY_PRESETS

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.remember
import com.example.slicer.ui.theme.pressSquish
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import com.example.slicer.ui.theme.SlicerTextButton as TextButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun QualityStepContent(
    quality: Int,
    controlsEnabled: Boolean,
    onQualityChange: (Int) -> Unit
) {
    var showCustomQuality by rememberSaveable {
        mutableStateOf(QUALITY_PRESETS.none { it.quality == quality })
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Compression quality",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            QUALITY_PRESETS.forEach { preset ->
                val isSelected = quality == preset.quality
                val interactionSource = remember { MutableInteractionSource() }
                val cardColor by animateColorAsState(
                    if (isSelected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    label = "preset-color"
                )
                val borderColor by animateColorAsState(
                    MaterialTheme.colorScheme.outline.copy(alpha = if (isSelected) 0.5f else 0.10f),
                    label = "preset-border"
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .pressSquish(interactionSource, pressedScale = 0.98f)
                        .testTag("quality-preset-${preset.label.lowercase()}")
                        .selectable(
                            selected = isSelected,
                            enabled = controlsEnabled,
                            role = Role.RadioButton,
                            interactionSource = interactionSource,
                            indication = null
                        ) {
                            showCustomQuality = false
                            onQualityChange(preset.quality)
                        },
                    shape = MaterialTheme.shapes.small,
                    color = cardColor,
                    border = BorderStroke(width = 1.dp, color = borderColor)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${preset.label} - ${preset.quality}%",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                            Text(
                                text = when (preset.label) {
                                    "Small" -> "Prioritize smaller files."
                                    "Balanced" -> "Balance quality and file size."
                                    else -> "Preserve more image detail."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }

                        RadioButton(
                            selected = isSelected,
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MaterialTheme.colorScheme.onSurface,
                                unselectedColor = MaterialTheme.colorScheme.onSurface,
                                disabledSelectedColor = MaterialTheme.colorScheme.onSurface,
                                disabledUnselectedColor = MaterialTheme.colorScheme.onSurface
                            ),
                            onClick = null,
                            enabled = controlsEnabled
                        )
                    }
                }
            }

            TextButton(
                onClick = { showCustomQuality = !showCustomQuality },
                enabled = controlsEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Text(if (showCustomQuality) "Hide custom quality" else "Custom quality")
            }

            AnimatedVisibility(
                visible = showCustomQuality,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
              Column {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Quality: $quality%",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Slider(
                    value = quality.toFloat(),
                    onValueChange = {
                        onQualityChange(it.roundToInt().coerceIn(MIN_QUALITY, MAX_QUALITY))
                    },
                    valueRange = MIN_QUALITY.toFloat()..MAX_QUALITY.toFloat(),
                    enabled = controlsEnabled
                )
              }
            }
        }
    }

}

@Composable
internal fun ImageSizeSection(
    maxDimension: Int,
    controlsEnabled: Boolean,
    onMaxDimensionChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Image size",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MAX_DIMENSION_OPTIONS.forEach { option ->
                val isSelected = option == maxDimension
                val interactionSource = remember { MutableInteractionSource() }
                val cardColor by animateColorAsState(
                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    label = "size-color"
                )
                val textColor by animateColorAsState(
                    if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    label = "size-text"
                )
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .pressSquish(interactionSource, pressedScale = 0.95f)
                        .selectable(
                            selected = isSelected,
                            enabled = controlsEnabled,
                            role = Role.RadioButton,
                            interactionSource = interactionSource,
                            indication = null
                        ) { onMaxDimensionChange(option) },
                    shape = MaterialTheme.shapes.small,
                    color = cardColor,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
                ) {
                    Text(
                        text = if (option == FULL_SIZE) "Full" else "$option",
                        style = MaterialTheme.typography.labelLarge,
                        color = textColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }
        }
        Text(
            text = if (maxDimension == FULL_SIZE) {
                "Photos keep their original dimensions."
            } else {
                "Longest side up to $maxDimension px. Smaller photos are left at their size."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

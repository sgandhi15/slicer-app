package com.example.slicer.home

import com.example.slicer.core.MAX_QUALITY
import com.example.slicer.core.MIN_QUALITY
import com.example.slicer.core.QUALITY_PRESETS

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            QUALITY_PRESETS.forEach { preset ->
                val isSelected = quality == preset.quality
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(70.dp)
                        .testTag("quality-preset-${preset.label.lowercase()}")
                        .clickable(enabled = controlsEnabled) {
                            showCustomQuality = false
                            onQualityChange(preset.quality)
                        },
                    shape = MaterialTheme.shapes.small,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        }
                    )
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
                                    MaterialTheme.colorScheme.onPrimaryContainer
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
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }

                        RadioButton(
                            selected = isSelected,
                            onClick = null,
                            enabled = controlsEnabled
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = { showCustomQuality = !showCustomQuality },
                enabled = controlsEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = MaterialTheme.shapes.small
            ) {
                Text(if (showCustomQuality) "Hide custom quality" else "Custom quality")
            }

            if (showCustomQuality) {
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

package com.example.slicer.home

import com.example.slicer.core.MAX_QUALITY
import com.example.slicer.core.MIN_QUALITY
import com.example.slicer.core.QUALITY_PRESETS

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun QualitySection(
    quality: Int,
    onQualityChange: (Int) -> Unit,
    onPresetSelected: (Int) -> Unit,
    controlsEnabled: Boolean
) {
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    val presetLabel = QUALITY_PRESETS.find { it.quality == quality }?.label ?: "Custom"
    SectionCard(title = "Quality") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Compression target",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

            }

            Text(
                text = presetLabel,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QUALITY_PRESETS.forEach { preset ->
                val isSelected = quality == preset.quality
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    shape = MaterialTheme.shapes.small,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = preset.label,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                            Text(
                                text = when (preset.label) {
                                    "Small" -> "Prioritize smaller files."
                                    "Balanced" -> "Balanced quality and size."
                                    else -> "Preserve more detail while reducing size."
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
                            onClick = { onPresetSelected(preset.quality) },
                            enabled = controlsEnabled
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedButton(
            onClick = { showAdvanced = !showAdvanced },
            enabled = controlsEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            shape = MaterialTheme.shapes.small
        ) {
            Text(if (showAdvanced) "Hide manual controls" else "Adjust manually")
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (showAdvanced) {
            Spacer(modifier = Modifier.height(4.dp))

            Slider(
                value = quality.toFloat(),
                onValueChange = { onQualityChange(it.roundToInt().coerceIn(MIN_QUALITY, MAX_QUALITY)) },
                valueRange = MIN_QUALITY.toFloat()..MAX_QUALITY.toFloat(),
                enabled = controlsEnabled
            )

        }
    }
}

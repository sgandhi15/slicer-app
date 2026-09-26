package com.example.slicer.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Reserve space for the solid shadow so scroll containers do not clip it. */
@Composable
internal fun Modifier.hardShadow(shape: Shape = MaterialTheme.shapes.small): Modifier {
    val ink = MaterialTheme.colorScheme.outline
    return padding(end = 4.dp, bottom = 4.dp).drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        translate(4.dp.toPx(), 4.dp.toPx()) { drawOutline(outline, ink) }
    }
}

@Composable
internal fun SlicerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.small,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    content: @Composable RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val ink = MaterialTheme.colorScheme.outline
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.padding(end = 4.dp, bottom = 4.dp).drawBehind {
            if (enabled) {
                val offset = if (pressed) 1.dp.toPx() else 4.dp.toPx()
                val outline = shape.createOutline(size, layoutDirection, this)
                translate(offset, offset) { drawOutline(outline, ink) }
            }
        },
        shape = shape,
        colors = colors.copy(
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        border = BorderStroke(2.dp, ink),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
        interactionSource = interaction,
        content = content
    )
}

@Composable
internal fun SlicerOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.small,
    content: @Composable RowScope.() -> Unit
) = SlicerButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = shape,
    colors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ),
    content = content
)

/** Disabled actions keep readable ink; background and semantics convey availability. */
@Composable
internal fun SlicerTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        content = content
    )
}

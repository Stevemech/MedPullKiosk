package com.medpull.kiosk.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medpull.kiosk.data.models.FieldType
import com.medpull.kiosk.data.models.FormField
import kotlin.math.roundToInt

/**
 * Renders all field overlays for the current page, positioned via PdfTransform.
 * Two overlays per field when labelBoundingBox is available:
 *   1. Label overlay (translated text over the original English label)
 *   2. Input overlay (fill-in area for user values)
 */
@Composable
fun FieldOverlayLayer(
    fields: List<FormField>,
    transform: PdfTransform
) {
    Box(modifier = Modifier.fillMaxSize()) {
        fields.forEach { field ->
            // Label overlay at labelBoundingBox (non-interactive, shows translated text)
            field.labelBoundingBox?.let { labelBB ->
                val labelRect = transform.normalizedToScreen(labelBB)
                LabelOverlayBox(
                    field = field,
                    screenX = labelRect.left,
                    screenY = labelRect.top,
                    screenWidth = labelRect.width,
                    screenHeight = labelRect.height
                )
            }

            // Input overlay at boundingBox (interactive, shows value or placeholder)
            val bb = field.boundingBox ?: return@forEach
            val rect = transform.normalizedToScreen(bb)

            InputOverlayBox(
                field = field,
                screenX = rect.left,
                screenY = rect.top,
                screenWidth = rect.width,
                screenHeight = rect.height
            )
        }
    }
}

/**
 * Label overlay — covers the original English label with translated text.
 * White background (90% alpha), muted purple/gray text. Not interactive.
 */
@Composable
private fun LabelOverlayBox(
    field: FormField,
    screenX: Float,
    screenY: Float,
    screenWidth: Float,
    screenHeight: Float
) {
    // Only show if there is a translation to display
    val translatedText = field.translatedText ?: return

    val density = LocalDensity.current
    val fontSizeSp = with(density) {
        (screenHeight * 0.65f).toSp().value.coerceIn(6f, 48f)
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(screenX.roundToInt(), screenY.roundToInt()) }
            .size(
                width = with(density) { screenWidth.toDp() },
                height = with(density) { screenHeight.toDp() }
            )
            .background(Color.White.copy(alpha = 0.90f)),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = translatedText,
            fontSize = fontSizeSp.sp,
            color = Color(0xFF5C5470),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}

/**
 * Input overlay — positioned on the fill-in area (VALUE bounding box).
 *
 * - Unfilled text: transparent bg, thin blue dashed border
 * - Filled text: white bg, dark text, green border
 * - Checkbox: checkbox icon at value position
 */
@Composable
private fun InputOverlayBox(
    field: FormField,
    screenX: Float,
    screenY: Float,
    screenWidth: Float,
    screenHeight: Float
) {
    val density = LocalDensity.current
    val isFilled = !field.value.isNullOrBlank()
    val isCheckbox = field.fieldType == FieldType.CHECKBOX

    val fontSizeSp = with(density) {
        (screenHeight * 0.65f).toSp().value.coerceIn(6f, 48f)
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(screenX.roundToInt(), screenY.roundToInt()) }
            .size(
                width = with(density) { screenWidth.toDp() },
                height = with(density) { screenHeight.toDp() }
            )
            .then(
                if (isCheckbox) {
                    Modifier
                } else if (isFilled) {
                    Modifier
                        .background(Color.White.copy(alpha = 0.95f))
                        .border(
                            width = 1.dp,
                            color = Color(0xFF4CAF50)
                        )
                } else {
                    Modifier
                        .border(
                            width = 1.dp,
                            color = Color(0xFF2196F3)
                        )
                }
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        if (isCheckbox) {
            val checked = field.value == "true" || field.value == "checked"
            Icon(
                imageVector = if (checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                contentDescription = field.fieldName,
                tint = if (checked) Color(0xFF4CAF50) else Color(0xFF757575),
                modifier = Modifier.fillMaxSize()
            )
        } else if (isFilled) {
            Text(
                text = field.value!!,
                fontSize = fontSizeSp.sp,
                color = Color(0xFF212121),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
        }
        // Unfilled non-checkbox: just the border, no text (label overlay shows the translated name)
    }
}

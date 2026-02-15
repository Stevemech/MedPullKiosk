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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medpull.kiosk.data.models.FieldType
import com.medpull.kiosk.data.models.FormField
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Renders field overlays using a Google Lens-inspired two-layer approach:
 *   Layer 1: White rectangles ERASE the original English labels
 *   Layer 2: Translated text RENDERS using the full neighbor-aware available width
 *
 * These layers are independent — the erase layer is sized to the original label bbox,
 * while the text layer uses the wider available space for font sizing and rendering.
 * The form background between labels is white, so text extending beyond the erase
 * layer remains readable.
 */
@Composable
fun FieldOverlayLayer(
    fields: List<FormField>,
    transform: PdfTransform
) {
    val density = LocalDensity.current
    val bleed = with(density) { 3.dp.toPx() }
    val gap = with(density) { 4.dp.toPx() }

    // Right edge of the rendered PDF page on screen
    val pageRightX = transform.pdfOriginX +
        transform.bitmapWidth * transform.baseScale * transform.userScale +
        transform.userOffsetX

    val availableWidths = remember(fields, transform) {
        computeNeighborAwareWidths(fields, transform, gap, pageRightX)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // === LAYER 1: Erase original English labels with white rectangles ===
        fields.forEach { field ->
            field.labelBoundingBox?.let { labelBB ->
                if (field.translatedText != null) {
                    val r = transform.normalizedToScreen(labelBB)
                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (r.left - bleed).roundToInt(),
                                    (r.top - bleed).roundToInt()
                                )
                            }
                            .size(
                                width = with(density) { (r.width + bleed * 2).toDp() },
                                height = with(density) { (r.height + bleed * 2).toDp() }
                            )
                            .background(Color.White)
                    )
                }
            }
        }

        // === LAYER 2: Render translated text in neighbor-aware available space ===
        fields.forEach { field ->
            field.labelBoundingBox?.let { labelBB ->
                val labelRect = transform.normalizedToScreen(labelBB)
                val availWidth = availableWidths[field.id] ?: labelRect.width

                TranslatedLabelText(
                    field = field,
                    screenX = labelRect.left,
                    screenY = labelRect.top,
                    availableWidth = availWidth,
                    screenHeight = labelRect.height
                )
            }

            // Input overlay
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
 * Computes available width for each label using neighbor-aware spatial detection.
 */
private fun computeNeighborAwareWidths(
    fields: List<FormField>,
    transform: PdfTransform,
    gap: Float,
    pageRightX: Float
): Map<String, Float> {
    data class Element(val fieldId: String, val left: Float, val centerY: Float)

    val elements = mutableListOf<Element>()
    val labelRects = mutableMapOf<String, Rect>()
    val inputRects = mutableMapOf<String, Rect>()

    for (field in fields) {
        field.labelBoundingBox?.let { bb ->
            val r = transform.normalizedToScreen(bb)
            labelRects[field.id] = r
            elements.add(Element(field.id, r.left, r.top + r.height / 2f))
        }
        field.boundingBox?.let { bb ->
            val r = transform.normalizedToScreen(bb)
            inputRects[field.id] = r
            elements.add(Element(field.id, r.left, r.top + r.height / 2f))
        }
    }

    val result = mutableMapOf<String, Float>()

    for (field in fields) {
        val labelRect = labelRects[field.id] ?: continue
        val labelLeft = labelRect.left
        val labelCenterY = labelRect.top + labelRect.height / 2f
        val rowTolerance = labelRect.height * 2f

        var nearestRight = Float.MAX_VALUE
        for (elem in elements) {
            if (elem.fieldId == field.id) continue
            if (abs(elem.centerY - labelCenterY) > rowTolerance) continue
            if (elem.left > labelLeft + labelRect.width * 0.3f) {
                nearestRight = minOf(nearestRight, elem.left)
            }
        }

        val effectiveWidth = if (nearestRight < Float.MAX_VALUE) {
            (nearestRight - labelLeft - gap).coerceAtLeast(labelRect.width)
        } else {
            // No right neighbor — extend to page right edge (rightmost labels)
            val inputRight = inputRects[field.id]?.let { it.left + it.width } ?: labelLeft
            val pageEdgeBound = (pageRightX - gap - labelLeft).coerceAtLeast(0f)
            maxOf(labelRect.width, inputRight - labelLeft, pageEdgeBound)
        }

        result[field.id] = effectiveWidth
    }

    return result
}

/**
 * Renders translated text using the full available width for font sizing.
 * No white background — the erase layer already covered the English text,
 * and the form background between fields is white.
 */
@Composable
private fun TranslatedLabelText(
    field: FormField,
    screenX: Float,
    screenY: Float,
    availableWidth: Float,
    screenHeight: Float
) {
    val translatedText = field.translatedText ?: return

    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    val hPad = with(density) { 1.dp.toPx() }
    val textWidthPx = (availableWidth - hPad * 2).roundToInt().coerceAtLeast(1)
    val textHeightPx = screenHeight.roundToInt().coerceAtLeast(1)

    val maxFontSp = with(density) {
        (screenHeight * 0.9f).toSp().value.coerceIn(4f, 48f)
    }
    val minFontSp = 4f

    // Binary search: largest font where text fits in available width × height
    var lo = minFontSp
    var hi = maxFontSp
    repeat(12) {
        val mid = (lo + hi) / 2f
        val m = textMeasurer.measure(
            text = translatedText,
            style = TextStyle(fontSize = mid.sp, lineHeight = (mid * 1.15f).sp),
            softWrap = true,
            overflow = TextOverflow.Clip,
            constraints = Constraints(maxWidth = textWidthPx, maxHeight = textHeightPx)
        )
        if (!m.hasVisualOverflow) lo = mid else hi = mid
    }
    val fontSp = lo

    val lineHeightPx = with(density) { (fontSp * 1.15f).sp.toPx() }
    val maxLines = (screenHeight / lineHeightPx).toInt().coerceAtLeast(1)

    Box(
        modifier = Modifier
            .offset { IntOffset(screenX.roundToInt(), screenY.roundToInt()) }
            .size(
                width = with(density) { availableWidth.toDp() },
                height = with(density) { screenHeight.toDp() }
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = translatedText,
            fontSize = fontSp.sp,
            lineHeight = (fontSp * 1.15f).sp,
            color = Color(0xFF333333),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            softWrap = true,
            modifier = Modifier.padding(horizontal = with(density) { hPad.toDp() })
        )
    }
}

/**
 * Input overlay — positioned on the fill-in area (VALUE bounding box).
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
                if (isCheckbox) Modifier
                else if (isFilled) Modifier.background(Color.White.copy(alpha = 0.95f)).border(1.dp, Color(0xFF4CAF50))
                else Modifier.border(1.dp, Color(0xFF2196F3))
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
    }
}

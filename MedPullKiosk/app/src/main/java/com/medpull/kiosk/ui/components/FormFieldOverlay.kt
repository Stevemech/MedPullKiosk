package com.medpull.kiosk.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medpull.kiosk.data.models.FormField

/**
 * Form field overlay component
 * Displays translucent rectangles over PDF at field locations
 */
@Composable
fun FormFieldOverlay(
    field: FormField,
    isSelected: Boolean,
    onFieldClick: (FormField) -> Unit,
    modifier: Modifier = Modifier
) {
    val boundingBox = field.boundingBox ?: return

    // Calculate position (assuming normalized coordinates 0-1)
    val boxModifier = modifier
        .fillMaxSize()
        .offset(
            x = (boundingBox.left * 100).dp,
            y = (boundingBox.top * 100).dp
        )
        .size(
            width = (boundingBox.width * 100).dp,
            height = (boundingBox.height * 100).dp
        )

    Box(
        modifier = boxModifier
            .background(
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                } else if (field.value.isNullOrBlank()) {
                    Color.Red.copy(alpha = 0.2f)
                } else {
                    Color.Green.copy(alpha = 0.2f)
                }
            )
            .border(
                width = 2.dp,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else if (field.value.isNullOrBlank()) {
                    Color.Red
                } else {
                    Color.Green
                }
            )
            .clickable { onFieldClick(field) }
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        // Show field name or value
        Text(
            text = field.value ?: field.translatedText ?: field.fieldName,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alpha(0.8f)
        )
    }
}

/**
 * Overlay all fields on PDF
 */
@Composable
fun FormFieldsOverlay(
    fields: List<FormField>,
    selectedFieldId: String?,
    currentPage: Int,
    onFieldClick: (FormField) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Filter fields by current page and render overlays
        fields
            .filter { it.page == currentPage + 1 } // PDF pages are 1-indexed
            .forEach { field ->
                FormFieldOverlay(
                    field = field,
                    isSelected = field.id == selectedFieldId,
                    onFieldClick = onFieldClick
                )
            }
    }
}

/**
 * Simplified field marker (just a colored box)
 */
@Composable
fun SimpleFieldMarker(
    field: FormField,
    onFieldClick: (FormField) -> Unit,
    modifier: Modifier = Modifier
) {
    val isFilled = !field.value.isNullOrBlank()

    Box(
        modifier = modifier
            .size(40.dp)
            .background(
                color = if (isFilled) {
                    Color.Green.copy(alpha = 0.3f)
                } else {
                    Color.Red.copy(alpha = 0.3f)
                }
            )
            .border(
                width = 2.dp,
                color = if (isFilled) Color.Green else Color.Red
            )
            .clickable { onFieldClick(field) }
    )
}

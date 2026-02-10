package com.medpull.kiosk.ui.components

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.medpull.kiosk.data.models.BoundingBox
import com.medpull.kiosk.data.models.FieldType
import com.medpull.kiosk.data.models.FormField
import java.io.File

/**
 * Coordinate transform for mapping normalized PDF bounding boxes to screen coordinates.
 */
data class PdfTransform(
    val pdfOriginX: Float,
    val pdfOriginY: Float,
    val bitmapWidth: Int,
    val bitmapHeight: Int,
    val baseScale: Float,
    val userScale: Float,
    val userOffsetX: Float,
    val userOffsetY: Float
) {
    fun normalizedToScreen(bb: BoundingBox): Rect {
        val x = pdfOriginX + (bb.left * bitmapWidth * baseScale * userScale) + userOffsetX
        val y = pdfOriginY + (bb.top * bitmapHeight * baseScale * userScale) + userOffsetY
        val w = bb.width * bitmapWidth * baseScale * userScale
        val h = bb.height * bitmapHeight * baseScale * userScale
        return Rect(x, y, x + w, y + h)
    }
}

/**
 * Full-screen interactive PDF viewer with zoom, pan, tap-to-fill, and field overlays.
 * Zoom/pan state is owned by the parent and passed in, so external controls (buttons)
 * can modify it alongside pinch gestures.
 */
@Composable
fun InteractivePdfViewer(
    pdfFile: File,
    currentPage: Int,
    fields: List<FormField>,
    showOverlays: Boolean,
    userScale: Float,
    userOffsetX: Float,
    userOffsetY: Float,
    onTransformChanged: (scale: Float, offsetX: Float, offsetY: Float) -> Unit,
    onFieldClick: (FormField) -> Unit,
    onCheckboxToggle: (FormField) -> Unit,
    onPageCountLoaded: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // Derived transform
    val transform by remember(bitmap, containerSize, userScale, userOffsetX, userOffsetY) {
        derivedStateOf {
            val bmp = bitmap ?: return@derivedStateOf null
            if (containerSize.width == 0 || containerSize.height == 0) return@derivedStateOf null

            val baseScale = minOf(
                containerSize.width.toFloat() / bmp.width,
                containerSize.height.toFloat() / bmp.height
            )
            val originX = (containerSize.width - bmp.width * baseScale) / 2f
            val originY = (containerSize.height - bmp.height * baseScale) / 2f

            PdfTransform(
                pdfOriginX = originX,
                pdfOriginY = originY,
                bitmapWidth = bmp.width,
                bitmapHeight = bmp.height,
                baseScale = baseScale,
                userScale = userScale,
                userOffsetX = userOffsetX,
                userOffsetY = userOffsetY
            )
        }
    }

    // Fields on current page (PDF pages are 1-indexed in the data model)
    val pageFields = remember(fields, currentPage) {
        fields.filter { it.page == currentPage + 1 }
    }

    // Load PDF bitmap
    LaunchedEffect(pdfFile, currentPage) {
        try {
            val fd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)
            onPageCountLoaded(renderer.pageCount)

            val safePage = currentPage.coerceIn(0, renderer.pageCount - 1)
            val page = renderer.openPage(safePage)

            val width = page.width * 2
            val height = page.height * 2
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap = bmp

            page.close()
            renderer.close()
            fd.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (userScale * zoom).coerceIn(0.5f, 5f)
                    onTransformChanged(
                        newScale,
                        userOffsetX + pan.x,
                        userOffsetY + pan.y
                    )
                }
            }
            .pointerInput(pageFields, transform) {
                detectTapGestures { tapOffset ->
                    val t = transform ?: return@detectTapGestures
                    // Hit-test fields in reverse order (topmost first)
                    for (field in pageFields.reversed()) {
                        val bb = field.boundingBox ?: continue
                        val rect = t.normalizedToScreen(bb)
                        if (rect.contains(Offset(tapOffset.x, tapOffset.y))) {
                            if (field.fieldType == FieldType.CHECKBOX) {
                                onCheckboxToggle(field)
                            } else {
                                onFieldClick(field)
                            }
                            return@detectTapGestures
                        }
                    }
                }
            }
    ) {
        // Draw the PDF bitmap
        bitmap?.let { bmp ->
            Canvas(modifier = Modifier.fillMaxSize()) {
                val baseScale = minOf(
                    size.width / bmp.width,
                    size.height / bmp.height
                )
                val originX = (size.width - bmp.width * baseScale) / 2f
                val originY = (size.height - bmp.height * baseScale) / 2f

                translate(left = originX + userOffsetX, top = originY + userOffsetY) {
                    scale(
                        scaleX = baseScale * userScale,
                        scaleY = baseScale * userScale,
                        pivot = Offset.Zero
                    ) {
                        drawImage(
                            image = bmp.asImageBitmap(),
                            topLeft = Offset.Zero
                        )
                    }
                }
            }
        }

        // Overlay layer
        if (showOverlays) {
            transform?.let { t ->
                FieldOverlayLayer(
                    fields = pageFields,
                    transform = t
                )
            }
        }
    }
}

package com.medpull.kiosk.ui.components

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * PDF Viewer component using Android's PdfRenderer
 * Supports zoom, pan, and page navigation
 */
@Composable
fun PdfViewer(
    pdfFile: File,
    currentPage: Int,
    onPageChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pageCount by remember { mutableStateOf(0) }

    val context = LocalContext.current

    // Load and render PDF page
    LaunchedEffect(pdfFile, currentPage) {
        try {
            val fileDescriptor = ParcelFileDescriptor.open(
                pdfFile,
                ParcelFileDescriptor.MODE_READ_ONLY
            )
            val pdfRenderer = PdfRenderer(fileDescriptor)
            pageCount = pdfRenderer.pageCount

            // Ensure page is within bounds
            val page = currentPage.coerceIn(0, pageCount - 1)
            if (page != currentPage) {
                onPageChange(page)
            }

            // Open the page
            val currentPdfPage = pdfRenderer.openPage(page)

            // Create bitmap
            val width = currentPdfPage.width * 2 // 2x resolution for better quality
            val height = currentPdfPage.height * 2
            val pageBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            // Render page to bitmap
            currentPdfPage.render(
                pageBitmap,
                null,
                null,
                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
            )

            bitmap = pageBitmap

            // Clean up
            currentPdfPage.close()
            pdfRenderer.close()
            fileDescriptor.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // PDF canvas with gestures
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.5f, 3f)
                    offset = Offset(
                        x = (offset.x + pan.x).coerceIn(-1000f, 1000f),
                        y = (offset.y + pan.y).coerceIn(-1000f, 1000f)
                    )
                }
            }
    ) {
        bitmap?.let { bmp ->
            Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                val imageWidth = bmp.width * scale
                val imageHeight = bmp.height * scale

                // Center the image
                val left = (size.width - imageWidth) / 2 + offset.x
                val top = (size.height - imageHeight) / 2 + offset.y

                drawImage(
                    image = bmp.asImageBitmap(),
                    topLeft = Offset(left, top),
                    alpha = 1f
                )
            }
        }
    }
}

/**
 * Simplified PDF viewer for static display
 */
@Composable
fun SimplePdfViewer(
    pdfFile: File,
    page: Int = 0,
    modifier: Modifier = Modifier
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(pdfFile, page) {
        try {
            val fileDescriptor = ParcelFileDescriptor.open(
                pdfFile,
                ParcelFileDescriptor.MODE_READ_ONLY
            )
            val pdfRenderer = PdfRenderer(fileDescriptor)

            val currentPage = page.coerceIn(0, pdfRenderer.pageCount - 1)
            val pdfPage = pdfRenderer.openPage(currentPage)

            val width = pdfPage.width * 2
            val height = pdfPage.height * 2
            val pageBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            pdfPage.render(
                pageBitmap,
                null,
                null,
                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
            )

            bitmap = pageBitmap

            pdfPage.close()
            pdfRenderer.close()
            fileDescriptor.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        bitmap?.let { bmp ->
            Canvas(modifier = Modifier.fillMaxSize()) {
                val aspectRatio = bmp.width.toFloat() / bmp.height.toFloat()
                val canvasAspectRatio = size.width / size.height

                val scale = if (aspectRatio > canvasAspectRatio) {
                    size.width / bmp.width
                } else {
                    size.height / bmp.height
                }

                val imageWidth = bmp.width * scale
                val imageHeight = bmp.height * scale

                val left = (size.width - imageWidth) / 2
                val top = (size.height - imageHeight) / 2

                drawImage(
                    image = bmp.asImageBitmap(),
                    topLeft = Offset(left, top)
                )
            }
        }
    }
}

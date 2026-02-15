package com.medpull.kiosk.data.remote.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renders PDF pages to base64-encoded PNG images for Claude Vision analysis.
 */
@Singleton
class PdfPageRenderer @Inject constructor() {

    companion object {
        private const val TAG = "PdfPageRenderer"
        private const val SCALE_FACTOR = 2 // 2x resolution, same as PdfViewer
    }

    /**
     * Render a single PDF page to a base64-encoded PNG string.
     * Returns null if rendering fails.
     */
    suspend fun renderPageToBase64(pdfFile: File, pageIndex: Int): String? = withContext(Dispatchers.IO) {
        var fd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null
        try {
            fd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(fd)

            if (pageIndex < 0 || pageIndex >= renderer.pageCount) {
                Log.e(TAG, "Page index $pageIndex out of range (0..${renderer.pageCount - 1})")
                return@withContext null
            }

            page = renderer.openPage(pageIndex)
            val width = page.width * SCALE_FACTOR
            val height = page.height * SCALE_FACTOR
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            // Fill with white background — PdfRenderer draws on transparent canvas
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            // Use JPEG for smaller payload (PNG base64 can be 3-4MB per page)
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            bitmap.recycle()

            Log.d(TAG, "Page $pageIndex rendered: ${width}x${height}, ${outputStream.size() / 1024}KB")

            Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to render page $pageIndex", e)
            null
        } finally {
            page?.close()
            renderer?.close()
            fd?.close()
        }
    }

    /**
     * Get the total number of pages in a PDF file.
     */
    suspend fun getPageCount(pdfFile: File): Int = withContext(Dispatchers.IO) {
        var fd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            fd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(fd)
            renderer.pageCount
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get page count", e)
            0
        } finally {
            renderer?.close()
            fd?.close()
        }
    }
}

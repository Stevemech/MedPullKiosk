package com.medpull.kiosk.data.repository

import com.medpull.kiosk.data.models.InventoryItem
import com.medpull.kiosk.data.remote.sheets.GoogleSheetsApiService
import com.medpull.kiosk.utils.Constants

class InventoryRepository(
    private val sheetsApiService: GoogleSheetsApiService
) {
    private var cachedItems: List<InventoryItem>? = null
    private var lastFetchTime: Long = 0L

    suspend fun getInventory(forceRefresh: Boolean = false): Result<List<InventoryItem>> {
        val now = System.currentTimeMillis()
        val cacheValid = cachedItems != null && (now - lastFetchTime) < CACHE_DURATION_MS

        if (!forceRefresh && cacheValid) {
            return Result.success(cachedItems!!)
        }

        return try {
            val allItems = mutableListOf<InventoryItem>()

            for (sheet in SHEET_NAMES) {
                try {
                    val range = "'$sheet'!A:H"
                    val response = sheetsApiService.getValues(
                        spreadsheetId = Constants.GoogleSheets.SPREADSHEET_ID,
                        range = range,
                        apiKey = Constants.GoogleSheets.API_KEY
                    )
                    val rows = response.values ?: emptyList()
                    val items = rows.drop(1).mapNotNull { row -> parseRow(row, sheet) }
                    allItems.addAll(items)
                } catch (e: Exception) {
                    // Skip sheets that fail, continue with others
                }
            }

            cachedItems = allItems
            lastFetchTime = now
            Result.success(allItems)
        } catch (e: Exception) {
            cachedItems?.let { return Result.success(it) }
            Result.failure(e)
        }
    }

    private fun parseRow(row: List<String>, room: String): InventoryItem? {
        if (row.size < 6) return null
        val name = row.getOrElse(1) { "" }.trim()
        if (name.isBlank()) return null
        return try {
            InventoryItem(
                location = row.getOrElse(0) { "" }.trim(),
                itemName = name,
                itemType = row.getOrElse(2) { "" }.trim(),
                category = row.getOrElse(3) { "" }.trim(),
                boxLabel = row.getOrElse(4) { "" }.trim(),
                quantity = row.getOrElse(5) { "0" }.trim().toIntOrNull() ?: 0,
                threshold = row.getOrElse(6) { "0" }.trim().toIntOrNull() ?: 0,
                expirationDates = row.getOrElse(7) { "" }.trim(),
                room = room
            )
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val CACHE_DURATION_MS = 5 * 60 * 1000L
        private val SHEET_NAMES = listOf(
            "Room 1 ",
            "Room 2--Interview Room",
            "Room 3--Provider Room",
            "Room 4--Resource Room"
        )
    }
}

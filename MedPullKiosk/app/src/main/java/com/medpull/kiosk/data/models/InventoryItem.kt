package com.medpull.kiosk.data.models

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class InventoryItem(
    val location: String,
    val itemType: String,
    val category: String,
    val boxLabel: String,
    val quantity: Int,
    val threshold: Int,
    val expirationDates: String,
    val additionalDescriptor: String
) {
    val isLowStock: Boolean
        get() = quantity <= threshold

    val isExpiringSoon: Boolean
        get() {
            if (expirationDates.isBlank()) return false
            val dateFormat = SimpleDateFormat("MM/dd/yyyy", Locale.US)
            val thirtyDaysFromNow = Date(System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000)
            return expirationDates.split(",").any { dateStr ->
                try {
                    val date = dateFormat.parse(dateStr.trim())
                    date != null && date.before(thirtyDaysFromNow)
                } catch (e: Exception) {
                    false
                }
            }
        }
}

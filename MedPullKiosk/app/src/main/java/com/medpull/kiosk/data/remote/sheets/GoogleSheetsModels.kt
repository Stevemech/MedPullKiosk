package com.medpull.kiosk.data.remote.sheets

import com.google.gson.annotations.SerializedName

data class SheetsValuesResponse(
    @SerializedName("range") val range: String?,
    @SerializedName("majorDimension") val majorDimension: String?,
    @SerializedName("values") val values: List<List<String>>?
)

package com.medpull.kiosk.data.remote.ai

import android.util.Log
import com.medpull.kiosk.data.models.BoundingBox
import com.medpull.kiosk.data.models.FieldType
import com.medpull.kiosk.data.models.FormField
import com.medpull.kiosk.data.remote.aws.TextractTableStructure
import java.util.UUID

/**
 * Generates precise FormFields for table cells by combining:
 * - Textract's pixel-precise cell bounding boxes (source of truth for geometry)
 * - Claude Vision's row count and column type metadata (source of truth for completeness)
 *
 * For rows Textract detected: uses exact Textract cell bounding boxes.
 * For extra rows Claude says exist: extrapolates using average row height + column boundaries.
 */
object TableFieldGenerator {

    private const val TAG = "TableFieldGenerator"

    /**
     * Generate FormFields for a single table on a given page.
     *
     * @param tableStructure Textract's extracted table geometry
     * @param visionTableInfo Claude's assessment of the table (row count, column types)
     * @param formId The form ID for generated fields
     * @param page The page number
     * @return List of FormFields representing every fillable cell in the table
     */
    fun generate(
        tableStructure: TextractTableStructure,
        visionTableInfo: VisionTableInfo?,
        formId: String,
        page: Int
    ): List<FormField> {
        val fields = mutableListOf<FormField>()

        val textractDataRows = tableStructure.detectedRowCount
        val actualDataRows = visionTableInfo?.actualDataRowCount
            ?.coerceAtLeast(textractDataRows)
            ?: textractDataRows

        val headers = tableStructure.headerTexts
        val columnTypes = visionTableInfo?.columnTypes ?: emptyList()
        val subColumns = visionTableInfo?.subColumns ?: emptyList()

        // Build expanded headers and types accounting for sub-columns
        val expandedHeaders = mutableListOf<String>()
        val expandedTypes = mutableListOf<String>()
        val expandedLeftEdges = mutableListOf<Float>()
        val expandedWidths = mutableListOf<Float>()
        val isSubColumnFlags = mutableListOf<Boolean>()

        for ((colIdx, header) in headers.withIndex()) {
            val subCol = subColumns.find { it.parentHeader.equals(header, ignoreCase = true) }
            val colType = columnTypes.getOrElse(colIdx) { "TEXT" }
            val colLeft = tableStructure.columnLeftEdges.getOrElse(colIdx) { 0f }
            val colWidth = tableStructure.columnWidths.getOrElse(colIdx) { 0.1f }

            if (subCol != null && subCol.subHeaders.size > 1) {
                // Split this column into sub-columns proportionally
                val subCount = subCol.subHeaders.size
                val subWidth = colWidth / subCount
                for ((subIdx, subHeader) in subCol.subHeaders.withIndex()) {
                    expandedHeaders.add("$header: $subHeader")
                    // Sub-columns under a parent like "Enroll In" are typically checkboxes
                    expandedTypes.add("CHECKBOX")
                    expandedLeftEdges.add(colLeft + subIdx * subWidth)
                    expandedWidths.add(subWidth)
                    isSubColumnFlags.add(true)
                }
            } else {
                expandedHeaders.add(header)
                expandedTypes.add(colType)
                expandedLeftEdges.add(colLeft)
                expandedWidths.add(colWidth)
                isSubColumnFlags.add(false)
            }
        }

        Log.d(TAG, "Table page=$page: ${expandedHeaders.size} columns, " +
            "$textractDataRows Textract rows, $actualDataRows actual rows")

        // Generate fields for each row
        for (dataRow in 1..actualDataRows) {
            for ((colIdx, header) in expandedHeaders.withIndex()) {
                val fieldType = mapColumnType(expandedTypes.getOrElse(colIdx) { "TEXT" })
                val fieldName = if (actualDataRows > 1) "$header (Row $dataRow)" else header

                val boundingBox = findCellBoundingBox(
                    dataRow = dataRow,
                    colIdx = colIdx,
                    isSubColumn = isSubColumnFlags.getOrElse(colIdx) { false },
                    tableStructure = tableStructure,
                    expandedLeftEdges = expandedLeftEdges,
                    expandedWidths = expandedWidths,
                    textractDataRows = textractDataRows,
                    page = page
                )

                // Check if Textract had a value for this cell (checkbox selected, etc.)
                val textractCell = findTextractCell(dataRow, colIdx, tableStructure)
                val value = when {
                    textractCell != null && textractCell.isCheckbox && textractCell.isSelected -> "true"
                    textractCell != null && textractCell.isCheckbox -> ""
                    else -> null
                }

                fields.add(
                    FormField(
                        id = UUID.randomUUID().toString(),
                        formId = formId,
                        fieldName = fieldName,
                        fieldType = fieldType,
                        originalText = header,
                        value = value,
                        boundingBox = boundingBox,
                        confidence = if (dataRow <= textractDataRows) 0.95f else 0.85f,
                        page = page
                    )
                )
            }
        }

        Log.d(TAG, "Generated ${fields.size} table fields for page $page")
        return fields
    }

    /**
     * Find or extrapolate a bounding box for a specific cell.
     * For rows within Textract's range, tries to use Textract's exact cell geometry.
     * For extrapolated rows, computes position from column edges + average row height.
     */
    private fun findCellBoundingBox(
        dataRow: Int,
        colIdx: Int,
        isSubColumn: Boolean,
        tableStructure: TextractTableStructure,
        expandedLeftEdges: List<Float>,
        expandedWidths: List<Float>,
        textractDataRows: Int,
        page: Int
    ): BoundingBox {
        val left = expandedLeftEdges.getOrElse(colIdx) { 0f }
        val width = expandedWidths.getOrElse(colIdx) { 0.1f }

        if (dataRow <= textractDataRows) {
            // Try to find exact Textract cell. Map expanded colIdx back to original col.
            val originalCol = mapExpandedColToOriginal(colIdx, expandedLeftEdges, tableStructure.columnLeftEdges)
            val textractCell = tableStructure.cells.find { it.row == dataRow && it.col == originalCol }
            if (textractCell != null) {
                if (isSubColumn) {
                    // Sub-column: split horizontally using expanded edges, keep Textract vertical
                    return BoundingBox(
                        left = left,
                        top = textractCell.boundingBox.top,
                        width = width,
                        height = textractCell.boundingBox.height,
                        page = page
                    )
                } else {
                    // Direct column: use Textract cell's exact bounding box
                    return textractCell.boundingBox
                }
            }
        }

        // Extrapolate: use last known row top + average row height to compute position
        val lastRowTop = if (tableStructure.rowTopEdges.isNotEmpty()) {
            tableStructure.rowTopEdges.last()
        } else {
            tableStructure.tableBoundingBox.top + tableStructure.averageRowHeight
        }

        val rowsBeforeLastKnown = tableStructure.rowTopEdges.size
        val extraRowsFromLast = dataRow - rowsBeforeLastKnown
        val top = if (extraRowsFromLast > 0) {
            lastRowTop + (extraRowsFromLast * tableStructure.averageRowHeight)
        } else if (dataRow <= tableStructure.rowTopEdges.size) {
            tableStructure.rowTopEdges[dataRow - 1]
        } else {
            lastRowTop + tableStructure.averageRowHeight
        }

        return BoundingBox(
            left = left,
            top = top,
            width = width,
            height = tableStructure.averageRowHeight,
            page = page
        )
    }

    /**
     * Map an expanded column index back to the original Textract column index (1-based).
     * Walks through expanded left edges to find which original column contains this position.
     */
    private fun mapExpandedColToOriginal(
        expandedColIdx: Int,
        expandedLeftEdges: List<Float>,
        originalLeftEdges: List<Float>
    ): Int {
        val expandedLeft = expandedLeftEdges.getOrElse(expandedColIdx) { 0f }
        // Find the original column whose range contains this left edge
        for ((i, origLeft) in originalLeftEdges.withIndex()) {
            val origRight = if (i + 1 < originalLeftEdges.size) {
                originalLeftEdges[i + 1]
            } else {
                1.0f // end of page
            }
            if (expandedLeft >= origLeft - 0.001f && expandedLeft < origRight + 0.001f) {
                return i + 1 // 1-based
            }
        }
        return expandedColIdx + 1 // fallback
    }

    /**
     * Find a matching Textract cell for the given expanded row/column.
     */
    private fun findTextractCell(
        dataRow: Int,
        expandedColIdx: Int,
        tableStructure: TextractTableStructure
    ): com.medpull.kiosk.data.remote.aws.TextractCellInfo? {
        val originalCol = mapExpandedColToOriginal(
            expandedColIdx,
            tableStructure.columnLeftEdges,
            tableStructure.columnLeftEdges
        )
        return tableStructure.cells.find { it.row == dataRow && it.col == originalCol }
    }

    private fun mapColumnType(type: String): FieldType {
        return when (type.uppercase()) {
            "TEXT" -> FieldType.TEXT
            "NUMBER" -> FieldType.NUMBER
            "DATE" -> FieldType.DATE
            "CHECKBOX" -> FieldType.CHECKBOX
            "SIGNATURE" -> FieldType.SIGNATURE
            else -> FieldType.TEXT
        }
    }
}

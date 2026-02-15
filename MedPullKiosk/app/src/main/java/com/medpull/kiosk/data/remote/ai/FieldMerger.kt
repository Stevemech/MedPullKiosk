package com.medpull.kiosk.data.remote.ai

import com.medpull.kiosk.data.models.BoundingBox
import com.medpull.kiosk.data.models.FieldType
import com.medpull.kiosk.data.models.FormField
import java.util.UUID

/**
 * Merges Textract fields with Claude Vision fields using a three-phase approach:
 * 1. Match & Upgrade: Match by IoU or name similarity, keep Textract bbox + Claude type
 * 2. Add Missing: Claude-only fields (fillable, no Textract match) get added
 * 3. Remove False Positives: Unmatched Textract fields flagged by Claude get removed
 */
object FieldMerger {

    private const val IOU_THRESHOLD = 0.3f
    private const val NAME_SIMILARITY_THRESHOLD = 0.6f

    /**
     * Merge Textract fields with Claude Vision results for a single page.
     */
    fun merge(
        textractFields: List<FormField>,
        visionFields: List<ClaudeVisionField>,
        falsePositives: List<String>,
        formId: String,
        page: Int
    ): List<FormField> {
        val pageTextractFields = textractFields.filter { it.page == page }
        val otherPageFields = textractFields.filter { it.page != page }

        val matched = mutableSetOf<Int>() // indices of matched Textract fields
        val matchedVision = mutableSetOf<Int>() // indices of matched Vision fields
        val result = mutableListOf<FormField>()

        // Phase 1: Match & Upgrade
        for ((vi, visionField) in visionFields.withIndex()) {
            if (!visionField.isFillable) continue

            var bestTextractIdx = -1
            var bestScore = 0f

            for ((ti, textractField) in pageTextractFields.withIndex()) {
                if (ti in matched) continue

                val iou = computeIoU(textractField.boundingBox, visionField.boundingBox)
                val nameSim = nameSimilarity(textractField.fieldName, visionField.fieldName)

                val score = maxOf(iou, nameSim)
                if (score > bestScore) {
                    bestScore = score
                    bestTextractIdx = ti
                }
            }

            if (bestScore >= minOf(IOU_THRESHOLD, NAME_SIMILARITY_THRESHOLD) && bestTextractIdx >= 0) {
                // Check if either threshold is met
                val textractField = pageTextractFields[bestTextractIdx]
                val iou = computeIoU(textractField.boundingBox, visionField.boundingBox)
                val nameSim = nameSimilarity(textractField.fieldName, visionField.fieldName)

                if (iou >= IOU_THRESHOLD || nameSim >= NAME_SIMILARITY_THRESHOLD) {
                    matched.add(bestTextractIdx)
                    matchedVision.add(vi)

                    // Keep Textract's precise bounding box, upgrade field type from Claude
                    result.add(
                        textractField.copy(
                            fieldType = mapVisionFieldType(visionField.fieldType, textractField.fieldType),
                            required = visionField.required || textractField.required
                        )
                    )
                }
            }
        }

        // Phase 2: Add Missing — Claude-only fields not matched to Textract
        for ((vi, visionField) in visionFields.withIndex()) {
            if (vi in matchedVision) continue
            if (!visionField.isFillable) continue
            if (visionField.boundingBox == null) continue

            result.add(
                FormField(
                    id = UUID.randomUUID().toString(),
                    formId = formId,
                    fieldName = visionField.fieldName,
                    fieldType = mapVisionFieldType(visionField.fieldType, null),
                    originalText = visionField.fieldName,
                    boundingBox = BoundingBox(
                        left = visionField.boundingBox.left,
                        top = visionField.boundingBox.top,
                        width = visionField.boundingBox.width,
                        height = visionField.boundingBox.height,
                        page = page
                    ),
                    confidence = 0.80f, // Vision-sourced fields get a reasonable confidence
                    required = visionField.required,
                    page = page
                )
            )
        }

        // Phase 3: Remove False Positives — unmatched Textract fields flagged by Claude
        // Uses fuzzy matching: exact match OR substring containment in either direction
        val falsePositiveList = falsePositives.map { it.lowercase().trim() }
        for ((ti, textractField) in pageTextractFields.withIndex()) {
            if (ti in matched) continue // already handled in Phase 1

            val nameKey = textractField.fieldName.lowercase().trim()
            val isFalsePositive = falsePositiveList.any { fp ->
                fp == nameKey || nameKey.contains(fp) || fp.contains(nameKey)
            }
            if (isFalsePositive) {
                // Flagged as not fillable — skip it
                continue
            }

            // Keep unmatched Textract field as-is (conservative — don't remove what Claude didn't flag)
            result.add(textractField)
        }

        return otherPageFields + result.map { fixFieldType(it) }
    }

    /**
     * Fix obviously misclassified field types.
     * Fields with "#" or "No." in their name followed by a blank are number/text inputs, not checkboxes.
     */
    private fun fixFieldType(field: FormField): FormField {
        if (field.fieldType != FieldType.CHECKBOX) return field
        val name = field.fieldName.lowercase()
        if (name.contains("#") || name.contains("no.") || name.contains("id")) {
            return field.copy(fieldType = FieldType.TEXT)
        }
        return field
    }

    /**
     * Merge results from all pages into the final field list.
     *
     * For pages where TableFieldGenerator produced fields (from Textract table structures):
     *   table-pattern fields from Textract are replaced by TableFieldGenerator output.
     * For pages without table structures (Claude returned cell-level fields):
     *   all fields go through normal merge (IoU + name similarity).
     */
    fun mergeAllPages(
        textractFields: List<FormField>,
        pageResults: Map<Int, VisionPageResult>,
        formId: String,
        tableGeneratedFields: List<FormField> = emptyList()
    ): List<FormField> {
        // Pages that have TableFieldGenerator output
        val generatedPages = tableGeneratedFields.map { it.page }.toSet()

        // For pages WITH generated table fields: remove Textract table-pattern fields
        // (they'll be replaced by the generated fields)
        // For pages WITHOUT: keep everything for normal merge
        val filteredTextractFields = if (generatedPages.isNotEmpty()) {
            textractFields.filter { field ->
                !(field.page in generatedPages && isTableField(field.fieldName))
            }
        } else {
            textractFields
        }

        // Run normal merge on filtered fields (includes Claude cell-level fields for non-table pages)
        var currentFields = filteredTextractFields
        if (pageResults.isNotEmpty()) {
            for ((page, result) in pageResults) {
                currentFields = merge(
                    textractFields = currentFields,
                    visionFields = result.fields,
                    falsePositives = result.falsePositives,
                    formId = formId,
                    page = page
                )
            }
        }

        // Append TableFieldGenerator output for pages that have it
        return currentFields + tableGeneratedFields
    }

    /**
     * Check if a field name matches the table field naming pattern "Header (Row N)".
     */
    private fun isTableField(fieldName: String): Boolean {
        return TABLE_FIELD_PATTERN.containsMatchIn(fieldName)
    }

    private val TABLE_FIELD_PATTERN = Regex("""\(Row \d+\)$""")

    /**
     * Compute intersection-over-union between a Textract BoundingBox and a Vision BoundingBox.
     */
    private fun computeIoU(a: BoundingBox?, b: VisionBoundingBox?): Float {
        if (a == null || b == null) return 0f
        val x1 = maxOf(a.left, b.left)
        val y1 = maxOf(a.top, b.top)
        val x2 = minOf(a.left + a.width, b.left + b.width)
        val y2 = minOf(a.top + a.height, b.top + b.height)
        if (x2 <= x1 || y2 <= y1) return 0f
        val intersection = (x2 - x1) * (y2 - y1)
        val areaA = a.width * a.height
        val areaB = b.width * b.height
        val union = areaA + areaB - intersection
        return if (union > 0f) intersection / union else 0f
    }

    /**
     * Compute name similarity using normalized Levenshtein distance.
     */
    private fun nameSimilarity(a: String, b: String): Float {
        val s1 = a.lowercase().trim()
        val s2 = b.lowercase().trim()
        if (s1 == s2) return 1f
        if (s1.isEmpty() || s2.isEmpty()) return 0f

        // Check if one contains the other
        if (s1.contains(s2) || s2.contains(s1)) return 0.8f

        val maxLen = maxOf(s1.length, s2.length)
        val distance = levenshtein(s1, s2)
        return 1f - (distance.toFloat() / maxLen)
    }

    /**
     * Levenshtein edit distance.
     */
    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[a.length][b.length]
    }

    /**
     * Map a Claude Vision field type string to our FieldType enum.
     * If the existing Textract type is more specific (e.g., RADIO), keep it.
     */
    private fun mapVisionFieldType(visionType: String, existingType: FieldType?): FieldType {
        val mapped = when (visionType.uppercase()) {
            "TEXT" -> FieldType.TEXT
            "NUMBER" -> FieldType.NUMBER
            "DATE" -> FieldType.DATE
            "CHECKBOX" -> FieldType.CHECKBOX
            "SIGNATURE" -> FieldType.SIGNATURE
            else -> FieldType.TEXT
        }

        // If Textract had a more specific type (RADIO, DROPDOWN), keep it
        if (existingType != null && existingType != FieldType.TEXT && existingType != FieldType.UNKNOWN) {
            // Only override if Claude says it's a fundamentally different type
            if (mapped == FieldType.TEXT) return existingType
        }

        return mapped
    }
}

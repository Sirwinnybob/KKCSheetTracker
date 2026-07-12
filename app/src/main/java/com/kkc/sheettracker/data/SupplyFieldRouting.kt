package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.SupplySchemaField

/** Result of splitting editor values into the two persisted maps. */
data class RoutedSupplyFields(
    val fields: Map<String, String>,
    val customFields: Map<String, String>
)

/**
 * Split the editor's current values into the builtin `fields` map and the custom
 * `customFields` map according to [schema] (routing by each field's `builtin` flag).
 *
 * Orphan keys — present on the existing item but absent from the current schema — are
 * carried over untouched so they round-trip instead of being silently dropped. Blank
 * edited values are omitted (an emptied field drops its key, matching prior behavior).
 */
fun routeSupplyFieldValues(
    schema: List<SupplySchemaField>,
    editedValues: Map<String, String>,
    existingFields: Map<String, String>,
    existingCustomFields: Map<String, String>
): RoutedSupplyFields {
    val schemaKeys = schema.map { it.key }.toSet()

    // Seed with orphan values (keys the current schema no longer lists) so they survive.
    val fields = existingFields.filterKeys { it !in schemaKeys }.toMutableMap()
    val customFields = existingCustomFields.filterKeys { it !in schemaKeys }.toMutableMap()

    for (field in schema) {
        val value = editedValues[field.key]?.trim().orEmpty()
        if (value.isEmpty()) continue
        if (field.builtin) fields[field.key] = value else customFields[field.key] = value
    }
    return RoutedSupplyFields(fields, customFields)
}

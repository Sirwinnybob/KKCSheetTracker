package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.DEFAULT_SUPPLY_SCHEMA
import com.kkc.sheettracker.data.models.SupplySchemaField
import org.junit.Assert.assertEquals
import org.junit.Test

class SupplyFieldRoutingTest {

    private val schema = listOf(
        SupplySchemaField("builtin-sku", "sku", "SKU", "text", true),
        SupplySchemaField("c1", "diameter", "Diameter", "text", false),
    )

    @Test
    fun routesBuiltinToFieldsAndCustomToCustomFields() {
        val routed = routeSupplyFieldValues(
            schema = schema,
            editedValues = mapOf("sku" to "SB-1", "diameter" to "10in"),
            existingFields = emptyMap(),
            existingCustomFields = emptyMap()
        )
        assertEquals(mapOf("sku" to "SB-1"), routed.fields)
        assertEquals(mapOf("diameter" to "10in"), routed.customFields)
    }

    @Test
    fun omitsBlankValues() {
        val routed = routeSupplyFieldValues(
            schema = schema,
            editedValues = mapOf("sku" to "  ", "diameter" to ""),
            existingFields = emptyMap(),
            existingCustomFields = emptyMap()
        )
        assertEquals(emptyMap<String, String>(), routed.fields)
        assertEquals(emptyMap<String, String>(), routed.customFields)
    }

    @Test
    fun preservesOrphanKeysNotInSchema() {
        val routed = routeSupplyFieldValues(
            schema = schema,
            editedValues = mapOf("sku" to "SB-1"),
            existingFields = mapOf("legacyBuiltin" to "x"),
            existingCustomFields = mapOf("legacyKerf" to "0.1")
        )
        assertEquals(mapOf("legacyBuiltin" to "x", "sku" to "SB-1"), routed.fields)
        assertEquals(mapOf("legacyKerf" to "0.1"), routed.customFields)
    }

    @Test
    fun trimsValues() {
        val routed = routeSupplyFieldValues(
            schema = schema,
            editedValues = mapOf("sku" to "  SB-1  "),
            existingFields = emptyMap(),
            existingCustomFields = emptyMap()
        )
        assertEquals("SB-1", routed.fields["sku"])
    }

    @Test
    fun defaultSchemaHasFourBuiltins() {
        assertEquals(4, DEFAULT_SUPPLY_SCHEMA.size)
        assertEquals(listOf("sku", "quantity", "vendorLink", "trackingNumber"),
            DEFAULT_SUPPLY_SCHEMA.map { it.key })
    }
}

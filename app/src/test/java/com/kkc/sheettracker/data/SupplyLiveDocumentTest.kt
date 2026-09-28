package com.kkc.sheettracker.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplyLiveDocumentTest {

    private fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

    @Test
    fun `parses a full document`() {
        val snapshot = parseSupplyLiveDocument(
            4L,
            json(
                """
                {"categories":[{"id":"c1","name":"Hardware","position":2}],
                 "schema":[{"id":"f1","key":"sku","label":"SKU","type":"text","builtin":true}],
                 "items":[{"id":"i1","categoryId":"c1","name":"Screws","notes":"n",
                           "fields":{"sku":"S1"},"customFields":{"x":"y"},
                           "attachmentIds":[{"id":"a1","originalName":"p.jpg","storedName":"a1.jpg"}],
                           "barcodes":["S1"],"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-02T00:00:00Z",
                           "status":"LOW","statusBy":"Sam","statusAt":"2026-01-03T00:00:00Z"}],
                 "comments":{"i1":[{"id":"k1","author":"Sam","text":"hi","createdAt":"2026-01-01T00:00:00Z"}]}}
                """
            )
        )
        assertNotNull(snapshot)
        snapshot!!
        assertEquals(4L, snapshot.revision)
        assertEquals("Hardware", snapshot.categories.single().name)
        assertEquals(2, snapshot.categories.single().position)
        assertEquals("sku", snapshot.schema.single().key)
        val item = snapshot.items.getValue("i1")
        assertEquals("LOW", item.status)
        assertEquals("Sam", item.statusBy)
        assertEquals("S1", item.fields["sku"])
        assertEquals("a1.jpg", item.attachmentIds.single().storedName)
        assertEquals(listOf("S1"), item.barcodes)
        assertEquals("hi", snapshot.comments.getValue("i1").single().text)
    }

    @Test
    fun `missing and null fields are normalized`() {
        val snapshot = parseSupplyLiveDocument(
            1L,
            json("""{"categories":[],"items":[{"id":"i1","notes":null,"fields":null,"barcodes":null}]}""")
        )!!
        val item = snapshot.items.getValue("i1")
        assertEquals("", item.categoryId)
        assertEquals("", item.name)
        assertNull(item.notes)
        assertTrue(item.fields.isEmpty())
        assertTrue(item.customFields.isEmpty())
        assertTrue(item.attachmentIds.isEmpty())
        assertTrue(item.barcodes.isEmpty())
        assertEquals("IN STOCK", item.status)
        assertEquals("", item.statusBy)
        assertEquals("", item.statusAt)
        assertEquals("", item.updatedAt)
        assertTrue(snapshot.schema.isEmpty())
        assertTrue(snapshot.comments.isEmpty())
    }

    @Test
    fun `entries without ids and non-object entries are skipped`() {
        val snapshot = parseSupplyLiveDocument(
            1L,
            json(
                """{"categories":[{"name":"x"},7,{"id":"c1","name":"ok"}],
                    "schema":[{"id":"f1"},{"id":"f2","key":"k"}],
                    "items":[{"name":"no id"},"text",{"id":"i1"}],
                    "comments":{"i1":[{"text":"no id"},{"id":"k1"}],"i2":"not a list"}}"""
            )
        )!!
        assertEquals(listOf("c1"), snapshot.categories.map { it.id })
        assertEquals(listOf("f2"), snapshot.schema.map { it.id })
        assertEquals(setOf("i1"), snapshot.items.keys)
        assertEquals(listOf("k1"), snapshot.comments.getValue("i1").map { it.id })
        assertEquals(setOf("i1"), snapshot.comments.keys)
    }

    @Test
    fun `comments are re-sorted by parsed instant`() {
        val snapshot = parseSupplyLiveDocument(
            1L,
            json(
                """{"categories":[],"items":[{"id":"i1"}],
                    "comments":{"i1":[{"id":"b","createdAt":"2026-01-01T00:00:00.5Z"},
                                      {"id":"a","createdAt":"2026-01-01T00:00:00Z"}]}}"""
            )
        )!!
        assertEquals(listOf("a", "b"), snapshot.comments.getValue("i1").map { it.id })
    }

    @Test
    fun `invalid document shapes return null`() {
        assertNull(parseSupplyLiveDocument(1L, json("""{"categories":[],"items":{}}""")))
        assertNull(parseSupplyLiveDocument(1L, json("""{"items":[]}""")))
        assertNull(parseSupplyLiveDocument(1L, json("""{"categories":[],"items":[],"comments":[]}""")))
        assertNull(parseSupplyLiveDocument(1L, json("""{"categories":[],"items":[],"schema":{}}""")))
    }
}

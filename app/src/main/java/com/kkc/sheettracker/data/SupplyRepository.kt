package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kkc.sheettracker.data.models.*
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class SupplyRepository(private val serverUrl: String) {

    private val gson = Gson()

    private fun get(path: String): String {
        val conn = URL("$serverUrl/api/supply$path").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        return try {
            conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            throw Exception("GET $path failed: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    private fun post(path: String, body: Any): String {
        val conn = URL("$serverUrl/api/supply$path").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        return try {
            OutputStreamWriter(conn.outputStream).use { it.write(gson.toJson(body)) }
            conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            throw Exception("POST $path failed: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    private fun patch(path: String, body: Any): String {
        val conn = URL("$serverUrl/api/supply$path").openConnection() as HttpURLConnection
        conn.requestMethod = "PATCH"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        return try {
            OutputStreamWriter(conn.outputStream).use { it.write(gson.toJson(body)) }
            conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            throw Exception("PATCH $path failed: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    private fun delete(path: String): String {
        val conn = URL("$serverUrl/api/supply$path").openConnection() as HttpURLConnection
        conn.requestMethod = "DELETE"
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        return try {
            conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            throw Exception("DELETE $path failed: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    fun getCategories(): List<SupplyCategory> =
        gson.fromJson(get("/categories"), object : TypeToken<List<SupplyCategory>>() {}.type)

    fun getItems(): List<SupplyItem> =
        gson.fromJson(get("/items"), object : TypeToken<List<SupplyItem>>() {}.type)

    fun getSchema(): List<SupplySchemaField> =
        gson.fromJson(get("/schema"), object : TypeToken<List<SupplySchemaField>>() {}.type)

    fun patchStatus(itemId: String, status: String): SupplyItem =
        gson.fromJson(patch("/items/$itemId/status", mapOf("status" to status)), SupplyItem::class.java)

    fun getComments(itemId: String): List<SupplyComment> =
        gson.fromJson(get("/items/$itemId/comments"), object : TypeToken<List<SupplyComment>>() {}.type)

    fun addComment(itemId: String, author: String, text: String): SupplyComment =
        gson.fromJson(post("/items/$itemId/comments", mapOf("author" to author, "text" to text)), SupplyComment::class.java)

    fun createItem(categoryId: String, name: String): SupplyItem =
        gson.fromJson(post("/items", mapOf("categoryId" to categoryId, "name" to name, "status" to "IN STOCK")), SupplyItem::class.java)

    // Returns the attachment URL for displaying/downloading in a WebView or image loader
    fun attachmentUrl(itemId: String, attId: String): String =
        "$serverUrl/api/supply/items/$itemId/attachments/$attId"
}

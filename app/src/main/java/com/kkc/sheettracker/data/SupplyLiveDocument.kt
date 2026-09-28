package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.kkc.sheettracker.data.models.SupplyAttachment
import com.kkc.sheettracker.data.models.SupplyCategory
import com.kkc.sheettracker.data.models.SupplyComment
import com.kkc.sheettracker.data.models.SupplyItem
import com.kkc.sheettracker.data.models.SupplySchemaField

/** One complete document from Hours Tracker's `/api/supply/live` socket. */
data class SupplyLiveSnapshot(
    val revision: Long,
    val categories: List<SupplyCategory>,
    val schema: List<SupplySchemaField>,
    val items: Map<String, SupplyItem>,
    val comments: Map<String, List<SupplyComment>>
)

/** Tablet comment order (AUD-10): parsed instant, then raw string. */
internal val SUPPLY_COMMENT_ORDER: Comparator<SupplyComment> =
    compareBy({ SupplyRepository.parseInstantOrMin(it.createdAt) }, { it.createdAt })

// Gson bypasses Kotlin constructors (absent keys become null even for non-null types), so the
// wire format is read into all-nullable DTOs and normalized explicitly below.
private data class LiveCategoryDto(val id: String?, val name: String?, val position: Int?)
private data class LiveSchemaFieldDto(
    val id: String?, val key: String?, val label: String?, val type: String?, val builtin: Boolean?
)
private data class LiveCommentDto(val id: String?, val author: String?, val text: String?, val createdAt: String?)
private data class LiveAttachmentDto(val id: String?, val originalName: String?, val storedName: String?)
private data class LiveItemDto(
    val id: String?,
    val categoryId: String?,
    val name: String?,
    val notes: String?,
    val fields: Map<String, String?>?,
    val customFields: Map<String, String?>?,
    val attachmentIds: List<LiveAttachmentDto?>?,
    val barcodes: List<String?>?,
    val createdAt: String?,
    val updatedAt: String?,
    val status: String?,
    val statusBy: String?,
    val statusAt: String?
)

private val liveGson = Gson()

private fun <T> JsonElement.decodeOrNull(type: Class<T>): T? =
    if (!isJsonObject) null else runCatching { liveGson.fromJson(this, type) }.getOrNull()

/**
 * Parses the `supply` object of a `snapshot`/`supply` frame. Returns null when the document shape
 * is invalid (`items`/`categories` missing or not arrays, `schema`/`comments` of the wrong type),
 * so the caller drops the frame and keeps its prior state. Malformed individual entries are skipped.
 */
internal fun parseSupplyLiveDocument(revision: Long, supply: JsonObject): SupplyLiveSnapshot? {
    val itemsJson = supply.get("items")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
    val categoriesJson = supply.get("categories")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
    val schemaElement = supply.get("schema")?.takeUnless { it.isJsonNull }
    if (schemaElement != null && !schemaElement.isJsonArray) return null
    val commentsElement = supply.get("comments")?.takeUnless { it.isJsonNull }
    if (commentsElement != null && !commentsElement.isJsonObject) return null

    val categories = categoriesJson.mapNotNull { element ->
        val dto = element.decodeOrNull(LiveCategoryDto::class.java) ?: return@mapNotNull null
        val id = dto.id ?: return@mapNotNull null
        SupplyCategory(id = id, name = dto.name ?: "", position = dto.position ?: 0)
    }

    val schema = schemaElement?.asJsonArray?.mapNotNull { element ->
        val dto = element.decodeOrNull(LiveSchemaFieldDto::class.java) ?: return@mapNotNull null
        val id = dto.id ?: return@mapNotNull null
        val key = dto.key ?: return@mapNotNull null
        SupplySchemaField(
            id = id,
            key = key,
            label = dto.label ?: key,
            type = dto.type ?: "text",
            builtin = dto.builtin ?: false
        )
    }.orEmpty()

    val items = LinkedHashMap<String, SupplyItem>()
    for (element in itemsJson) {
        val dto = element.decodeOrNull(LiveItemDto::class.java) ?: continue
        val id = dto.id ?: continue
        items[id] = SupplyItem(
            id = id,
            categoryId = dto.categoryId ?: "",
            name = dto.name ?: "",
            status = dto.status ?: "IN STOCK",
            statusBy = dto.statusBy ?: "",
            statusAt = dto.statusAt ?: "",
            notes = dto.notes,
            fields = dto.fields.orEmpty().mapValues { it.value ?: "" },
            customFields = dto.customFields.orEmpty().mapValues { it.value ?: "" },
            attachmentIds = dto.attachmentIds.orEmpty().mapNotNull { attachment ->
                val attachmentId = attachment?.id ?: return@mapNotNull null
                SupplyAttachment(attachmentId, attachment.originalName ?: "", attachment.storedName ?: "")
            },
            barcodes = dto.barcodes.orEmpty().filterNotNull(),
            createdAt = dto.createdAt ?: "",
            updatedAt = dto.updatedAt ?: ""
        )
    }

    val comments = HashMap<String, List<SupplyComment>>()
    commentsElement?.asJsonObject?.entrySet()?.forEach { (itemId, listElement) ->
        if (!listElement.isJsonArray) return@forEach
        val list = listElement.asJsonArray.mapNotNull { element ->
            val dto = element.decodeOrNull(LiveCommentDto::class.java) ?: return@mapNotNull null
            val commentId = dto.id ?: return@mapNotNull null
            SupplyComment(commentId, dto.author ?: "", dto.text ?: "", dto.createdAt ?: "")
        }.sortedWith(SUPPLY_COMMENT_ORDER)
        if (list.isNotEmpty()) comments[itemId] = list
    }

    return SupplyLiveSnapshot(revision, categories, schema, items, comments)
}

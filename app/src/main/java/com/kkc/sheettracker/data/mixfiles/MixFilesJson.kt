package com.kkc.sheettracker.data.mixfiles

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Typed wire boundary: missing optional fields and unknown keys are allowed; coercion is not. */
internal object MixFilesJson {
    // Builders add null only for the two revision tokens whose explicit null means create.
    private val gson = GsonBuilder().serializeNulls().create()

    fun stringify(body: JsonObject): String = gson.toJson(body)

    fun parseCatalog(content: String): MixFilesCatalog? = parse(content) { root ->
        require(root.boolean("ok"))
        catalog(root)
    }

    fun parseMutationCatalog(content: String): MixFilesCatalog? = parse(content) { root ->
        require(root.boolean("ok"))
        catalog(root["catalog"].objectValue())
    }

    fun parseFolderRevision(content: String): String? = parse(content) { root ->
        require(root.boolean("ok"))
        root.string("folderRevision").also { require(it.isNotBlank()) }
    }

    fun parseFeatures(content: String): Set<String> = parse(content) { root ->
        require(root.boolean("ok"))
        root["features"].arrayValue().map { it.stringValue() }.toSet()
    }.orEmpty()

    fun parseError(content: String, status: Int): MixFilesError? = parse(content) { root ->
        require(!root.boolean("ok"))
        MixFilesError(
            status = status,
            code = root.string("code"),
            message = root.string("error"),
            current = root.optional("current")?.let { row(it.objectValue()) },
            mismatches = root.optional("mismatches")?.arrayValue()?.map { element ->
                val mismatch = element.objectValue()
                FrzMismatch(mismatch.string("pgm"), mismatch.integer("index"), mismatch.boolean("tabletLocked"), mismatch.integer("frzRemaining"))
            }.orEmpty(),
            edit = root.optionalString("edit"),
            pgm = root.optionalString("pgm"),
            reason = root.optionalString("reason"),
            frzRestored = root.optional("frzRestored")?.booleanValue(),
        )
    }

    fun entry(spec: EntrySpec): JsonObject = JsonObject().apply {
        when (spec) {
            is EntrySpec.NewPgm -> addProperty("pgm", spec.pgm)
            is EntrySpec.Existing -> {
                addProperty("path", spec.path)
                add("parameters", stringMap(spec.parameters))
                spec.fields?.let { addProperty("fields", it) }
            }
            is EntrySpec.Pending -> {
                addProperty("pgm", spec.pgm)
                spec.quantity?.let { addProperty("quantity", it) }
                spec.remaining?.let { addProperty("remaining", it) }
            }
        }
    }

    fun entries(specs: List<EntrySpec>): JsonArray = JsonArray().apply { specs.forEach { add(entry(it)) } }

    fun completion(value: Completion): JsonObject = JsonObject().apply {
        add("completedPgms", JsonArray().apply { value.completedPgms.forEach { add(it) } })
        add("resolutions", stringMap(value.resolutions))
        value.companionExpectedRevision?.let { addProperty("companionExpectedRevision", it) }
    }

    fun newMixes(values: List<NewMix>): JsonArray = JsonArray().apply {
        values.forEach { mix -> add(JsonObject().apply {
            addProperty("file", mix.file)
            add("entries", entries(mix.entries))
        }) }
    }

    fun JsonObject.revision(name: String, value: String?) {
        add(name, value?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
    }

    private fun stringMap(values: Map<String, String>): JsonObject = JsonObject().apply {
        values.forEach { (key, value) -> addProperty(key, value) }
    }

    private fun catalog(root: JsonObject): MixFilesCatalog = MixFilesCatalog(
        job = root.string("job").also { require(it.isNotBlank()) },
        material = root.string("material").also { require(it.isNotBlank()) },
        folderRevision = root.string("folderRevision").also { require(it.isNotBlank()) },
        files = root["files"].arrayValue().map { row(it.objectValue()) },
    )

    private fun row(value: JsonObject): MixFileRow = MixFileRow(
        file = value.string("file"),
        kind = when (value.string("kind")) {
            "mix" -> MixFileKind.MIX
            "frz" -> MixFileKind.FRZ
            "revision" -> MixFileKind.REVISION
            else -> error("Unknown mix file kind")
        },
        revisionOf = value.optionalString("revisionOf"),
        revisionNumber = value.optional("revisionNumber")?.integerValue(),
        revision = value.string("revision"),
        companion = value.optionalString("companion"),
        companionMismatch = value.boolean("companionMismatch"),
        error = value.optionalString("error"),
        modifiedAt = value.optionalString("modifiedAt"),
        entries = value["entries"].arrayValue().map { entry ->
            val item = entry.objectValue()
            MixFileEntry(
                path = item.string("path"),
                pgm = item.string("pgm"),
                parameters = linkedMapOf<String, String>().apply {
                    item["parameters"].objectValue().entrySet().forEach { (key, value) -> put(key, value.stringValue()) }
                },
                fields = item.optionalString("fields"),
                quantity = item.integer("quantity"),
                remaining = item.optional("remaining")?.integerValue(),
                pgmExists = item.boolean("pgmExists"),
                headerDrift = item.boolean("headerDrift"),
            )
        },
    )

    private fun <T> parse(content: String, decode: (JsonObject) -> T): T? =
        runCatching { decode(JsonParser.parseString(content).objectValue()) }.getOrNull()

    private fun JsonElement?.objectValue(): JsonObject {
        require(this != null && isJsonObject)
        return asJsonObject
    }
    private fun JsonElement?.arrayValue(): JsonArray {
        require(this != null && isJsonArray)
        return asJsonArray
    }
    private fun JsonElement?.stringValue(): String {
        require(this != null && isJsonPrimitive && asJsonPrimitive.isString)
        return asString
    }
    private fun JsonElement?.booleanValue(): Boolean {
        require(this != null && isJsonPrimitive && asJsonPrimitive.isBoolean)
        return asBoolean
    }
    private fun JsonElement?.integerValue(): Int {
        require(this != null && isJsonPrimitive && asJsonPrimitive.isNumber)
        return asBigDecimal.intValueExact()
    }
    private fun JsonObject.optional(name: String): JsonElement? = get(name)?.takeUnless { it.isJsonNull }
    private fun JsonObject.string(name: String): String = get(name).stringValue()
    private fun JsonObject.optionalString(name: String): String? = optional(name)?.stringValue()
    private fun JsonObject.boolean(name: String): Boolean = get(name).booleanValue()
    private fun JsonObject.integer(name: String): Int = get(name).integerValue()
}

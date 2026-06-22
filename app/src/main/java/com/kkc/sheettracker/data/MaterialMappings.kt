package com.kkc.sheettracker.data

import com.google.gson.JsonParser
import java.io.File
import java.util.Locale

class MaterialMappings private constructor(private val realToSanitized: Map<String, String>) {

    fun canonical(name: String?): String {
        val normalized = normalize(name)
        if (normalized.isEmpty()) return ""

        val sanitized = realToSanitized.entries.firstOrNull { (realName, _) ->
            normalize(realName) == normalized
        }?.value

        return normalize(sanitized ?: name)
    }

    companion object {
        fun load(baseDir: File): MaterialMappings {
            val mappingsFile = baseDir.resolve(".metadata").resolve("material_mappings.json")
            if (!mappingsFile.isFile) return MaterialMappings(emptyMap())

            val mappings = runCatching {
                mappingsFile.reader().use { reader ->
                    val json = JsonParser.parseReader(reader)
                    if (!json.isJsonObject) return@runCatching emptyMap<String, String>()

                    json.asJsonObject.entrySet()
                        .filter { (_, value) -> value.isJsonPrimitive && value.asJsonPrimitive.isString }
                        .associate { (key, value) -> key to value.asString }
                }
            }.getOrElse { emptyMap() }

            return MaterialMappings(mappings)
        }

        fun of(realToSanitized: Map<String, String>): MaterialMappings =
            MaterialMappings(realToSanitized)

        private fun normalize(name: String?): String =
            name?.trim()?.lowercase(Locale.US).orEmpty()
    }
}

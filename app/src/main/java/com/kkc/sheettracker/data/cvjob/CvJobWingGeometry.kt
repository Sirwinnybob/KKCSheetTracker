package com.kkc.sheettracker.data.cvjob

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class CvWingVector(val x: Double, val y: Double, val z: Double)
data class CvWingSize(val width: Double, val height: Double, val depth: Double)
data class CvWingNodeMaterial(val materialId: String?, val grainAxis: String?)
data class CvWingNode(
    val path: String,
    val parent: String?,
    val sourcePid: String?,
    val code: String,
    val position: CvWingVector,
    val size: CvWingSize,
    val rotationDegrees: CvWingVector,
    val doorId: String?,
    val material: CvWingNodeMaterial?
)

data class CvWingGeometry(
    val cabinet: String,
    val room: String,
    val handedness: String,
    val construction: String,
    val nodes: List<CvWingNode>
)

sealed interface CvJobWings {
    data class Loaded(val wings: List<CvWingGeometry>) : CvJobWings
    data object Missing : CvJobWings
    data class Unreadable(val reason: String) : CvJobWings
}

private val WING_CONTAINER_CODES = setOf("CAB", "Case", "Face2", "Face4", "DOR_OPEN")
private val WING_HIDDEN_SUPPORT_CODES = setOf("TS", "DE")

/** Parses the optional measured-wing export without guessing missing geometry or metadata. */
fun parseCvJobWings(json: String): CvJobWings = try {
    val root = JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
        ?: return CvJobWings.Unreadable("root is not an object")
    val entries = root.get("wingGeometry") ?: return CvJobWings.Missing
    if (!entries.isJsonArray || entries.asJsonArray.isEmpty) return CvJobWings.Missing
    val wings = entries.asJsonArray.mapIndexed { index, element -> parseWing(element, index) }
    CvJobWings.Loaded(wings)
} catch (error: Exception) {
    CvJobWings.Unreadable(error.message ?: error.javaClass.simpleName)
}

/** Only manufactured solids participate in any wing presentation; source nodes remain intact. */
fun CvWingGeometry.visibleNodes(): List<CvWingNode> = nodes.filter { node ->
    node.code !in WING_CONTAINER_CODES && node.code !in WING_HIDDEN_SUPPORT_CODES
}

fun CvWingGeometry.nodeForDoor(doorId: String): CvWingNode? =
    nodes.singleOrNull { it.doorId == doorId }

private fun parseWing(element: JsonElement, index: Int): CvWingGeometry {
    val wing = element.requireObject("wingGeometry[$index]")
    val cabinet = wing.requiredText("cabinet")
    val nodes = wing.requiredArray("nodes").mapIndexed { nodeIndex, node -> parseNode(node, cabinet, nodeIndex) }
    require(nodes.isNotEmpty()) { "wing $cabinet has no nodes" }
    validateNodeTree(cabinet, nodes)
    return CvWingGeometry(
        cabinet = cabinet,
        room = wing.optionalText("room").orEmpty(),
        handedness = wing.optionalText("handedness").orEmpty(),
        construction = wing.optionalText("construction").orEmpty(),
        nodes = nodes
    )
}

private fun parseNode(element: JsonElement, cabinet: String, index: Int): CvWingNode {
    val node = element.requireObject("wing $cabinet node[$index]")
    val materialId = node.optionalText("materialId")
    val grainAxis = node.optionalText("grainAxis")
    if (grainAxis != null && grainAxis != "y") error("wing $cabinet node[$index] has unsupported grain axis")
    return CvWingNode(
        path = node.requiredText("path"),
        parent = node.optionalText("parent")?.takeIf { it.isNotBlank() },
        sourcePid = node.optionalText("sourcePid"),
        code = node.requiredText("code"),
        position = node.vector("position"),
        size = node.size("size"),
        rotationDegrees = node.vector("rotationDegrees"),
        doorId = node.optionalText("doorId"),
        material = if (materialId != null || grainAxis != null) CvWingNodeMaterial(materialId, grainAxis) else null
    )
}

private fun validateNodeTree(cabinet: String, nodes: List<CvWingNode>) {
    val seen = linkedSetOf<String>()
    nodes.forEach { node ->
        require(seen.add(node.path)) { "wing $cabinet repeats path ${node.path}" }
        require(node.parent == null || node.parent in seen) { "wing $cabinet parent precedes child ${node.path}" }
        require(node.size.width > 0 && node.size.height > 0 && node.size.depth > 0) { "wing $cabinet has non-positive size" }
    }
}

private fun JsonElement.requireObject(label: String): JsonObject {
    require(isJsonObject) { "$label is not an object" }
    return asJsonObject
}

private fun JsonObject.requiredArray(key: String): List<JsonElement> =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList() ?: error("missing array $key")

private fun JsonObject.requiredText(key: String): String =
    optionalText(key)?.takeIf { it.isNotBlank() } ?: error("missing $key")

private fun JsonObject.optionalText(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.vector(key: String): CvWingVector {
    val vector = get(key).requireObject(key)
    return CvWingVector(vector.number("x"), vector.number("y"), vector.number("z"))
}

private fun JsonObject.size(key: String): CvWingSize {
    val size = get(key).requireObject(key)
    return CvWingSize(size.number("width"), size.number("height"), size.number("depth"))
}

private fun JsonObject.number(key: String): Double = optionalText(key)?.toDoubleOrNull()
    ?.takeIf { it.isFinite() } ?: error("invalid $key")

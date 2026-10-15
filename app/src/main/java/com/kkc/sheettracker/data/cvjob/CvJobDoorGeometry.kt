package com.kkc.sheettracker.data.cvjob

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/*
 * Read-only view of `<job>/.metadata/cv_job/job_data.json` (CV Cutlist Query, contract
 * `docs/job-data-contract-v2.md` in that repo; v1 remains supported), limited to what door previews draw:
 * `doorGeometry[]` joined with `doors[]` for label and style.
 *
 * CV Cutlist Query is the single writer of `.metadata/cv_job/`. The tablet only reads that one
 * file by its exact name (never `*.tmp` or `.sync-conflict-*` copies) and never writes there.
 */

const val CV_JOB_DATA_RELATIVE_PATH = ".metadata/cv_job/job_data.json"
const val DOOR_ASSEMBLY_PROGRESS_DOC_TYPE = "DOOR_ASSEMBLY"

enum class DoorConstruction { FIVE_PIECE, SLAB, OTHER }

enum class DoorHingeSide { LEFT, RIGHT, TOP, NONE, OTHER }

/** All sizes are decimal inches, parsed from the contract's decimal-inch strings. */
data class DoorFrame(
    val leftStile: Double,
    val rightStile: Double,
    val topRail: Double,
    val bottomRail: Double,
    val tenon: Double?,
    val material: String
)

data class DoorRect(val x: Double, val y: Double, val width: Double, val height: Double)

data class DoorPanel(
    /** `flat`, `raised`, `glass` or `panel` as published. */
    val kind: String,
    val insetX: Double?,
    val insetY: Double?,
    /** Visible opening between the members, door coordinates (bottom-left origin, y up). */
    val opening: DoorRect?,
    val material: String
) {
    val isGlass: Boolean get() = kind.equals("glass", ignoreCase = true)
}

data class DoorHinges(
    val side: DoorHingeSide,
    val code: String,
    val count: Int,
    /** Distance of each hinge along its edge: from the bottom (left/right), from the left (top). */
    val positions: List<Double>,
    /** Cup centre distance from the hinge edge; null without hinge UCS rev 5 notes. */
    val edgeOffset: Double?,
    /** Cup centres in door coordinates (rev 5 notes); empty when unknown. */
    val points: List<DoorPoint>
)

data class DoorPoint(val x: Double, val y: Double)

data class DoorGeometry(
    val doorId: String,
    val construction: DoorConstruction,
    val width: Double,
    val height: Double,
    val thickness: Double?,
    val frame: DoorFrame?,
    val panel: DoorPanel?,
    val hinges: DoorHinges,
    val checks: List<String>,
    /** Component grain takes priority over `doors[].grain`: true = horizontal, false = vertical. */
    val horizontalGrain: Boolean = false,
    /** Visibility is independent of direction; vertical grain is still drawn. */
    val showFaceGrain: Boolean = true
)

/**
 * One part of a door, from `doorGeometry[].components` joined with `parts[]`. Sizes are the
 * published decimal-inch strings (CV's cut sizes), shown as published like the cut lists.
 */
data class DoorPart(
    val partId: String,
    /** `leftStile`, `rightStile`, `topRail`, `bottomRail`, `panel`, `slab`, `hinge`, ... as published. */
    val role: String,
    val description: String,
    val material: String,
    val width: String,
    val length: String,
    /** Published piece count after operator edits; older files default to one per row. */
    val quantity: Int = 1,
    /** Published component direction; null when older data has no component grain. */
    val horizontalGrain: Boolean? = null
)

/** One door ready to show: its geometry plus the `doors[]` label, style and cabinet, and its parts. */
data class DoorPreviewData(
    val geometry: DoorGeometry,
    val label: String,
    val style: String,
    val cabinet: String = "",
    val room: String = "",
    val parts: List<DoorPart> = emptyList()
) {
    val doorId: String get() = geometry.doorId
    val partCount: Int get() = parts.sumOf { it.quantity }
}

sealed interface CvJobDoorPreviews {
    /** No `job_data.json` in the job folder (job not published from CV Cutlist Query). */
    data object NoJobData : CvJobDoorPreviews

    /** The file exists but predates `doorGeometry` (older CV Cutlist Query). */
    data object NoDoorGeometry : CvJobDoorPreviews

    /** The file could not be read or is not a JSON object. */
    data class Unreadable(val reason: String) : CvJobDoorPreviews

    data class Loaded(val doors: List<DoorPreviewData>) : CvJobDoorPreviews {
        private val byId = doors.associateBy { it.doorId }
        fun forDoor(doorId: String): DoorPreviewData? = byId[doorId]
    }
}

fun cvJobDataFile(basePath: String, jobFolderName: String): File =
    File(File(basePath, jobFolderName), CV_JOB_DATA_RELATIVE_PATH)

/** Reads and parses the job's `job_data.json`; never throws and never writes. */
fun loadCvJobDoorPreviews(basePath: String, jobFolderName: String): CvJobDoorPreviews {
    if (basePath.isBlank() || jobFolderName.isBlank()) return CvJobDoorPreviews.NoJobData
    val file = cvJobDataFile(basePath, jobFolderName)
    if (!file.isFile) return CvJobDoorPreviews.NoJobData
    val text = try {
        file.readText()
    } catch (e: Exception) {
        return CvJobDoorPreviews.Unreadable(e.message ?: e.javaClass.simpleName)
    }
    return parseCvJobDoorPreviews(text)
}

fun parseCvJobDoorPreviews(json: String): CvJobDoorPreviews {
    val root = try {
        JsonParser.parseString(json)
    } catch (e: Exception) {
        return CvJobDoorPreviews.Unreadable(e.message ?: "invalid JSON")
    }
    if (root == null || !root.isJsonObject) return CvJobDoorPreviews.Unreadable("not a JSON object")
    val obj = root.asJsonObject
    val geometry = obj.arrayOrNull("doorGeometry") ?: return CvJobDoorPreviews.NoDoorGeometry
    val doorsById = obj.arrayOrNull("doors").objects()
        .mapNotNull { door -> door.text("id")?.let { it to door } }
        .toMap()
    val partsById = obj.arrayOrNull("parts").objects()
        .mapNotNull { part -> part.text("id")?.let { it to part } }
        .toMap()
    val doors = geometry.objects().mapNotNull { entry ->
        val parsed = parseDoorGeometry(entry) ?: return@mapNotNull null
        val door = doorsById[parsed.doorId]
        if (door?.text("hidden") == "true") return@mapNotNull null
        // CROSS-PROGRAM: CV Cutlist Query owns this read-only source. Its grain
        // booleans mean direction, never visibility; false means vertical.
        val faceRole = if (parsed.construction == DoorConstruction.SLAB) "slab" else "panel"
        val componentGrain = entry.arrayOrNull("components").objects()
            .filter { it.text("role") == faceRole }
            .firstNotNullOfOrNull { component ->
                component.booleanOrNull("grain")
                    ?: partsById[component.text("part")]?.booleanOrNull("grain")
            }
        val panelGrain = if (parsed.construction == DoorConstruction.SLAB) null
            else entry.objectOrNull("panel")?.booleanOrNull("grain")
        DoorPreviewData(
            geometry = parsed.copy(
                horizontalGrain = panelGrain ?: componentGrain ?: door?.booleanOrNull("grain") ?: false
            ),
            label = door?.text("label").orEmpty(),
            style = door?.text("style").orEmpty(),
            cabinet = door?.text("cabinet").orEmpty(),
            room = door?.text("room").orEmpty(),
            parts = entry.arrayOrNull("components").objects().mapNotNull { component ->
                val partId = component.text("part")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val part = partsById[partId]
                if (part?.text("hidden") == "true") return@mapNotNull null
                DoorPart(
                    partId = partId,
                    role = component.text("role").orEmpty(),
                    description = component.text("description") ?: part?.text("description").orEmpty(),
                    material = part?.text("material").orEmpty(),
                    width = part?.text("width").orEmpty(),
                    length = part?.text("length").orEmpty(),
                    quantity = part?.text("quantity")?.toIntOrNull() ?: 1,
                    horizontalGrain = component.booleanOrNull("grain") ?: part?.booleanOrNull("grain")
                )
            }
        )
    }
    return CvJobDoorPreviews.Loaded(doors)
}

/**
 * Null when the entry has no door id or no usable finished size: nothing can be drawn.
 * `pulls` are deliberately not read: CV's pull points are not where the shop puts pulls.
 */
internal fun parseDoorGeometry(entry: JsonObject): DoorGeometry? {
    val doorId = entry.text("doorId")?.takeIf { it.isNotBlank() } ?: return null
    val width = entry.inches("width")?.takeIf { it > 0.0 } ?: return null
    val height = entry.inches("height")?.takeIf { it > 0.0 } ?: return null
    val construction = when (entry.text("construction")) {
        "fivePiece" -> DoorConstruction.FIVE_PIECE
        "slab" -> DoorConstruction.SLAB
        else -> DoorConstruction.OTHER
    }
    return DoorGeometry(
        doorId = doorId,
        construction = construction,
        width = width,
        height = height,
        thickness = entry.inches("thickness"),
        frame = entry.objectOrNull("frame")?.let(::parseFrame),
        panel = entry.objectOrNull("panel")?.let(::parsePanel),
        hinges = parseHinges(entry.objectOrNull("hinges")),
        checks = entry.arrayOrNull("checks").strings()
    )
}

private fun parseFrame(frame: JsonObject): DoorFrame? {
    // A frame missing any member width cannot be placed; the door then draws as an outline.
    return DoorFrame(
        leftStile = frame.inches("leftStile") ?: return null,
        rightStile = frame.inches("rightStile") ?: return null,
        topRail = frame.inches("topRail") ?: return null,
        bottomRail = frame.inches("bottomRail") ?: return null,
        tenon = frame.inches("tenon"),
        material = frame.text("material").orEmpty()
    )
}

private fun parsePanel(panel: JsonObject): DoorPanel {
    val opening = panel.objectOrNull("opening")?.let { o ->
        val x = o.inches("x")
        val y = o.inches("y")
        val w = o.inches("width")
        val h = o.inches("height")
        if (x != null && y != null && w != null && h != null && w > 0.0 && h > 0.0) DoorRect(x, y, w, h) else null
    }
    return DoorPanel(
        kind = panel.text("kind").orEmpty(),
        insetX = panel.inches("insetX"),
        insetY = panel.inches("insetY"),
        opening = opening,
        material = panel.text("material").orEmpty()
    )
}

private fun parseHinges(hinges: JsonObject?): DoorHinges {
    if (hinges == null) return DoorHinges(DoorHingeSide.NONE, "", 0, emptyList(), null, emptyList())
    val side = when (hinges.text("side")) {
        "left" -> DoorHingeSide.LEFT
        "right" -> DoorHingeSide.RIGHT
        "top" -> DoorHingeSide.TOP
        "none", null -> DoorHingeSide.NONE
        else -> DoorHingeSide.OTHER
    }
    return DoorHinges(
        side = side,
        code = hinges.text("code").orEmpty(),
        count = hinges.get("count")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() } ?: 0,
        positions = hinges.arrayOrNull("positions")?.mapNotNull { it.decimalOrNull() }?.sorted().orEmpty(),
        edgeOffset = hinges.inches("edgeOffset"),
        points = hinges.arrayOrNull("points").objects().mapNotNull { point ->
            DoorPoint(point.inches("x") ?: return@mapNotNull null, point.inches("y") ?: return@mapNotNull null)
        }
    )
}

private fun JsonObject.objectOrNull(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.arrayOrNull(key: String): JsonArray? =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray

private fun JsonArray?.objects(): List<JsonObject> =
    this?.filter { it.isJsonObject }?.map { it.asJsonObject }.orEmpty()

private fun JsonArray?.strings(): List<String> =
    this?.mapNotNull { el -> el.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() } }.orEmpty()

private fun JsonObject.text(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString

private fun JsonObject.booleanOrNull(key: String): Boolean? =
    text(key)?.toBooleanStrictOrNull()

/** Decimal-inch string (numbers are accepted too); null for missing, null or non-numeric. */
private fun JsonObject.inches(key: String): Double? = get(key)?.decimalOrNull()

private fun JsonElement.decimalOrNull(): Double? {
    if (!isJsonPrimitive) return null
    return asString.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
}

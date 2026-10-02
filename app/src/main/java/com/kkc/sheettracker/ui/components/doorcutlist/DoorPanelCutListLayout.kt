package com.kkc.sheettracker.ui.components.doorcutlist

import kotlin.math.max

/**
 * Pure layout for the Door Panel Cut List PDF: decides every block's page and position so the
 * Canvas painter only draws. Units are PDF points (72 per inch). No android.* imports.
 */

interface TextMeasurer {
    fun width(text: String, sizePt: Float, bold: Boolean): Float
}

enum class CellAlign { LEFT, CENTER, RIGHT }

enum class ColumnKey { QTY, DESCRIPTION, WIDTH, STAR, LENGTH, CABINET, ROOM }

data class CutListColumn(val key: ColumnKey, val title: String, val widthPt: Float, val align: CellAlign)

object CutListGeometry {
    const val PAGE_WIDTH = 612f
    const val PAGE_HEIGHT = 792f
    const val MARGIN_X = 72f
    const val MARGIN_TOP = 40f
    const val MARGIN_BOTTOM = 40f
    const val TABLE_WIDTH = 467f
    const val FOOTER_HEIGHT = 16f
    const val CONTENT_BOTTOM = PAGE_HEIGHT - MARGIN_BOTTOM - FOOTER_HEIGHT

    const val TITLE_SIZE = 26f
    const val TITLE_HEIGHT = 34f
    const val BAND_SIZE = 8.5f
    const val BAND_LINE_HEIGHT = 12f
    const val BAND_PADDING = 3f
    const val SECTION_GAP = 6f

    const val LEGEND_CHIP_WIDTH = 92f
    const val LEGEND_CHIP_HEIGHT = 18f
    const val LEGEND_GAP = 2f

    const val MATERIAL_SIZE = 12f
    const val MATERIAL_HEIGHT = 27f
    const val HEADER_ROW_HEIGHT = 17f

    const val CELL_SIZE = 8.5f
    const val ROOM_CELL_SIZE = 7.5f
    const val ROW_MIN_HEIGHT = 22f
    const val ROW_LINE_HEIGHT = 12f
    const val ROW_VPAD = 5f
    const val CELL_HPAD = 5f

    const val FOOTER_SIZE = 7.5f
}

object CutListColors {
    val NAVY: Int = 0xFF16375E.toInt()
    val TEXT: Int = 0xFF1F2D3D.toInt()
    val FOOTER: Int = 0xFF6B7785.toInt()
    val BAND: Int = 0xFFF3F3F3.toInt()
    val ROW_SHADE: Int = 0xFFEBEFF6.toInt()
    val WHITE: Int = 0xFFFFFFFF.toInt()
    /** CV's Door Cut List width shading, cycled per run of equal widths. */
    val WIDTH_BANDS: List<Int> =
        listOf(0xFFEADBC8, 0xFFDDEAFB, 0xFFFCE4E4, 0xFFF1ECFB, 0xFFE4F7E4).map { it.toInt() }
}

fun cutListColumns(roomTags: Boolean): List<CutListColumn> = if (!roomTags) {
    listOf(
        CutListColumn(ColumnKey.QTY, "Qty", 34f, CellAlign.CENTER),
        CutListColumn(ColumnKey.DESCRIPTION, "Description", 140f, CellAlign.LEFT),
        CutListColumn(ColumnKey.WIDTH, "Width", 78f, CellAlign.RIGHT),
        CutListColumn(ColumnKey.STAR, "*", 16f, CellAlign.CENTER),
        CutListColumn(ColumnKey.LENGTH, "Length", 92f, CellAlign.LEFT),
        CutListColumn(ColumnKey.CABINET, "Cabinet (Qty)", 107f, CellAlign.RIGHT),
    )
} else {
    listOf(
        CutListColumn(ColumnKey.QTY, "Qty", 28f, CellAlign.CENTER),
        CutListColumn(ColumnKey.DESCRIPTION, "Description", 116f, CellAlign.LEFT),
        CutListColumn(ColumnKey.WIDTH, "Width", 60f, CellAlign.RIGHT),
        CutListColumn(ColumnKey.STAR, "*", 12f, CellAlign.CENTER),
        CutListColumn(ColumnKey.LENGTH, "Length", 62f, CellAlign.LEFT),
        CutListColumn(ColumnKey.CABINET, "Cabinet (Qty)", 97f, CellAlign.RIGHT),
        CutListColumn(ColumnKey.ROOM, "Room", 92f, CellAlign.CENTER),
    )
}

/** Greedy word wrap on spaces. A single word wider than [maxWidth] gets its own line. */
fun wrapText(text: String, maxWidth: Float, sizePt: Float, bold: Boolean, measurer: TextMeasurer): List<String> {
    val words = text.split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return listOf("")
    val lines = mutableListOf<String>()
    var current = ""
    for (word in words) {
        val candidate = if (current.isEmpty()) word else "$current $word"
        if (current.isEmpty() || measurer.width(candidate, sizePt, bold) <= maxWidth) {
            current = candidate
        } else {
            lines += current
            current = word
        }
    }
    lines += current
    return lines
}

/**
 * Single-line fit: [text] unchanged if it fits [maxWidth], otherwise the longest prefix (trailing
 * spaces trimmed) that fits together with a trailing "…". Returns just "…" if even that is too wide.
 */
fun ellipsize(text: String, maxWidth: Float, sizePt: Float, bold: Boolean, measurer: TextMeasurer): String {
    if (text.isEmpty() || measurer.width(text, sizePt, bold) <= maxWidth) return text
    for (length in text.length - 1 downTo 1) {
        val candidate = text.substring(0, length).trimEnd() + "…"
        if (measurer.width(candidate, sizePt, bold) <= maxWidth) return candidate
    }
    return "…"
}

fun headerBandText(model: DoorPanelCutListModel): String = buildString {
    append(model.jobTitle).append(" · ").append(model.dateText).append(" · ")
    append(if (model.roomTags) "Room Tags" else "Standard")
    if (model.filteredRoomNames.isNotEmpty()) {
        append(" · Rooms: ").append(model.filteredRoomNames.joinToString(", "))
    }
}

sealed interface CutListBlock {
    val top: Float
    val height: Float
}

data class TitleBlock(
    override val top: Float,
    override val height: Float,
    val title: String,
    val bandLines: List<String>,
) : CutListBlock

data class LegendChip(val label: String, val color: Int, val left: Float, val top: Float, val width: Float, val height: Float)

data class LegendBlock(override val top: Float, override val height: Float, val chips: List<LegendChip>) : CutListBlock

data class MaterialHeaderBlock(override val top: Float, override val height: Float, val text: String) : CutListBlock

data class TableHeaderBlock(
    override val top: Float,
    override val height: Float,
    val columns: List<CutListColumn>,
    val continued: Boolean,
) : CutListBlock

data class RowBlock(
    override val top: Float,
    override val height: Float,
    val columns: List<CutListColumn>,
    /** Wrapped lines per column. */
    val cells: Map<ColumnKey, List<String>>,
    val shaded: Boolean,
    /** Standard mode only. */
    val widthBandColor: Int?,
    /** Room Tags mode only. */
    val roomColor: Int?,
) : CutListBlock

data class FooterBlock(override val top: Float, override val height: Float, val text: String) : CutListBlock

data class CutListPage(val pageNumber: Int, val blocks: List<CutListBlock>)

private data class PreparedRow(
    val cells: Map<ColumnKey, List<String>>,
    val height: Float,
    val widthBandColor: Int?,
    val roomColor: Int?,
)

fun layoutDoorPanelCutList(model: DoorPanelCutListModel, measurer: TextMeasurer): List<CutListPage> {
    val g = CutListGeometry
    val columns = cutListColumns(model.roomTags)
    val roomsByKey = model.rooms.associateBy { it.key }
    val pages = mutableListOf(mutableListOf<CutListBlock>())
    var y = g.MARGIN_TOP

    fun newPage() {
        pages += mutableListOf<CutListBlock>()
        y = g.MARGIN_TOP
    }
    fun add(block: CutListBlock) {
        pages.last() += block
        y = block.top + block.height
    }

    val bandLines = wrapText(headerBandText(model), g.TABLE_WIDTH - 2 * g.BAND_PADDING, g.BAND_SIZE, false, measurer)
    add(
        TitleBlock(
            top = y,
            height = g.TITLE_HEIGHT + bandLines.size * g.BAND_LINE_HEIGHT + 2 * g.BAND_PADDING + g.SECTION_GAP,
            title = "Door Cut List",
            bandLines = bandLines,
        )
    )

    if (model.roomTags && model.rooms.isNotEmpty()) {
        val perRow = max(1, (g.TABLE_WIDTH / g.LEGEND_CHIP_WIDTH).toInt())
        val legendTop = y
        val chips = model.rooms.mapIndexed { i, room ->
            LegendChip(
                label = ellipsize(
                    room.displayName,
                    g.LEGEND_CHIP_WIDTH - g.LEGEND_GAP - 2 * g.CELL_HPAD,
                    g.ROOM_CELL_SIZE,
                    bold = true,
                    measurer = measurer,
                ),
                color = room.color,
                left = g.MARGIN_X + (i % perRow) * g.LEGEND_CHIP_WIDTH,
                top = legendTop + (i / perRow) * (g.LEGEND_CHIP_HEIGHT + g.LEGEND_GAP),
                width = g.LEGEND_CHIP_WIDTH - g.LEGEND_GAP,
                height = g.LEGEND_CHIP_HEIGHT,
            )
        }
        val chipRows = (model.rooms.size + perRow - 1) / perRow
        add(LegendBlock(legendTop, chipRows * (g.LEGEND_CHIP_HEIGHT + g.LEGEND_GAP) + g.SECTION_GAP, chips))
    }

    var bandIndex = -1
    model.materials.forEach { section ->
        val prepared = section.rows.mapIndexed { i, row ->
            if (i == 0 || row.width != section.rows[i - 1].width) bandIndex++
            prepareRow(
                row = row,
                columns = columns,
                measurer = measurer,
                widthBandColor = if (model.roomTags) null else CutListColors.WIDTH_BANDS[bandIndex % CutListColors.WIDTH_BANDS.size],
                room = row.roomKey?.let { key -> roomsByKey[key] ?: CutListRoom(key, roomDisplayName(key), UNASSIGNED_ROOM_COLOR) },
            )
        }
        // Never orphan a material header: it needs its column header and up to 2 rows below it.
        val needed = g.MATERIAL_HEIGHT + g.HEADER_ROW_HEIGHT + prepared.take(2).sumOf { it.height.toDouble() }.toFloat()
        if (y > g.MARGIN_TOP && y + needed > g.CONTENT_BOTTOM) newPage()
        add(MaterialHeaderBlock(y, g.MATERIAL_HEIGHT, "Material: '${section.material}'  |  Units: ${section.unitsLabel}"))
        add(TableHeaderBlock(y, g.HEADER_ROW_HEIGHT, columns, continued = false))
        prepared.forEachIndexed { i, row ->
            if (y + row.height > g.CONTENT_BOTTOM) {
                newPage()
                add(TableHeaderBlock(y, g.HEADER_ROW_HEIGHT, columns, continued = true))
            }
            add(RowBlock(y, row.height, columns, row.cells, shaded = i % 2 == 0, row.widthBandColor, row.roomColor))
        }
    }

    val jobNumber = cutListJobNumber(model.jobTitle)
    val footerTop = g.PAGE_HEIGHT - g.MARGIN_BOTTOM - g.FOOTER_HEIGHT + 4f
    return pages.mapIndexed { i, blocks ->
        CutListPage(
            pageNumber = i + 1,
            blocks = blocks + FooterBlock(footerTop, g.FOOTER_HEIGHT - 4f, "$jobNumber · Door Cut List · Page ${i + 1} of ${pages.size}"),
        )
    }
}

private fun prepareRow(
    row: CutListRow,
    columns: List<CutListColumn>,
    measurer: TextMeasurer,
    widthBandColor: Int?,
    room: CutListRoom?,
): PreparedRow {
    val g = CutListGeometry
    val cells = columns.associate { column ->
        val text = when (column.key) {
            ColumnKey.QTY -> row.qty.toString()
            ColumnKey.DESCRIPTION -> row.description
            ColumnKey.WIDTH -> row.width
            ColumnKey.STAR -> "*"
            ColumnKey.LENGTH -> row.length
            ColumnKey.CABINET -> row.cabinetText
            ColumnKey.ROOM -> room?.displayName.orEmpty()
        }
        val bold = column.key == ColumnKey.ROOM
        val size = if (bold) g.ROOM_CELL_SIZE else g.CELL_SIZE
        val lines = when (column.key) {
            ColumnKey.DESCRIPTION, ColumnKey.CABINET ->
                wrapText(text, column.widthPt - 2 * g.CELL_HPAD, size, bold, measurer)
            // Room names never wrap: one line, cut off with an ellipsis.
            ColumnKey.ROOM ->
                listOf(ellipsize(text, column.widthPt - 2 * g.CELL_HPAD, g.ROOM_CELL_SIZE, bold = true, measurer = measurer))
            else -> listOf(text)
        }
        column.key to lines
    }
    val maxLines = cells.values.maxOf { it.size }
    return PreparedRow(
        cells = cells,
        height = max(g.ROW_MIN_HEIGHT, maxLines * g.ROW_LINE_HEIGHT + 2 * g.ROW_VPAD),
        widthBandColor = widthBandColor,
        roomColor = room?.color,
    )
}

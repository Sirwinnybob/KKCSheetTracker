package com.kkc.sheettracker.ui.components.doorcutlist

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream

/** Android side of the Door Panel Cut List print: measures text and paints laid-out pages. */

class PaintTextMeasurer : TextMeasurer {
    private val regularPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }

    override fun width(text: String, sizePt: Float, bold: Boolean): Float {
        val paint = if (bold) boldPaint else regularPaint
        paint.textSize = sizePt
        return paint.measureText(text)
    }
}

/** Writes the PDF to [out]; returns the page count. Deletes [out] and rethrows on failure. */
fun writeDoorPanelCutListPdf(model: DoorPanelCutListModel, out: File): Int {
    val pages = layoutDoorPanelCutList(model, PaintTextMeasurer())
    val document = PdfDocument()
    try {
        pages.forEach { page ->
            val info = PdfDocument.PageInfo.Builder(
                CutListGeometry.PAGE_WIDTH.toInt(),
                CutListGeometry.PAGE_HEIGHT.toInt(),
                page.pageNumber
            ).create()
            val pdfPage = document.startPage(info)
            CutListPainter(pdfPage.canvas).draw(page)
            document.finishPage(pdfPage)
        }
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { document.writeTo(it) }
        return pages.size
    } catch (e: Exception) {
        out.delete()
        throw e
    } finally {
        document.close()
    }
}

/** cacheDir/print/door_cut_list_{job}.pdf, clearing earlier generated files first. */
fun prepareCutListPrintFile(context: Context, jobFolderName: String): File {
    val dir = File(context.cacheDir, "print").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val safeJob = cutListJobNumber(jobFolderName)
        .ifEmpty { "job" }
        .replace(Regex("""[^A-Za-z0-9_-]"""), "_")
    return File(dir, "door_cut_list_$safeJob.pdf")
}

private class CutListPainter(private val canvas: Canvas) {
    private val g = CutListGeometry
    private val fill = Paint().apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val regular = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }

    fun draw(page: CutListPage) {
        page.blocks.forEach { block ->
            when (block) {
                is TitleBlock -> drawTitle(block)
                is LegendBlock -> drawLegend(block)
                is MaterialHeaderBlock -> drawText(
                    block.text, g.MARGIN_X, block.top + 8f + g.MATERIAL_SIZE, g.MATERIAL_SIZE, true,
                    CutListColors.NAVY, CellAlign.LEFT
                )
                is TableHeaderBlock -> drawTableHeader(block)
                is RowBlock -> drawRow(block)
                is FooterBlock -> drawText(
                    block.text, g.MARGIN_X, block.top + g.FOOTER_SIZE + 2f, g.FOOTER_SIZE, false,
                    CutListColors.FOOTER, CellAlign.LEFT
                )
            }
        }
    }

    private fun drawTitle(block: TitleBlock) {
        drawText(block.title, g.MARGIN_X, block.top + g.TITLE_SIZE, g.TITLE_SIZE, true, CutListColors.NAVY, CellAlign.LEFT)
        val bandTop = block.top + g.TITLE_HEIGHT
        val bandBottom = bandTop + block.bandLines.size * g.BAND_LINE_HEIGHT + 2 * g.BAND_PADDING
        drawRect(g.MARGIN_X, bandTop, g.MARGIN_X + g.TABLE_WIDTH, bandBottom, CutListColors.BAND)
        block.bandLines.forEachIndexed { i, line ->
            drawText(
                line, g.MARGIN_X + g.BAND_PADDING,
                bandTop + g.BAND_PADDING + (i + 1) * g.BAND_LINE_HEIGHT - 3f,
                g.BAND_SIZE, false, CutListColors.TEXT, CellAlign.LEFT
            )
        }
    }

    private fun drawLegend(block: LegendBlock) {
        block.chips.forEach { chip ->
            drawRect(chip.left, chip.top, chip.left + chip.width, chip.top + chip.height, chip.color)
            drawText(
                chip.label, chip.left + chip.width / 2f, chip.top + chip.height / 2f + g.ROOM_CELL_SIZE * 0.35f,
                g.ROOM_CELL_SIZE, true, CutListColors.TEXT, CellAlign.CENTER
            )
        }
    }

    private fun drawTableHeader(block: TableHeaderBlock) {
        var x = g.MARGIN_X
        val baseline = block.top + block.height / 2f + g.CELL_SIZE * 0.35f
        block.columns.forEach { column ->
            drawText(column.title, cellX(x, column), baseline, g.CELL_SIZE, false, CutListColors.TEXT, column.align)
            x += column.widthPt
        }
        drawLine(g.MARGIN_X, block.top + block.height, g.MARGIN_X + g.TABLE_WIDTH, block.top + block.height, 0.6f, CutListColors.NAVY)
    }

    private fun drawRow(block: RowBlock) {
        val bottom = block.top + block.height
        if (block.shaded) drawRect(g.MARGIN_X, block.top, g.MARGIN_X + g.TABLE_WIDTH, bottom, CutListColors.ROW_SHADE)
        var x = g.MARGIN_X
        block.columns.forEach { column ->
            val right = x + column.widthPt
            val fillColor = when (column.key) {
                ColumnKey.WIDTH -> block.widthBandColor
                ColumnKey.ROOM -> block.roomColor
                else -> null
            }
            if (fillColor != null) drawRect(x, block.top, right, bottom, fillColor)
            val isRoom = column.key == ColumnKey.ROOM
            val size = if (isRoom) g.ROOM_CELL_SIZE else g.CELL_SIZE
            val lines = block.cells[column.key].orEmpty()
            val firstBaseline = block.top + (block.height - lines.size * g.ROW_LINE_HEIGHT) / 2f + g.ROW_LINE_HEIGHT - 2.5f
            lines.forEachIndexed { i, line ->
                drawText(line, cellX(x, column), firstBaseline + i * g.ROW_LINE_HEIGHT, size, isRoom, CutListColors.TEXT, column.align)
            }
            if (column.key in SEPARATOR_AFTER) drawLine(right, block.top, right, bottom, 1.5f, CutListColors.WHITE)
            x = right
        }
        drawLine(g.MARGIN_X, bottom, g.MARGIN_X + g.TABLE_WIDTH, bottom, 1.2f, CutListColors.WHITE)
    }

    private fun cellX(left: Float, column: CutListColumn): Float = when (column.align) {
        CellAlign.LEFT -> left + g.CELL_HPAD
        CellAlign.CENTER -> left + column.widthPt / 2f
        CellAlign.RIGHT -> left + column.widthPt - g.CELL_HPAD
    }

    private fun drawRect(left: Float, top: Float, right: Float, bottom: Float, color: Int) {
        fill.color = color
        canvas.drawRect(left, top, right, bottom, fill)
    }

    private fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = width
        canvas.drawLine(x1, y1, x2, y2, stroke)
    }

    private fun drawText(text: String, x: Float, baseline: Float, size: Float, isBold: Boolean, color: Int, align: CellAlign) {
        val paint = if (isBold) bold else regular
        paint.textSize = size
        paint.color = color
        paint.textAlign = when (align) {
            CellAlign.LEFT -> Paint.Align.LEFT
            CellAlign.CENTER -> Paint.Align.CENTER
            CellAlign.RIGHT -> Paint.Align.RIGHT
        }
        canvas.drawText(text, x, baseline, paint)
    }

    private companion object {
        /** White column separators, as in CV's layout. */
        val SEPARATOR_AFTER = setOf(ColumnKey.DESCRIPTION, ColumnKey.WIDTH, ColumnKey.LENGTH, ColumnKey.CABINET)
    }
}

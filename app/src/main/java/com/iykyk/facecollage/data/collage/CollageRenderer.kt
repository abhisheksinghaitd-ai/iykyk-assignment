package com.iykyk.facecollage.data.collage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.iykyk.facecollage.util.Constants
import kotlin.math.ceil

/** Everything a tile needs: the full-resolution frame it was cut from and the face's box in
 *  that same full-resolution coordinate space (already scaled up from detection-time coords). */
data class TileSource(
    val personNumber: Int,
    val fullResFrame: Bitmap,
    val faceBox: RectF,
    val appearanceCount: Int
)

/**
 * Renders the final 1080x1920 shareable collage. Crops generously around each face (never
 * tightly to the detected box — a tight crop produces low-resolution, poor-quality tiles) and
 * lays tiles out in a grid whose shape depends on how many people were found.
 */
object CollageRenderer {

    private const val BACKGROUND_TOP = "#14141C"
    private const val BACKGROUND_BOTTOM = "#0E0E12"

    fun render(tiles: List<TileSource>, videoLabel: String): Bitmap {
        val width = Constants.COLLAGE_WIDTH
        val height = Constants.COLLAGE_HEIGHT
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        drawBackground(canvas, width, height)
        drawHeader(canvas, width, videoLabel, tiles.size, tiles.sumOf { it.appearanceCount })

        val margin = Constants.COLLAGE_OUTER_MARGIN
        val gridRect = RectF(
            margin,
            Constants.COLLAGE_HEADER_HEIGHT,
            width - margin,
            height - margin
        )

        val rects = computeTileRects(tiles.size, gridRect)
        for ((tile, rect) in tiles.zip(rects)) {
            drawTile(canvas, tile, rect)
        }

        return bitmap
    }

    // ---- Layout (section 10) ----

    private fun computeTileRects(count: Int, grid: RectF): List<RectF> = when (count) {
        0 -> emptyList()
        1 -> listOf(RectF(grid))
        2 -> verticalStack(grid, 2)
        3 -> heroPlusRow(grid)
        4 -> regularGrid(grid, rows = 2, cols = 2, count = 4)
        5 -> twoByTwoPlusSpan(grid)
        6 -> regularGrid(grid, rows = 2, cols = 3, count = 6)
        in 7..9 -> regularGrid(grid, rows = 3, cols = 3, count = count)
        else -> regularGrid(grid, rows = ceil(count / 3.0).toInt(), cols = 3, count = count)
    }

    private fun verticalStack(grid: RectF, n: Int): List<RectF> {
        val gutter = Constants.COLLAGE_GUTTER
        val cellHeight = (grid.height() - gutter * (n - 1)) / n
        return (0 until n).map { i ->
            val top = grid.top + i * (cellHeight + gutter)
            RectF(grid.left, top, grid.right, top + cellHeight)
        }
    }

    private fun heroPlusRow(grid: RectF): List<RectF> {
        val gutter = Constants.COLLAGE_GUTTER
        val heroHeight = grid.height() * 0.55f
        val rowTop = grid.top + heroHeight + gutter
        val hero = RectF(grid.left, grid.top, grid.right, grid.top + heroHeight)
        val cellWidth = (grid.width() - gutter) / 2f
        val left = RectF(grid.left, rowTop, grid.left + cellWidth, grid.bottom)
        val right = RectF(grid.left + cellWidth + gutter, rowTop, grid.right, grid.bottom)
        return listOf(hero, left, right)
    }

    private fun twoByTwoPlusSpan(grid: RectF): List<RectF> {
        val gutter = Constants.COLLAGE_GUTTER
        val rowHeight = (grid.height() - gutter * 2) / 3f
        val cellWidth = (grid.width() - gutter) / 2f

        val row1Top = grid.top
        val row2Top = row1Top + rowHeight + gutter
        val row3Top = row2Top + rowHeight + gutter

        return listOf(
            RectF(grid.left, row1Top, grid.left + cellWidth, row1Top + rowHeight),
            RectF(grid.left + cellWidth + gutter, row1Top, grid.right, row1Top + rowHeight),
            RectF(grid.left, row2Top, grid.left + cellWidth, row2Top + rowHeight),
            RectF(grid.left + cellWidth + gutter, row2Top, grid.right, row2Top + rowHeight),
            RectF(grid.left, row3Top, grid.right, row3Top + rowHeight)
        )
    }

    /** Row-major grid; a partially-filled final row is centered rather than left stuck to one side. */
    private fun regularGrid(grid: RectF, rows: Int, cols: Int, count: Int): List<RectF> {
        val gutter = Constants.COLLAGE_GUTTER
        val cellWidth = (grid.width() - gutter * (cols - 1)) / cols
        val cellHeight = (grid.height() - gutter * (rows - 1)) / rows

        val rects = mutableListOf<RectF>()
        var remaining = count
        for (row in 0 until rows) {
            val inThisRow = remaining.coerceAtMost(cols)
            if (inThisRow <= 0) break
            val rowWidth = inThisRow * cellWidth + (inThisRow - 1) * gutter
            val rowLeft = grid.left + (grid.width() - rowWidth) / 2f
            val top = grid.top + row * (cellHeight + gutter)
            for (col in 0 until inThisRow) {
                val left = rowLeft + col * (cellWidth + gutter)
                rects.add(RectF(left, top, left + cellWidth, top + cellHeight))
            }
            remaining -= inThisRow
        }
        return rects
    }

    // ---- Cropping ----

    /** Expands the face box by [Constants.COLLAGE_CROP_EXPAND_FACTOR], biases the centre upward
     *  so the crop includes shoulders rather than forehead, fits the tile's aspect ratio, then
     *  clamps inside the frame by shifting (not shrinking) wherever possible. */
    private fun computeCropRect(box: RectF, frameWidth: Int, frameHeight: Int, tileAspect: Float): Rect {
        val cx = box.centerX()
        val cy = box.centerY() - box.height() * Constants.COLLAGE_CROP_UPWARD_BIAS

        var halfW = box.width() / 2f * Constants.COLLAGE_CROP_EXPAND_FACTOR
        var halfH = box.height() / 2f * Constants.COLLAGE_CROP_EXPAND_FACTOR
        if (halfW / halfH > tileAspect) halfH = halfW / tileAspect else halfW = halfH * tileAspect

        var left = cx - halfW
        var right = cx + halfW
        var top = cy - halfH
        var bottom = cy + halfH

        if (left < 0f) { right -= left; left = 0f }
        if (top < 0f) { bottom -= top; top = 0f }
        if (right > frameWidth) { left -= (right - frameWidth); right = frameWidth.toFloat() }
        if (bottom > frameHeight) { top -= (bottom - frameHeight); bottom = frameHeight.toFloat() }

        return Rect(
            left.coerceIn(0f, frameWidth.toFloat()).toInt(),
            top.coerceIn(0f, frameHeight.toFloat()).toInt(),
            right.coerceIn(0f, frameWidth.toFloat()).toInt().coerceAtLeast(1),
            bottom.coerceIn(0f, frameHeight.toFloat()).toInt().coerceAtLeast(1)
        )
    }

    // ---- Drawing ----

    private fun drawBackground(canvas: Canvas, width: Int, height: Int) {
        val paint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                Color.parseColor(BACKGROUND_TOP), Color.parseColor(BACKGROUND_BOTTOM),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    private fun drawHeader(canvas: Canvas, width: Int, videoLabel: String, peopleCount: Int, totalAppearances: Int) {
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 52f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(ellipsize(videoLabel, titlePaint, width - 80f), width / 2f, 96f, titlePaint)

        val peopleWord = if (peopleCount == 1) "person" else "people"
        val appearanceWord = if (totalAppearances == 1) "appearance" else "appearances"
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B8B8C4")
            textSize = 34f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            "$peopleCount $peopleWord · $totalAppearances $appearanceWord",
            width / 2f, 140f, subtitlePaint
        )
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var truncated = text
        while (truncated.isNotEmpty() && paint.measureText("$truncated…") > maxWidth) {
            truncated = truncated.dropLast(1)
        }
        return "$truncated…"
    }

    private fun drawTile(canvas: Canvas, tile: TileSource, rect: RectF) {
        val cornerRadius = Constants.COLLAGE_TILE_CORNER_RADIUS
        val clipPath = Path().apply { addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW) }

        canvas.save()
        canvas.clipPath(clipPath)

        val tileAspect = rect.width() / rect.height()
        val srcRect = computeCropRect(tile.faceBox, tile.fullResFrame.width, tile.fullResFrame.height, tileAspect)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        canvas.drawBitmap(tile.fullResFrame, srcRect, rect, paint)

        val scrimTop = rect.top + rect.height() * 0.6f
        val scrimPaint = Paint().apply {
            shader = LinearGradient(
                0f, scrimTop, 0f, rect.bottom,
                Color.TRANSPARENT, Color.parseColor("#D9000000"),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(rect, scrimPaint)

        canvas.restore()

        drawBadge(canvas, rect, tile.appearanceCount)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (rect.height() * 0.075f).coerceIn(28f, 48f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }
        val label = "Person ${tile.personNumber}"
        canvas.drawText(
            ellipsize(label, labelPaint, rect.width() - 24f),
            rect.left + 20f,
            rect.bottom - 24f,
            labelPaint
        )
    }

    private fun drawBadge(canvas: Canvas, rect: RectF, appearanceCount: Int) {
        val text = if (appearanceCount == 1) "1 appearance" else "$appearanceCount appearances"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (rect.height() * 0.05f).coerceIn(20f, 32f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }
        val padH = 16f
        val padV = 10f
        val textWidth = textPaint.measureText(text)
        val badgeHeight = textPaint.textSize + padV * 2
        val badgeWidth = (textWidth + padH * 2).coerceAtMost(rect.width() - 24f)
        val badgeLeft = rect.left + 20f
        val badgeTop = rect.bottom - 24f - textPaint.textSize - 12f - badgeHeight
        val badgeRect = RectF(badgeLeft, badgeTop, badgeLeft + badgeWidth, badgeTop + badgeHeight)

        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CC5A4FCF") }
        canvas.drawRoundRect(badgeRect, badgeHeight / 2f, badgeHeight / 2f, badgePaint)
        canvas.drawText(
            ellipsize(text, textPaint, badgeWidth - padH * 2),
            badgeLeft + padH,
            badgeTop + badgeHeight / 2f + textPaint.textSize * 0.35f,
            textPaint
        )
    }
}

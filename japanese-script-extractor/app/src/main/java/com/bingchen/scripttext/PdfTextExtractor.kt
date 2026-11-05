package com.bingchen.scripttext

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.math.roundToInt

class PdfTextExtractor(context: Context) {
    init { PDFBoxResourceLoader.init(context.applicationContext) }

    data class Result(val text: String, val pageCount: Int, val characterCount: Int)

    fun extract(input: InputStream, includePageMarkers: Boolean = true): Result {
        PDDocument.load(input).use { document ->
            require(!document.isEncrypted) { "この PDF は暗号化されているため、現在は対応していません。" }
            val pageCount = document.numberOfPages
            require(pageCount > 0) { "PDF にページがありません。" }

            val output = StringBuilder()
            for (page in 1..pageCount) {
                // Detect the writing direction from glyph coordinates before choosing
                // the extraction strategy. PDFTextStripper's positional sort is
                // designed for horizontal text and interleaves vertical columns.
                val probe = PositionCollector().apply {
                    sortByPosition = false
                    startPage = page
                    endPage = page
                }
                probe.getText(document)

                val pageText = if (probe.isVerticalLayout()) {
                    probe.toVerticalText()
                } else {
                    val stripper = PDFTextStripper().apply {
                        sortByPosition = true
                        lineSeparator = "\n"
                        wordSeparator = ""
                        paragraphStart = "\n"
                        paragraphEnd = "\n"
                        pageStart = ""
                        pageEnd = ""
                        startPage = page
                        endPage = page
                    }
                    stripper.getText(document)
                        .replace("\r\n", "\n")
                        .replace('\r', '\n')
                        .trim()
                }

                if (page > 1 && includePageMarkers) {
                    output.append("\n\n──────── ページ ").append(page).append(" ────────\n\n")
                } else if (page > 1) {
                    output.append("\n\n")
                }
                output.append(pageText)
            }
            val text = output.toString().trim()
            return Result(text, pageCount, text.length)
        }
    }

    private class PositionCollector : PDFTextStripper() {
        private val collected = mutableListOf<TextPosition>()

        override fun processTextPosition(text: TextPosition) {
            collected.add(text)
            super.processTextPosition(text)
        }

        fun isVerticalLayout(): Boolean {
            val glyphs = collected.filter { !it.unicode.isNullOrBlank() }
            if (glyphs.size < 8) return false

            // PDF content-stream order is not a reliable reading order: some
            // documents store glyphs column-by-column, others interleave them.
            // Compare the nearest aligned neighbour in each direction instead.
            // Vertical Japanese has a shorter same-column Y gap than same-row X
            // gap; horizontal text has the opposite pattern.
            val verticalGaps = FloatArray(glyphs.size) { Float.MAX_VALUE }
            val horizontalGaps = FloatArray(glyphs.size) { Float.MAX_VALUE }

            fun buildGroups(axis: (TextPosition) -> Float): List<MutableList<Int>> {
                val sorted = glyphs.indices.sortedBy { axis(glyphs[it]) }
                val groups = mutableListOf<MutableList<Int>>()
                sorted.forEach { index ->
                    val value = axis(glyphs[index])
                    val group = groups.lastOrNull()
                    val center = group?.map { axis(glyphs[it]) }?.average()?.toFloat()
                    if (group == null || center == null || abs(value - center) > 3.5f) {
                        groups.add(mutableListOf(index))
                    } else {
                        group.add(index)
                    }
                }
                return groups
            }

            // Same X coordinate: find each glyph's closest neighbour above/below.
            buildGroups { it.x }.forEach { group ->
                val sorted = group.sortedBy { glyphs[it].y }
                sorted.forEachIndexed { position, index ->
                    if (position > 0) {
                        verticalGaps[index] = minOf(
                            verticalGaps[index],
                            abs(glyphs[index].y - glyphs[sorted[position - 1]].y)
                        )
                    }
                    if (position < sorted.lastIndex) {
                        verticalGaps[index] = minOf(
                            verticalGaps[index],
                            abs(glyphs[index].y - glyphs[sorted[position + 1]].y)
                        )
                    }
                }
            }

            // Same Y coordinate: find each glyph's closest neighbour left/right.
            buildGroups { it.y }.forEach { group ->
                val sorted = group.sortedBy { glyphs[it].x }
                sorted.forEachIndexed { position, index ->
                    if (position > 0) {
                        horizontalGaps[index] = minOf(
                            horizontalGaps[index],
                            abs(glyphs[index].x - glyphs[sorted[position - 1]].x)
                        )
                    }
                    if (position < sorted.lastIndex) {
                        horizontalGaps[index] = minOf(
                            horizontalGaps[index],
                            abs(glyphs[index].x - glyphs[sorted[position + 1]].x)
                        )
                    }
                }
            }

            var verticalVotes = 0
            var horizontalVotes = 0
            glyphs.indices.forEach { index ->
                val vertical = verticalGaps[index]
                val horizontal = horizontalGaps[index]
                if (vertical < horizontal * 0.85f) verticalVotes++
                else if (horizontal < vertical * 0.85f) horizontalVotes++
            }

            return verticalVotes >= 5 && verticalVotes > horizontalVotes * 1.25f
        }

        fun toVerticalText(): String {
            if (collected.isEmpty()) return ""

            data class Column(var centerX: Float, val glyphs: MutableList<TextPosition>)
            val allGlyphs = collected.filter { !it.unicode.isNullOrEmpty() }
            if (allGlyphs.isEmpty()) return ""

            val excluded = mutableSetOf<TextPosition>()
            val pageNumber = extractFooterPageNumber(allGlyphs, excluded)

            // Many scripts print a small 1, 2, 3... above every vertical column.
            // These are layout guides, not part of the spoken script. Remove only
            // small, standalone numeric labels in the narrow top band; normal
            // numbers inside the body are retained.
            val fontSizes = allGlyphs.map { it.fontSizeInPt }.filter { it > 0f }.sorted()
            val typicalFontSize = if (fontSizes.isEmpty()) 14f else fontSizes[fontSizes.size / 2]
            val minY = allGlyphs.minOf { it.y }
            val maxY = allGlyphs.maxOf { it.y }
            val pageHeight = (maxY - minY).coerceAtLeast(1f)
            val headerLimit = minY + pageHeight * 0.16f

            allGlyphs.forEach { glyph ->
                val value = glyph.unicode.trim()
                if (glyph.fontSizeInPt < typicalFontSize * 0.9f &&
                    glyph.y <= headerLimit &&
                    value.matches(Regex("\\d{1,2}"))
                ) {
                    excluded.add(glyph)
                }
            }

            val columns = mutableListOf<Column>()

            // Japanese tategaki is read from the rightmost column to the left,
            // and from top to bottom inside each column.
            allGlyphs
                .filterNot { it in excluded }
                .sortedByDescending { it.x }
                .forEach { glyph ->
                    val current = columns.lastOrNull()
                    if (current == null || abs(current.centerX - glyph.x) > 10f) {
                        columns.add(Column(glyph.x, mutableListOf(glyph)))
                    } else {
                        current.glyphs.add(glyph)
                        current.centerX = current.glyphs.map { it.x }.average().toFloat()
                    }
                }

            val result = StringBuilder()
            columns.forEachIndexed { columnIndex, column ->
                val glyphs = column.glyphs.sortedBy { it.y }
                glyphs.forEach { glyph -> result.append(glyph.unicode) }
                if (columnIndex < columns.lastIndex) result.append('\n')
            }

            // Keep the PDF's own page counter as one intact token at the end.
            // It is extracted separately because its horizontal baseline would
            // otherwise be split across the vertical body columns.
            if (!pageNumber.isNullOrBlank()) {
                if (result.isNotEmpty()) result.append('\n')
                result.append(pageNumber)
            }

            return result.toString().trim()
        }

        private fun extractFooterPageNumber(
            glyphs: List<TextPosition>,
            excluded: MutableSet<TextPosition>
        ): String? {
            val sizes = glyphs.map { it.fontSizeInPt }.filter { it > 0f }.sorted()
            if (sizes.isEmpty()) return null
            val typicalSize = sizes[sizes.size / 2]
            val minY = glyphs.minOf { it.y }
            val maxY = glyphs.maxOf { it.y }
            val footerLimit = minY + (maxY - minY).coerceAtLeast(1f) * 0.78f

            val smallFooterGlyphs = glyphs.filter {
                it.fontSizeInPt < typicalSize * 0.9f && it.y >= footerLimit
            }
            if (smallFooterGlyphs.isEmpty()) return null

            // Reassemble horizontally printed footer fragments by baseline and
            // X position, then accept only a strict current/total page pattern.
            val rows = mutableListOf<MutableList<TextPosition>>()
            smallFooterGlyphs.sortedBy { it.y }.forEach { glyph ->
                val row = rows.lastOrNull()
                val centerY = row?.map { it.y }?.average()?.toFloat()
                if (row == null || centerY == null || abs(glyph.y - centerY) > 4f) {
                    rows.add(mutableListOf(glyph))
                } else {
                    row.add(glyph)
                }
            }

            for (row in rows) {
                val sorted = row.sortedBy { it.x }
                val clusters = mutableListOf<MutableList<TextPosition>>()
                sorted.forEach { glyph ->
                    val cluster = clusters.lastOrNull()
                    val previous = cluster?.lastOrNull()
                    if (cluster == null || previous == null ||
                        glyph.x - (previous.x + previous.width) > 24f
                    ) {
                        clusters.add(mutableListOf(glyph))
                    } else {
                        cluster.add(glyph)
                    }
                }

                for (cluster in clusters) {
                    val raw = cluster.joinToString("") { it.unicode }
                    val normalized = raw.replace(Regex("\\s+"), "")
                    if (normalized.matches(Regex("\\d{1,4}/\\d{1,4}"))) {
                        excluded.addAll(cluster)
                        return normalized
                    }
                }
            }
            return null
        }
    }

    fun encodeUtf8(text: String): ByteArray = text.toByteArray(StandardCharsets.UTF_8)
}

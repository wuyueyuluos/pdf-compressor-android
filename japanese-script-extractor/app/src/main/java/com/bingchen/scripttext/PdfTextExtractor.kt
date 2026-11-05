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
            val glyphs = collected.filter { !it.unicode.isNullOrEmpty() }
            if (glyphs.size < 8) return false

            // Compare actual movement between consecutive glyphs instead of
            // counting occupied coordinate buckets. Vertical Japanese advances
            // mainly in Y while X stays nearly constant. Horizontal page numbers
            // and labels no longer confuse the page-level direction test.
            var verticalSteps = 0
            var horizontalSteps = 0
            for (index in 1 until glyphs.size) {
                val previous = glyphs[index - 1]
                val current = glyphs[index]
                val dx = abs(current.x - previous.x)
                val dy = abs(current.y - previous.y)

                if (dx <= 3.5f && dy >= 2f) {
                    verticalSteps++
                } else if (dy <= 3.5f && dx >= 2f) {
                    horizontalSteps++
                }
            }

            return verticalSteps >= 5 && verticalSteps > horizontalSteps * 1.35f
        }

        fun toVerticalText(): String {
            if (collected.isEmpty()) return ""

            data class Column(var centerX: Float, val glyphs: MutableList<TextPosition>)
            val columns = mutableListOf<Column>()

            // Japanese tategaki is read from the rightmost column to the left,
            // and from top to bottom inside each column.
            collected
                .filter { !it.unicode.isNullOrEmpty() }
                .sortedByDescending { it.x }
                .forEach { glyph ->
                    val current = columns.lastOrNull()
                    // Numbering markers can sit 7-8pt beside the first glyph
                    // of a vertical column. Keep them with that column; adjacent
                    // Japanese columns in the supplied PDFs are at least ~18pt apart.
                    if (current == null || abs(current.centerX - glyph.x) > 10f) {
                        columns.add(Column(glyph.x, mutableListOf(glyph)))
                    } else {
                        current.glyphs.add(glyph)
                        current.centerX = current.glyphs.map { it.x }.average().toFloat()
                    }
                }

            val result = StringBuilder()
            columns.forEachIndexed { columnIndex, column ->
                // A Japanese vertical column becomes one horizontal TXT line.
                // Do not interpret normal glyph spacing as line breaks: PDFBox's
                // glyph height can be much smaller than the vertical advance,
                // which previously split nearly every character onto its own line.
                val glyphs = column.glyphs.sortedBy { it.y }
                glyphs.forEach { glyph ->
                    result.append(glyph.unicode)
                }

                if (columnIndex < columns.lastIndex) result.append('\n')
            }
            return result.toString().trim()
        }
    }

    fun encodeUtf8(text: String): ByteArray = text.toByteArray(StandardCharsets.UTF_8)
}

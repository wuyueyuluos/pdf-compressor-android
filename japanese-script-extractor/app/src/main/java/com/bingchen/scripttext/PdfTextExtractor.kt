package com.bingchen.scripttext

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.InputStream
import java.nio.charset.StandardCharsets

class PdfTextExtractor(context: Context) {
    init { PDFBoxResourceLoader.init(context.applicationContext) }

    data class Result(val text: String, val pageCount: Int, val characterCount: Int)

    fun extract(input: InputStream, includePageMarkers: Boolean = true): Result {
        PDDocument.load(input).use { document ->
            require(!document.isEncrypted) { "この PDF は暗号化されているため、現在は対応していません。" }
            val pageCount = document.numberOfPages
            require(pageCount > 0) { "PDF にページがありません。" }

            // Extract page-by-page so separators are inserted only BETWEEN pages.
            val output = StringBuilder()
            for (page in 1..pageCount) {
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
                val pageText = stripper.getText(document).replace("\r\n", "\n").replace('\r', '\n').trim()
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

    fun encodeUtf8(text: String): ByteArray = text.toByteArray(StandardCharsets.UTF_8)
}

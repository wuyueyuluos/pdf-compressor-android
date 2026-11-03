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
            require(document.numberOfPages > 0) { "PDF にページがありません。" }

            val stripper = PDFTextStripper().apply {
                sortByPosition = true
                lineSeparator = "\n"
                wordSeparator = ""
                paragraphStart = "\n"
                paragraphEnd = "\n"
                pageStart = ""
                pageEnd = ""
                if (includePageMarkers) {
                    pageSeparator = "\n\n──────── ページ区切り ────────\n\n"
                } else {
                    pageSeparator = "\n\n"
                }
            }
            val text = stripper.getText(document)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim()
            return Result(text, document.numberOfPages, text.length)
        }
    }

    fun encodeUtf8(text: String): ByteArray = text.toByteArray(StandardCharsets.UTF_8)
}

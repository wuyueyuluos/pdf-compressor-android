package com.bingchen.pdfcompressor

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.InputStream

/**
 * Safe first-stage PDF rewrite.
 *
 * This pass preserves page content instead of rasterizing pages. It may reduce
 * redundant PDF structures in some files, but it does not recompress embedded
 * images and is not expected to shrink every PDF.
 */
class PdfCompressionEngine(context: Context) {
    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    data class Result(
        val outputFile: File,
        val originalBytes: Long,
        val outputBytes: Long,
        val pageCount: Int,
        val reduced: Boolean
    )

    fun rewrite(input: InputStream, originalBytes: Long, workFile: File): Result {
        var pageCount = 0
        PDDocument.load(input).use { document ->
            require(!document.isEncrypted) { "暂不支持加密 PDF" }
            pageCount = document.numberOfPages
            require(pageCount > 0) { "PDF 没有页面" }
            document.save(workFile)
        }

        // Re-open the generated file before it is ever offered as a result.
        PDDocument.load(workFile).use { check ->
            require(check.numberOfPages == pageCount) { "输出 PDF 校验失败：页数不一致" }
        }

        val outputBytes = workFile.length()
        return Result(
            outputFile = workFile,
            originalBytes = originalBytes,
            outputBytes = outputBytes,
            pageCount = pageCount,
            reduced = outputBytes in 1 until originalBytes
        )
    }
}

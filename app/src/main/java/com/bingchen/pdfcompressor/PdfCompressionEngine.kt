package com.bingchen.pdfcompressor

import android.content.Context
import android.graphics.Bitmap
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.PDXObject
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import java.io.InputStream
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Re-encodes eligible embedded images while leaving page content streams,
 * text, vectors and document structure intact. Images with masks are skipped
 * in this first image-compression pass to avoid changing transparency.
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
        val reduced: Boolean,
        val imagesCompressed: Int,
        val imagesSkipped: Int
    )

    private data class Options(val maxDimension: Int, val jpegQuality: Float)

    private fun settings(level: Int): Options = when (level.coerceIn(0, 2)) {
        0 -> Options(2400, 0.86f)
        1 -> Options(1800, 0.74f)
        else -> Options(1300, 0.60f)
    }

    fun rewrite(input: InputStream, originalBytes: Long, workFile: File, level: Int = 1): Result {
        var pageCount = 0
        var compressed = 0
        var skipped = 0
        val options = settings(level)
        val replacements = IdentityHashMap<COSStream, PDImageXObject>()
        val visitedForms = IdentityHashMap<COSStream, Boolean>()

        PDDocument.load(input).use { document ->
            require(!document.isEncrypted) { "暂不支持加密 PDF" }
            pageCount = document.numberOfPages
            require(pageCount > 0) { "PDF 没有页面" }

            fun processResources(resources: PDResources?) {
                if (resources == null) return
                val names = resources.xObjectNames.toList()
                for (name in names) {
                    val xObject: PDXObject = try {
                        resources.getXObject(name) ?: continue
                    } catch (_: Exception) {
                        skipped++
                        continue
                    }

                    when (xObject) {
                        is PDImageXObject -> {
                            val sourceStream = xObject.cosObject
                            val existing = replacements[sourceStream]
                            if (existing != null) {
                                resources.put(name, existing)
                                continue
                            }

                            // Do not rewrite stencil, soft-mask, explicit-mask or color-key images.
                            val hasSpecialMask = try {
                                xObject.isStencil ||
                                    xObject.softMask != null ||
                                    xObject.mask != null ||
                                    xObject.colorKeyMask != null
                            } catch (_: Exception) {
                                true
                            }
                            if (hasSpecialMask || xObject.width <= 0 || xObject.height <= 0 ||
                                max(xObject.width, xObject.height) <= options.maxDimension) {
                                skipped++
                                continue
                            }

                            var bitmap: Bitmap? = null
                            var scaled: Bitmap? = null
                            try {
                                var sample = 1
                                while (max(xObject.width / (sample * 2), xObject.height / (sample * 2)) >= options.maxDimension) {
                                    sample *= 2
                                }
                                bitmap = xObject.getImage(null, sample)
                                val edge = max(bitmap.width, bitmap.height)
                                val targetWidth = max(1, (bitmap.width * options.maxDimension.toFloat() / edge).roundToInt())
                                val targetHeight = max(1, (bitmap.height * options.maxDimension.toFloat() / edge).roundToInt())
                                val encodedBitmap = if (bitmap.width != targetWidth || bitmap.height != targetHeight) {
                                    Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true).also { scaled = it }
                                } else {
                                    bitmap
                                }

                                val replacement = JPEGFactory.createFromImage(document, encodedBitmap, options.jpegQuality)
                                replacement.setInterpolate(xObject.interpolate)
                                resources.put(name, replacement)
                                replacements[sourceStream] = replacement
                                compressed++
                            } catch (_: OutOfMemoryError) {
                                skipped++
                            } catch (_: Exception) {
                                skipped++
                            } finally {
                                if (scaled != null && scaled !== bitmap) scaled?.recycle()
                                bitmap?.recycle()
                            }
                        }
                        is PDFormXObject -> {
                            val formStream = xObject.cosObject
                            if (visitedForms.put(formStream, true) == null) {
                                try {
                                    processResources(xObject.resources)
                                } catch (_: Exception) {
                                    // A malformed optional form must not abort the whole document.
                                }
                            }
                        }
                    }
                }
            }

            for (page in document.pages) {
                processResources(page.resources)
            }
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
            reduced = outputBytes in 1 until originalBytes,
            imagesCompressed = compressed,
            imagesSkipped = skipped
        )
    }
}

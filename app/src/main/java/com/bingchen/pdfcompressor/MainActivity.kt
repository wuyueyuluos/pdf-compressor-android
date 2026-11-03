package com.bingchen.pdfcompressor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("pdf_compressor", MODE_PRIVATE) }
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var treeUri: Uri? = null
    private var selectedUri: Uri? = null
    private var selectedName: String? = null
    private var level = 1
    private lateinit var root: LinearLayout
    private lateinit var fileList: LinearLayout
    private lateinit var status: TextView
    private lateinit var levelLabel: TextView
    private lateinit var compressButton: Button

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            treeUri = uri
            prefs.edit().putString("tree_uri", uri.toString()).apply()
            scanFolder()
        }
    }

    private val pdfPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
            selectedUri = uri
            selectedName = DocumentFile.fromSingleUri(this, uri)?.name ?: "所选 PDF"
            renderSelection()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        treeUri = prefs.getString("tree_uri", null)?.let(Uri::parse)
        buildUi()
        if (treeUri != null) scanFolder()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(28))
            setBackgroundColor(0xFFFFFCF8.toInt())
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "PDF 圧縮"
            textSize = 28f
            setTextColor(0xFF344B50.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "轻轻整理文件，尽量保留清晰度。"
            textSize = 14f
            setTextColor(0xFF758487.toInt())
            setPadding(0, dp(6), 0, dp(22))
        })
        button("选择扫描文件夹") { folderPicker.launch(treeUri) }
        button("单独选择 PDF") { pdfPicker.launch(arrayOf("application/pdf")) }

        root.addView(TextView(this).apply {
            text = "压缩强度"
            textSize = 18f
            setTextColor(0xFF344B50.toInt())
            setPadding(0, dp(22), 0, dp(8))
        })
        levelLabel = TextView(this).apply { textSize = 14f; setTextColor(0xFF53666A.toInt()) }
        root.addView(levelLabel)
        val levels = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("轻度", "中度", "强力").forEachIndexed { index, label ->
            val b = Button(this).apply { text = label; isAllCaps = false }
            b.setOnClickListener { level = index; updateLevelLabel() }
            levels.addView(b, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) })
        }
        root.addView(levels)
        updateLevelLabel()

        root.addView(TextView(this).apply {
            text = "扫描结果"
            textSize = 18f
            setTextColor(0xFF344B50.toInt())
            setPadding(0, dp(22), 0, dp(8))
        })
        status = TextView(this).apply {
            text = "请选择一个文件夹开始扫描。"
            textSize = 14f
            setTextColor(0xFF758487.toInt())
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(status)
        fileList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(fileList)
        compressButton = Button(this).apply {
            text = "压缩所选 PDF"
            isAllCaps = false
            setOnClickListener { compressSelected() }
        }
        root.addView(compressButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
            bottomMargin = dp(6)
        })
        root.addView(TextView(this).apply {
            text = "压缩会尝试重新编码符合条件的嵌入图片，并保留 PDF 页面中的文字与矢量内容。带特殊透明蒙版的图片会跳过。结果仅在确实变小时保存到「PDF压缩」目录，原文件始终保留。"
            textSize = 12f
            setTextColor(0xFF8B9696.toInt())
            setPadding(0, dp(14), 0, 0)
        })
    }

    private fun scanFolder() {
        fileList.removeAllViews()
        val uri = treeUri ?: return
        val tree = DocumentFile.fromTreeUri(this, uri)
        if (tree == null) {
            status.text = "无法读取该文件夹，请重新选择。"
            return
        }
        val found = mutableListOf<Pair<DocumentFile, String>>()
        fun walk(dir: DocumentFile, path: String) {
            dir.listFiles().forEach { file ->
                val name = file.name ?: "未命名文件"
                val next = "$path/$name"
                if (file.isDirectory) walk(file, next)
                else if (name.endsWith(".pdf", ignoreCase = true)) found.add(file to next)
            }
        }
        walk(tree, tree.name ?: "已选文件夹")
        status.text = "找到 ${found.size} 个 PDF 文件"
        found.forEach { (file, path) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(0xFFF3F0E9.toInt())
            }
            row.addView(TextView(this).apply {
                text = file.name ?: "未命名 PDF"
                textSize = 15f
                setTextColor(0xFF344B50.toInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            row.addView(TextView(this).apply {
                text = path
                textSize = 11f
                setTextColor(0xFF758487.toInt())
            })
            row.addView(TextView(this).apply {
                text = readableSize(file.length())
                textSize = 12f
                setTextColor(0xFF53666A.toInt())
            })
            row.setOnClickListener {
                selectedUri = file.uri
                selectedName = file.name
                renderSelection()
            }
            fileList.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }
    }

    private fun renderSelection() {
        status.text = if (selectedUri != null) "已选择：$selectedName。点击列表可切换文件。" else "请选择 PDF 文件。"
    }

    private fun compressSelected() {
        val inputUri = selectedUri
        val outputRootUri = treeUri
        if (inputUri == null) {
            Toast.makeText(this, "请先从列表或文件选择器中选择一个 PDF。", Toast.LENGTH_SHORT).show()
            return
        }
        if (outputRootUri == null) {
            Toast.makeText(this, "请先选择扫描文件夹，压缩结果将保存在该文件夹内。", Toast.LENGTH_LONG).show()
            return
        }
        compressButton.isEnabled = false
        status.text = "正在整理 PDF，请稍候……"
        val name = selectedName ?: "document.pdf"
        val selectedLevel = level
        executor.execute {
            var temp: File? = null
            try {
                val originalBytes = contentResolver.openFileDescriptor(inputUri, "r")?.use { it.statSize }?.takeIf { it > 0 } ?: 0L
                temp = File(cacheDir, "pdf_work_${System.currentTimeMillis()}.pdf")
                val result = contentResolver.openInputStream(inputUri)?.use { input ->
                    PdfCompressionEngine(this).rewrite(input, originalBytes, temp!!, selectedLevel)
                } ?: throw IllegalStateException("无法读取原 PDF")
                if (result.outputBytes >= result.originalBytes || result.originalBytes <= 0L) {
                    temp.delete()
                    mainHandler.post {
                        status.text = "已完成完整性校验：共 ${result.pageCount} 页。重写后没有变小，因此未保存结果，也没有改动原文件。"
                        compressButton.isEnabled = true
                    }
                    return@execute
                }

                val tree = DocumentFile.fromTreeUri(this, outputRootUri) ?: throw IllegalStateException("无法访问输出文件夹")
                val outputDir = tree.findFile("PDF压缩")?.takeIf { it.isDirectory }
                    ?: tree.createDirectory("PDF压缩")
                    ?: throw IllegalStateException("无法创建 PDF压缩 文件夹")
                val base = name.substringBeforeLast('.', name)
                var outputName = "${base}_压缩.pdf"
                var suffix = 2
                while (outputDir.findFile(outputName) != null) {
                    outputName = "${base}_压缩($suffix).pdf"
                    suffix++
                }
                val target = outputDir.createFile("application/pdf", outputName)
                    ?: throw IllegalStateException("无法创建输出 PDF")
                try {
                    contentResolver.openOutputStream(target.uri, "w")?.use { out ->
                        temp.inputStream().use { it.copyTo(out) }
                    } ?: throw IllegalStateException("无法写入输出 PDF")
                } catch (e: Exception) {
                    target.delete()
                    throw e
                }
                temp.delete()
                val savedBytes = target.length()
                mainHandler.post {
                    status.text = "压缩完成：${result.pageCount} 页，处理图片 ${result.imagesCompressed} 张，跳过 ${result.imagesSkipped} 张。${readableSize(result.originalBytes)} → ${readableSize(savedBytes)}，节省 ${String.format("%.1f", (1.0 - savedBytes.toDouble() / result.originalBytes) * 100)}%。已保存到「PDF压缩/$outputName」。"
                    compressButton.isEnabled = true
                }
            } catch (e: Exception) {
                temp?.delete()
                mainHandler.post {
                    status.text = "处理失败：${e.message ?: "未知错误"}"
                    compressButton.isEnabled = true
                }
            }
        }
    }

    private fun updateLevelLabel() {
        levelLabel.text = when (level) {
            0 -> "轻度：优先保留画质"
            1 -> "中度：平衡画质与体积"
            else -> "强力：更积极地缩小图片体积"
        }
    }

    private fun button(label: String, action: () -> Unit) {
        root.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
            bottomMargin = dp(6)
        })
    }

    private fun readableSize(bytes: Long): String =
        if (bytes >= 1024L * 1024L) String.format("%.2f MB", bytes / (1024.0 * 1024.0))
        else String.format("%.1f KB", bytes / 1024.0)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

package com.bingchen.scripttext

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
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
    private val prefs by lazy { getSharedPreferences("script_text_extractor", MODE_PRIVATE) }
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var treeUri: Uri? = null
    private val selected = linkedMapOf<String, DocumentFile>()
    private var includePageMarkers = true
    private lateinit var root: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var convertButton: Button
    private lateinit var pageMarkerCheck: CheckBox

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            treeUri = uri
            prefs.edit().putString("tree_uri", uri.toString()).apply()
            scanFolder()
        }
    }
    private val pdfPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                val file = DocumentFile.fromSingleUri(this, uri)
                if (file != null) selected[uri.toString()] = file
            }
            renderList()
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
            text = "日文台本提取器"
            textSize = 27f
            setTextColor(0xFF344B50.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "PDF の文字を UTF-8 の TXT に。すべて端末内で処理します。"
            textSize = 13f
            setTextColor(0xFF758487.toInt())
            setPadding(0, dp(7), 0, dp(20))
        })
        button("选择台本文件夹并扫描") { folderPicker.launch(treeUri) }
        button("单独选择 PDF（可多选）") { pdfPicker.launch(arrayOf("application/pdf")) }

        root.addView(TextView(this).apply {
            text = "PDF 文件列表"
            textSize = 18f
            setTextColor(0xFF344B50.toInt())
            setPadding(0, dp(22), 0, dp(8))
        })
        status = TextView(this).apply {
            text = "请选择文件夹或单独选择 PDF。"
            textSize = 13f
            setTextColor(0xFF758487.toInt())
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        pageMarkerCheck = CheckBox(this).apply {
            text = "在 TXT 中标记 PDF 页码分隔"
            isChecked = true
            setTextColor(0xFF53666A.toInt())
            setOnCheckedChangeListener { _, checked -> includePageMarkers = checked }
        }
        root.addView(pageMarkerCheck)
        convertButton = Button(this).apply {
            text = "提取并保存 TXT"
            isAllCaps = false
            setOnClickListener { convertSelected() }
        }
        root.addView(convertButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
            topMargin = dp(10)
        })
        root.addView(TextView(this).apply {
            text = "说明：仅提取 PDF 内嵌的真实文字，不包含扫描图片中的文字。扫描版 PDF 需要 OCR，本版本暂不支持。原 PDF 不会被修改。"
            textSize = 12f
            setTextColor(0xFF8B9696.toInt())
            setPadding(0, dp(14), 0, 0)
        })
    }

    private fun scanFolder() {
        val uri = treeUri ?: return
        status.text = "正在扫描文件夹……"
        executor.execute {
            val found = mutableListOf<Pair<DocumentFile, String>>()
            try {
                val tree = DocumentFile.fromTreeUri(this, uri) ?: throw IllegalStateException("无法访问该文件夹")
                fun walk(dir: DocumentFile, path: String) {
                    dir.listFiles().forEach { file ->
                        val name = file.name ?: "未命名文件"
                        val next = "$path/$name"
                        if (file.isDirectory) walk(file, next)
                        else if (name.endsWith(".pdf", ignoreCase = true)) found.add(file to next)
                    }
                }
                walk(tree, tree.name ?: "已选文件夹")
                mainHandler.post {
                    selected.clear()
                    found.forEach { selected[it.first.uri.toString()] = it.first }
                    status.text = "扫描完成：找到 ${found.size} 份 PDF。勾选需要提取的文件。"
                    renderList(found.associate { it.first.uri.toString() to it.second })
                }
            } catch (e: Exception) {
                mainHandler.post { status.text = "扫描失败：${e.message}" }
            }
        }
    }

    private fun renderList(paths: Map<String, String> = emptyMap()) {
        list.removeAllViews()
        if (selected.isEmpty()) {
            list.addView(TextView(this).apply { text = "暂时没有 PDF 文件。"; setTextColor(0xFF758487.toInt()) })
            return
        }
        selected.forEach { (key, file) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setBackgroundColor(0xFFF3F0E9.toInt())
            }
            val check = CheckBox(this).apply {
                text = file.name ?: "未命名 PDF"
                isChecked = true
                setTextColor(0xFF344B50.toInt())
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected[key] = file else selected.remove(key)
                    status.text = "已选择 ${selected.size} 份 PDF。"
                }
            }
            row.addView(check, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val path = paths[key]
            if (path != null) {
                row.addView(TextView(this).apply {
                    text = path
                    textSize = 10f
                    setTextColor(0xFF758487.toInt())
                    maxWidth = dp(110)
                })
            }
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            })
        }
    }

    private fun convertSelected() {
        val files = selected.values.toList()
        if (files.isEmpty()) {
            Toast.makeText(this, "请先选择 PDF 文件。", Toast.LENGTH_SHORT).show()
            return
        }
        val outputRoot = treeUri?.let { DocumentFile.fromTreeUri(this, it) }
        if (outputRoot == null) {
            Toast.makeText(this, "请先选择一个输出文件夹。", Toast.LENGTH_LONG).show()
            return
        }
        convertButton.isEnabled = false
        status.text = "正在提取文字，请稍候……"
        val markers = includePageMarkers
        executor.execute {
            var success = 0
            var noText = 0
            val failures = mutableListOf<String>()
            try {
                val outputDir = outputRoot.findFile("台本TXT")?.takeIf { it.isDirectory }
                    ?: outputRoot.createDirectory("台本TXT")
                    ?: throw IllegalStateException("无法创建「台本TXT」文件夹")
                files.forEach { source ->
                    val name = source.name ?: "document.pdf"
                    try {
                        val extracted = contentResolver.openInputStream(source.uri)?.use {
                            PdfTextExtractor(this).extract(it, markers)
                        } ?: throw IllegalStateException("无法读取文件")
                        if (extracted.text.isBlank()) {
                            noText++
                        } else {
                            val base = name.substringBeforeLast('.', name)
                            var outputName = "$base.txt"
                            var suffix = 2
                            while (outputDir.findFile(outputName) != null) {
                                outputName = "$base($suffix).txt"
                                suffix++
                            }
                            val target = outputDir.createFile("text/plain", outputName)
                                ?: throw IllegalStateException("无法创建 TXT")
                            try {
                                contentResolver.openOutputStream(target.uri, "w")?.use { out ->
                                    out.write(PdfTextExtractor(this).encodeUtf8(extracted.text))
                                } ?: throw IllegalStateException("无法写入 TXT")
                            } catch (e: Exception) {
                                target.delete()
                                throw e
                            }
                            success++
                        }
                    } catch (e: Exception) {
                        failures.add("$name：${e.message ?: "转换失败"}")
                    }
                }
                mainHandler.post {
                    val detail = buildString {
                        append("完成：成功生成 ${success} 份 TXT。")
                        if (noText > 0) append("\n${noText} 份 PDF 未提取到文字（可能是扫描图片）。")
                        if (failures.isNotEmpty()) append("\n失败：${failures.joinToString("；")}")
                        append("\n保存位置：台本TXT 文件夹。")
                    }
                    status.text = detail
                    convertButton.isEnabled = true
                }
            } catch (e: Exception) {
                mainHandler.post {
                    status.text = "处理失败：${e.message ?: "未知错误"}"
                    convertButton.isEnabled = true
                }
            }
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

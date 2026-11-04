package com.bingchen.scripttext

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("script_text_extractor", MODE_PRIVATE) }
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var treeUri: Uri? = null
    private val allFiles = linkedMapOf<String, DocumentFile>()
    private val selectedKeys = linkedSetOf<String>()
    private val filePaths = mutableMapOf<String, String>()
    private var includePageMarkers = true
    private var menuExpanded = false

    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var pageMarkerCheck: CheckBox
    private lateinit var selectAllLabel: TextView
    private lateinit var fabMenu: LinearLayout
    private lateinit var mainFab: TextView
    private lateinit var convertAction: TextView

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
                if (file != null) {
                    val key = uri.toString()
                    allFiles[key] = file
                    selectedKeys.add(key)
                }
            }
            status.text = "已添加 " + uris.size + " 份 PDF，当前选择 " + selectedKeys.size + " 份。"
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
        val frame = FrameLayout(this)
        scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(112))
            setBackgroundColor(0xFFFFFCF8.toInt())
        }
        scroll.addView(root)
        frame.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(frame)

        root.addView(TextView(this).apply {
            text = "日文台本提取器"
            textSize = 27f
            setTextColor(0xFF344B50.toInt())
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "PDF の文字を UTF-8 の TXT に。すべて端末内で処理します。"
            textSize = 13f
            setTextColor(0xFF758487.toInt())
            setPadding(0, dp(7), 0, dp(10))
        })
        status = TextView(this).apply {
            text = "请选择台本文件夹或单独选择 PDF。"
            textSize = 13f
            setTextColor(0xFF758487.toInt())
            setPadding(0, dp(3), 0, dp(12))
        }
        root.addView(status)
        root.addView(TextView(this).apply {
            text = "PDF 文件列表"
            textSize = 18f
            setTextColor(0xFF344B50.toInt())
            setPadding(0, 0, 0, dp(9))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        renderList()

        fabMenu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            visibility = View.GONE
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        addMenuAction(fabMenu, "选择台本文件夹并扫描", "▣") { folderPicker.launch(treeUri) }
        addMenuAction(fabMenu, "单独选择 PDF（可多选）", "＋") { pdfPicker.launch(arrayOf("application/pdf")) }

        val markerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(5), dp(10), dp(5))
            background = rounded(0xFFFFFCF8.toInt(), 20)
            isClickable = true
            isFocusable = true
            setOnClickListener { pageMarkerCheck.isChecked = !pageMarkerCheck.isChecked }
        }
        pageMarkerCheck = CheckBox(this).apply {
            isChecked = true
            buttonTintList = android.content.res.ColorStateList.valueOf(0xFF269EAA.toInt())
            setOnCheckedChangeListener { _, checked -> includePageMarkers = checked }
        }
        markerRow.addView(pageMarkerCheck)
        markerRow.addView(TextView(this).apply {
            text = "在 TXT 中标记 PDF 页码分隔"
            textSize = 13f
            setTextColor(0xFF344B50.toInt())
        })
        fabMenu.addView(markerRow)
        selectAllLabel = addMenuAction(fabMenu, "全选", "☑") { toggleSelectAll() }
        addMenuAction(fabMenu, "回顶", "↑") { scroll.smoothScrollTo(0, 0) }
        convertAction = addMenuAction(fabMenu, "提取并保存 TXT", "↓") { convertSelected() }

        mainFab = TextView(this).apply {
            text = "＋"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(0xFF35AABD.toInt(), 100)
            elevation = dp(8).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { setMenuExpanded(!menuExpanded) }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(fabMenu)
            addView(mainFab, LinearLayout.LayoutParams(dp(58), dp(58)).apply { topMargin = dp(5) })
        }
        frame.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.BOTTOM).apply {
            setMargins(0, 0, dp(16), dp(18))
        })
    }

    private fun addMenuAction(parent: LinearLayout, label: String, symbol: String, action: () -> Unit): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(0, dp(3), 0, dp(3))
        }
        val text = TextView(this).apply {
            this.text = label
            textSize = 13f
            setTextColor(0xFF344B50.toInt())
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rounded(0xFFFFFCF8.toInt(), 20)
            elevation = dp(3).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        val icon = TextView(this).apply {
            this.text = symbol
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(0xFF35AABD.toInt(), 100)
            elevation = dp(4).toFloat()
        }
        row.addView(text)
        row.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(9) })
        parent.addView(row)
        return text
    }

    private fun setMenuExpanded(expanded: Boolean) {
        menuExpanded = expanded
        fabMenu.visibility = if (expanded) View.VISIBLE else View.GONE
        mainFab.text = if (expanded) "×" else "＋"
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
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
                        val next = path + "/" + name
                        if (file.isDirectory) walk(file, next)
                        else if (name.endsWith(".pdf", ignoreCase = true)) found.add(file to next)
                    }
                }
                walk(tree, tree.name ?: "已选文件夹")
                mainHandler.post {
                    allFiles.clear()
                    selectedKeys.clear()
                    filePaths.clear()
                    found.forEach {
                        val key = it.first.uri.toString()
                        allFiles[key] = it.first
                        selectedKeys.add(key)
                        filePaths[key] = it.second
                    }
                    status.text = "扫描完成：找到 " + found.size + " 份 PDF，已全部勾选。"
                    updateSelectAllLabel()
                    renderList()
                }
            } catch (e: Exception) {
                mainHandler.post { status.text = "扫描失败：" + (e.message ?: "未知错误") }
            }
        }
    }

    private fun renderList() {
        list.removeAllViews()
        if (allFiles.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "暂时没有 PDF 文件。"
                setTextColor(0xFF758487.toInt())
                setPadding(0, dp(5), 0, dp(12))
            })
            updateSelectAllLabel()
            return
        }
        allFiles.forEach { (key, file) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = rounded(0xFFF3F0E9.toInt(), 12)
            }
            val check = CheckBox(this).apply {
                text = file.name ?: "未命名 PDF"
                isChecked = selectedKeys.contains(key)
                setTextColor(0xFF344B50.toInt())
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFF269EAA.toInt())
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedKeys.add(key) else selectedKeys.remove(key)
                    status.text = "已选择 " + selectedKeys.size + " / " + allFiles.size + " 份 PDF。"
                    updateSelectAllLabel()
                }
            }
            row.addView(check, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            filePaths[key]?.let { path ->
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
        updateSelectAllLabel()
    }

    private fun toggleSelectAll() {
        if (allFiles.isEmpty()) {
            Toast.makeText(this, "当前没有 PDF 文件。", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedKeys.size == allFiles.size) selectedKeys.clear() else selectedKeys.addAll(allFiles.keys)
        status.text = if (selectedKeys.isEmpty()) "已取消全部选择。" else "已选择全部 " + selectedKeys.size + " 份 PDF。"
        renderList()
    }

    private fun updateSelectAllLabel() {
        if (::selectAllLabel.isInitialized) {
            selectAllLabel.text = if (allFiles.isNotEmpty() && selectedKeys.size == allFiles.size) "取消全选" else "全选"
        }
    }

    private fun convertSelected() {
        val files = selectedKeys.mapNotNull { allFiles[it] }
        if (files.isEmpty()) {
            Toast.makeText(this, "请先选择 PDF 文件。", Toast.LENGTH_SHORT).show()
            return
        }
        val outputRoot = treeUri?.let { DocumentFile.fromTreeUri(this, it) }
        if (outputRoot == null) {
            Toast.makeText(this, "请先选择一个输出文件夹。", Toast.LENGTH_LONG).show()
            return
        }
        convertAction.isEnabled = false
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
                            var outputName = base + ".txt"
                            var suffix = 2
                            while (outputDir.findFile(outputName) != null) {
                                outputName = base + "(" + suffix + ").txt"
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
                        failures.add(name + "：" + (e.message ?: "转换失败"))
                    }
                }
                mainHandler.post {
                    val detail = buildString {
                        append("完成：成功生成 " + success + " 份 TXT。")
                        if (noText > 0) append("\n" + noText + " 份 PDF 未提取到文字（可能是扫描图片）。")
                        if (failures.isNotEmpty()) append("\n失败：" + failures.joinToString("；"))
                        append("\n保存位置：台本TXT 文件夹。")
                    }
                    status.text = detail
                    convertAction.isEnabled = true
                    setMenuExpanded(false)
                    scroll.smoothScrollTo(0, 0)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    status.text = "处理失败：" + (e.message ?: "未知错误")
                    convertAction.isEnabled = true
                    scroll.smoothScrollTo(0, 0)
                }
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

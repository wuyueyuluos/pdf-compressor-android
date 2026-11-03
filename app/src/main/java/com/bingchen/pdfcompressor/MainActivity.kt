package com.bingchen.pdfcompressor

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.documentfile.provider.DocumentFile

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("pdf_compressor", MODE_PRIVATE) }
    private var treeUri: Uri? = null
    private var selectedUri: Uri? = null
    private var selectedName: String? = null
    private var level = 1
    private lateinit var root: LinearLayout
    private lateinit var fileList: LinearLayout
    private lateinit var status: TextView
    private lateinit var levelLabel: TextView

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
        button("压缩所选文件") {
            status.text = "第一阶段已完成文件选择与扫描界面；压缩引擎正在接入，暂时不会修改你的原文件。"
        }
        root.addView(TextView(this).apply {
            text = "提示：扫描列表会显示完整来源路径；压缩文件将统一放入所选文件夹内的「PDF压缩」目录，原文件不会被覆盖。"
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

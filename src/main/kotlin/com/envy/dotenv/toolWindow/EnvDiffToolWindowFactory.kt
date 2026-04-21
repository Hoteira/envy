package com.envy.dotenv.toolWindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.envy.dotenv.services.EnvFileService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import com.intellij.openapi.ui.ComboBox

class EnvDiffToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = EnvDiffPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "Env Diff", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

class EnvDiffPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val service = project.getService(EnvFileService::class.java)
    private val leftCombo = ComboBox<String>()
    private val rightCombo = ComboBox<String>()
    private val tableModel = DefaultTableModel(arrayOf("Key", "Left", "Right", "Status"), 0)
    private val table = JBTable(tableModel)
    private var envFiles = mapOf<String, Map<String, String>>()
    private var loading = false
    private var pendingLoad: java.util.concurrent.Future<*>? = null
    @Volatile private var disposed = false

    private val rowStatuses = mutableListOf<RowStatus>()

    private val differBg = JBColor(Color(255, 255, 180), Color(80, 80, 0))
    private val differFg = JBColor(Color(60, 60, 0), Color.WHITE)
    private val missingBg = JBColor(Color(255, 220, 220), Color(80, 0, 0))
    private val missingFg = JBColor(Color(100, 0, 0), Color.WHITE)

    enum class RowStatus { MATCH, DIFFER, MISSING_LEFT, MISSING_RIGHT }

    init {
        val topBar = JPanel()
        topBar.add(JLabel("Compare:"))
        topBar.add(leftCombo)
        topBar.add(JLabel("with:"))
        topBar.add(rightCombo)

        val refreshButton = JButton("Refresh")
        refreshButton.addActionListener { loadFiles() }
        topBar.add(refreshButton)

        add(topBar, BorderLayout.NORTH)

        table.setDefaultEditor(Any::class.java, null)
        // NEXT_COLUMN: dragging a divider resizes only the two columns adjacent to it.
        // All other columns stay exactly where they are. Table always fills the viewport.
        table.autoResizeMode = JTable.AUTO_RESIZE_NEXT_COLUMN
        table.rowHeight = 24

        val renderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable, value: Any?, isSelected: Boolean,
                hasFocus: Boolean, row: Int, column: Int
            ): Component {
                val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                if (!isSelected && row < rowStatuses.size) {
                    when (rowStatuses[row]) {
                        RowStatus.MATCH -> {
                            comp.background = table.background
                            comp.foreground = table.foreground
                        }
                        RowStatus.DIFFER -> {
                            comp.background = differBg
                            comp.foreground = differFg
                        }
                        RowStatus.MISSING_LEFT, RowStatus.MISSING_RIGHT -> {
                            comp.background = missingBg
                            comp.foreground = missingFg
                        }
                    }
                }
                return comp
            }
        }
        table.setDefaultRenderer(Any::class.java, renderer)

        add(JScrollPane(table), BorderLayout.CENTER)

        leftCombo.addActionListener { if (!loading) updateDiff() }
        rightCombo.addActionListener { if (!loading) updateDiff() }

        loadFiles()
    }

    private fun loadFiles() {
        pendingLoad?.cancel(false)
        loading = true
        pendingLoad = com.intellij.openapi.application.ReadAction.nonBlocking(java.util.concurrent.Callable {
            val files = service.findEnvFiles()
            val baseDir = project.guessProjectDir()
            files.associate { file ->
                val relativePath = if (baseDir != null) {
                    file.path.removePrefix(baseDir.path).removePrefix("/")
                } else {
                    file.name
                }
                relativePath to service.parseEnvFile(file)
            }
        })
            .expireWith(this)
        .finishOnUiThread(com.intellij.openapi.application.ModalityState.defaultModalityState()) { result ->
            if (disposed || project.isDisposed || !isDisplayable) return@finishOnUiThread
            envFiles = result
            leftCombo.removeAllItems()
            rightCombo.removeAllItems()

            for (name in envFiles.keys) {
                leftCombo.addItem(name)
                rightCombo.addItem(name)
            }

            if (envFiles.size >= 2) {
                leftCombo.selectedIndex = 0
                rightCombo.selectedIndex = 1
            }
            loading = false
            pendingLoad = null
            updateDiff()
        }
        .submit(com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService())
    }

    private fun updateDiff() {
        val leftName = leftCombo.selectedItem as? String ?: return
        val rightName = rightCombo.selectedItem as? String ?: return
        val leftMap = envFiles[leftName] ?: return
        val rightMap = envFiles[rightName] ?: return

        tableModel.rowCount = 0
        rowStatuses.clear()

        val allKeys = (leftMap.keys + rightMap.keys).toSortedSet()
        val rowData = Array(allKeys.size) { emptyArray<Any>() }
        var rowIndex = 0

        for (key in allKeys) {
            val leftVal = leftMap[key]
            val rightVal = rightMap[key]

            val status = when {
                leftVal == null -> RowStatus.MISSING_LEFT
                rightVal == null -> RowStatus.MISSING_RIGHT
                leftVal == rightVal -> RowStatus.MATCH
                else -> RowStatus.DIFFER
            }

            val statusText = when (status) {
                RowStatus.MATCH -> "OK"
                RowStatus.DIFFER -> "DIFFERS"
                RowStatus.MISSING_LEFT, RowStatus.MISSING_RIGHT -> "MISSING"
            }

            rowData[rowIndex++] = arrayOf(
                key,
                leftVal ?: "-",
                rightVal ?: "-",
                statusText
            )
            rowStatuses.add(status)
        }

        tableModel.setDataVector(rowData, arrayOf("Key", leftName, rightName, "Status"))
        table.tableHeader?.repaint()
    }

    override fun dispose() {
        disposed = true
        pendingLoad?.cancel(true)
        pendingLoad = null
        envFiles = emptyMap()
        rowStatuses.clear()
    }
}

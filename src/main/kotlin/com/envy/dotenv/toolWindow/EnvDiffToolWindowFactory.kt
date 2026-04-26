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

    private val differBg = JBColor(Color(255, 255, 180), Color(80, 80, 0))
    private val differFg = JBColor(Color(60, 60, 0), Color.WHITE)
    private val missingBg = JBColor(Color(255, 220, 220), Color(80, 0, 0))
    private val missingFg = JBColor(Color(100, 0, 0), Color.WHITE)

    private companion object {
        const val STATUS_OK = "OK"
        const val STATUS_DIFFERS = "DIFFERS"
        const val STATUS_MISSING = "MISSING"
    }

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
                if (!isSelected) {
                    val statusCol = table.columnCount - 1
                    val modelRow = table.convertRowIndexToModel(row)
                    val status = table.model.getValueAt(modelRow, statusCol) as? String
                    when (status) {
                        STATUS_DIFFERS -> {
                            comp.background = differBg
                            comp.foreground = differFg
                        }
                        STATUS_MISSING -> {
                            comp.background = missingBg
                            comp.foreground = missingFg
                        }
                        else -> {
                            comp.background = table.background
                            comp.foreground = table.foreground
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
        loading = true
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            if (disposed || project.isDisposed) return@executeOnPooledThread
            
            val result = com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction(com.intellij.openapi.util.Computable {
                if (project.isDisposed) return@Computable emptyMap<String, Map<String, String>>()
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

            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
                if (disposed || project.isDisposed || !isDisplayable) return@invokeLater
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
                updateDiff()
            }, com.intellij.openapi.application.ModalityState.defaultModalityState())
        }
    }

    private fun updateDiff() {
        val leftName = leftCombo.selectedItem as? String ?: return
        val rightName = rightCombo.selectedItem as? String ?: return
        val leftMap = envFiles[leftName] ?: return
        val rightMap = envFiles[rightName] ?: return

        tableModel.rowCount = 0

        val allKeys = (leftMap.keys + rightMap.keys).toSortedSet()
        val rowData = Array(allKeys.size) { emptyArray<Any>() }
        var rowIndex = 0

        for (key in allKeys) {
            val leftVal = leftMap[key]
            val rightVal = rightMap[key]

            val statusText = when {
                leftVal == null || rightVal == null -> STATUS_MISSING
                leftVal == rightVal -> STATUS_OK
                else -> STATUS_DIFFERS
            }

            rowData[rowIndex++] = arrayOf(
                key,
                leftVal ?: "-",
                rightVal ?: "-",
                statusText
            )
        }

        tableModel.setDataVector(rowData, arrayOf("Key", leftName, rightName, "Status"))
        table.tableHeader?.repaint()
    }

    override fun dispose() {
        disposed = true
        pendingLoad = null
        envFiles = emptyMap()
    }
}

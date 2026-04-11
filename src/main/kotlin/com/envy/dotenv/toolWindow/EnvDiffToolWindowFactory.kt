package com.envy.dotenv.toolWindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.envy.dotenv.services.EnvFileService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

class EnvDiffToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = EnvDiffPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "Env Diff", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

class EnvDiffPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val service = project.getService(EnvFileService::class.java)
    private val leftCombo = JComboBox<String>()
    private val rightCombo = JComboBox<String>()
    private val tableModel = DefaultTableModel(arrayOf("Key", "Left", "Right", "Status"), 0)
    private val table = JBTable(tableModel)
    private var envFiles = mapOf<String, Map<String, String>>()
    private var loading = false

    // Row status for coloring
    private val rowStatuses = mutableListOf<RowStatus>()

    enum class RowStatus { MATCH, DIFFER, MISSING_LEFT, MISSING_RIGHT }

    init {
        // Top bar with dropdowns
        val topBar = JPanel()
        topBar.add(JLabel("Compare:"))
        topBar.add(leftCombo)
        topBar.add(JLabel("with:"))
        topBar.add(rightCombo)

        val refreshButton = JButton("Refresh")
        refreshButton.addActionListener { loadFiles() }
        topBar.add(refreshButton)

        add(topBar, BorderLayout.NORTH)

        // Table setup
        table.setDefaultEditor(Any::class.java, null) // read-only
        table.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        table.rowHeight = 24

        // Custom renderer for row colors
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
                            comp.background = Color(80, 80, 0)
                            comp.foreground = Color.WHITE
                        }
                        RowStatus.MISSING_LEFT, RowStatus.MISSING_RIGHT -> {
                            comp.background = Color(80, 0, 0)
                            comp.foreground = Color.WHITE
                        }
                    }
                }
                return comp
            }
        }
        table.setDefaultRenderer(Any::class.java, renderer)

        add(JScrollPane(table), BorderLayout.CENTER)

        // Listen for dropdown changes
        leftCombo.addActionListener { if (!loading) updateDiff() }
        rightCombo.addActionListener { if (!loading) updateDiff() }

        // Load on init
        loadFiles()
    }

    private fun loadFiles() {
        loading = true
        val files = service.findEnvFiles()
        val baseDir = project.guessProjectDir()
        envFiles = files.associate { file ->
            val relativePath = if (baseDir != null) {
                file.path.removePrefix(baseDir.path).removePrefix("/")
            } else {
                file.name
            }
            relativePath to service.parseEnvFile(file)
        }

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
    }

    private fun updateDiff() {
        val leftName = leftCombo.selectedItem as? String ?: return
        val rightName = rightCombo.selectedItem as? String ?: return
        val leftMap = envFiles[leftName] ?: return
        val rightMap = envFiles[rightName] ?: return

        // Clear table
        tableModel.rowCount = 0
        rowStatuses.clear()

        // Collect all keys from both files
        val allKeys = (leftMap.keys + rightMap.keys).toSortedSet()

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

            tableModel.addRow(arrayOf(
                key,
                leftVal ?: "-",
                rightVal ?: "-",
                statusText
            ))
            rowStatuses.add(status)
        }

        // Update column headers
        tableModel.setColumnIdentifiers(arrayOf("Key", leftName, rightName, "Status"))
    }
}
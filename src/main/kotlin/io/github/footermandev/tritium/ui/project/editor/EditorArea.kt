package io.github.footermandev.tritium.ui.project.editor

import io.github.footermandev.tritium.core.project.ProjectBase
import io.github.footermandev.tritium.extension.core.BuiltinRegistries
import io.github.footermandev.tritium.io.VPath
import io.github.footermandev.tritium.logger
import io.github.footermandev.tritium.registry.DeferredRegistryBuilder
import io.github.footermandev.tritium.ui.theme.TColors
import io.github.footermandev.tritium.ui.theme.qt.setThemedStyle
import io.github.footermandev.tritium.ui.widgets.constructor_functions.vBoxLayout
import io.qt.gui.QIcon
import io.qt.widgets.QFrame
import io.qt.widgets.QStackedWidget
import io.qt.widgets.QTextEdit
import io.qt.widgets.QWidget

/**
 * This is the main Editor area of [io.github.footermandev.tritium.ui.project.ProjectViewWindow],
 * which includes the Tab Bar, Code Editor, and handles opening files.
 */
class EditorArea(
    private val project: ProjectBase
) {
    private val container = QWidget()
    private val mainLayout = vBoxLayout(container)
    private val tabBar = EditorTabBar()
    private val stack = QStackedWidget()
    private val paneIdx = mutableMapOf<Int, EditorPane>()
    private val providerRegistry = BuiltinRegistries.EditorPane
    private val syntaxRegistry = BuiltinRegistries.SyntaxLanguage
    private var providersSnapshot: List<EditorPaneProvider> = emptyList()

    private val logger = logger()

    init {
        container.objectName = "editorArea"
        stack.objectName = "editorStack"
        tabBar.apply {
            onTabCloseRequest = { idx -> closeTab(idx) }
            onCurrentChanged = { idx -> onTabSelected(idx) }
        }
        container.setThemedStyle {
            val editorSurface = TColors.Surface1
            selector("#editorArea") {
                backgroundColor(editorSurface)
                border()
            }
            selector("#editorStack") {
                backgroundColor(editorSurface)
                border()
            }
        }
        mainLayout.setContentsMargins(0, 0, 0, 0)
        mainLayout.setSpacing(0)
        stack.frameShape = QFrame.Shape.NoFrame
        mainLayout.addWidget(tabBar)
        mainLayout.addWidget(stack)
        DeferredRegistryBuilder(providerRegistry) { list ->
            providersSnapshot = list.sortedBy { it.order }
        }
    }

    fun widget(): QWidget = container

    fun openFile(file: VPath): EditorPane {
        val absolute = file.toAbsolute()
        val existing = paneIdx.entries.firstOrNull { it.value.file.toAbsolute() == absolute }

        val fileIcon = resolveFileIcon(file, project)

        if(existing != null) {
            val idx = existing.key
            tabBar.setCurrentIndex(idx)
            stack.currentIndex  = idx
            return existing.value
        }

        val chosen = providersSnapshot.firstOrNull { it.canOpen(file, project) }
        val pane = chosen?.create(project, file) ?: run {
            val lang = syntaxRegistry.all().find { it.matches(file) }
            TextEditorPane(project, file, lang)
        }
        val w = pane.widget()
        val idx = stack.addWidget(w)
        paneIdx[idx] = pane
        tabBar.insertTab(idx, fileIcon, file.fileName())
        tabBar.setCurrentIndex(idx)
        stack.currentIndex  = idx
        pane.onOpen()
        return pane
    }

    private fun defaultTextPane(project: ProjectBase, file: VPath): EditorPane = object : EditorPane(project, file) {
        private val text = QTextEdit()

        init {
            text.lineWrapMode = QTextEdit.LineWrapMode.NoWrap
            text.frameShape = QFrame.Shape.NoFrame
            text.viewport()?.setContentsMargins(0, 0, 0, 0)
            try { if(file.exists()) text.plainText = file.readTextOr("") } catch (_: Throwable) {}
        }

        override fun widget(): QWidget = text
        override suspend fun save(): Boolean = try {
            file.writeBytes(text.toPlainText().toByteArray())
            true
        } catch (t: Throwable) {
            logger.warn("DefaultTextPane save failed for {}", file.toAbsolute(), t)
            false
        }
    }

    fun closeTab(idx: Int) {
        val pane = paneIdx[idx] ?: return
        pane.onClose()
        val w = stack.widget(idx)
        stack.removeWidget(w)
        tabBar.removeTab(idx)
        paneIdx.remove(idx)
        rebuild()
    }

    private fun rebuild() {
        val new = HashMap<Int, EditorPane>()
        for(i in 0 until stack.count) {
            val w = stack.widget(i)
            val pane = paneIdx.values.find { it.widget() == w }
            if(pane != null) new[i] = pane
        }
        paneIdx.clear()
        paneIdx.putAll(new)
    }

    private fun onTabSelected(idx: Int) {
        if(idx >= 0 && idx < stack.count) stack.currentIndex = idx
    }

    fun openFiles(): List<String> = paneIdx.values.map { it.file.toAbsolute().toString() }

    fun restoreOpenFiles(paths: List<String>) {
        for(p in paths) {
            try {
                val v = VPath.get(p)
                if(v.exists()) openFile(v)
            } catch (_: Throwable) {}
        }
    }

    private fun resolveFileIcon(file: VPath, project: ProjectBase): QIcon? {
        val ftr = BuiltinRegistries.FileType
        val matches = ftr.all().filter { desc -> desc.matches(file, project) }.sortedBy { it.order }
        val primary = matches.firstOrNull()
        return primary?.icon
    }
}

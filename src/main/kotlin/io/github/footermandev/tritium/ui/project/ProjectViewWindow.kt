package io.github.footermandev.tritium.ui.project

import io.github.footermandev.tritium.connect
import io.github.footermandev.tritium.core.project.ProjectBase
import io.github.footermandev.tritium.core.project.ProjectMngr
import io.github.footermandev.tritium.io.VPath
import io.github.footermandev.tritium.logger
import io.github.footermandev.tritium.registry.DeferredRegistryBuilder
import io.github.footermandev.tritium.registry.RegistryMngr
import io.github.footermandev.tritium.ui.helpers.runOnGuiThread
import io.github.footermandev.tritium.ui.project.editor.EditorArea
import io.github.footermandev.tritium.ui.project.menu.MenuItem
import io.github.footermandev.tritium.ui.project.menu.ProjectMenuBar
import io.github.footermandev.tritium.ui.project.sidebar.SidePanelMngr
import io.github.footermandev.tritium.util.ByteUtils
import io.qt.Nullable
import io.qt.core.QByteArray
import io.qt.core.Qt.ItemDataRole.UserRole
import io.qt.gui.QCloseEvent
import io.qt.widgets.QListWidget
import io.qt.widgets.QMainWindow
import io.qt.widgets.QTreeWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * The main window for active Projects.
 */
class ProjectViewWindow internal constructor(
    private val project: ProjectBase
): QMainWindow() {

    private val logger = logger()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; }
    private val tDir: VPath = project.projectDir.resolve(".tr")
    private val stateFile: VPath = tDir.resolve("tritium-ui.json")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val menuBarBuilder = ProjectMenuBar()
    private val editorArea = EditorArea(project)
    private val sidePanelMngr = SidePanelMngr(project, this) { id, dock ->
        if(id == "project_files") {
            val widget = dock.widget()
            val tree = (widget as? QTreeWidget) ?: widget?.findChild(QTreeWidget::class.java)

            tree?.itemDoubleClicked?.connect { item, _ ->
                val path = item?.data(0, UserRole) as? VPath
                if(path != null && !path.isDir()) {
                    editorArea.openFile(path)
                }
            }
        }
    }

    private var uiState: ProjectUIState = loadState()

    private val menuItemsRegistry = RegistryMngr.getOrCreateRegistry<MenuItem>("ui.menu")

    init {
        windowTitle = "Tritium | " + project.name
        menuBarBuilder.attach(this)

        setCentralWidget(editorArea.widget())
        applyState(uiState)

        DeferredRegistryBuilder(menuItemsRegistry) {
            runOnGuiThread {
                menuBarBuilder.rebuildFor(this, project, null)
            }
        }
    }

    private fun ensureTDir() {
        if(!tDir.exists()) tDir.mkdirs()
    }

    private fun applyState(state: ProjectUIState) {
        try {
            var restored = false
            state.mainWindowGeometry?.let {
                if(restoreGeometry(QByteArray(state.mainWindowGeometry))) {
                    restored = true
                }
            }
            state.mainWindowState?.let { restoreState(QByteArray(state.mainWindowState)) }

            if(!restored) {
                resize(1280, 720)
            }

            editorArea.restoreOpenFiles(state.openFiles)
        } catch (t: Throwable) {
            logger.warn("Failed to apply UI state for '{}'", project.name, t)
            resize(1280, 720)
        }
    }

    private fun loadState(): ProjectUIState {
        return try {
            ensureTDir()
            if (!stateFile.exists()) return ProjectUIState()
            val txt = stateFile.readTextOrNull() ?: return ProjectUIState()
            return json.decodeFromString<ProjectUIState>(txt)
        } catch (t: Throwable) {
            logger.warn("Failed to load UI state for {}", project.name, t)
            ProjectUIState()
        }
    }

    private fun persistStateAsync() {
        scope.launch(Dispatchers.IO) {
            try {
                ensureTDir()
                val openFiles = editorArea.openFiles()
                val geom = ByteUtils.toByteArray(saveGeometry().data())
                val state = ByteUtils.toByteArray(saveState().data())
                val s = ProjectUIState(openFiles = openFiles, mainWindowState = state, mainWindowGeometry = geom)
                val txt = json.encodeToString(s)
                stateFile.writeBytes(txt.toByteArray())
            } catch (t: Throwable) {
                logger.warn("Failed to persist UI state for '{}'", project.name, t)
            }
        }
    }

    override fun closeEvent(event: @Nullable QCloseEvent?) {
        persistStateAsync()
        super.closeEvent(event)
    }

    fun rebuildMenus() { menuBarBuilder.rebuildFor(this, project, null) }

    companion object {
        private val logger = logger(ProjectViewWindow::class)

        fun dashboardList(
            list: QListWidget,
            openWindow: (ProjectBase) -> Unit = { p -> ProjectViewWindow(p).apply { show() } }
        ) {
            list.itemDoubleClicked.connect { item ->
                val name = item?.text() ?: return@connect
                val proj = ProjectMngr.getProject(name)
                if(proj != null) {
                    openWindow(proj)
                } else {
                    logger.warn("Dashboard requested open for unknown project '{}'", name)
                }
            }
        }
    }
}

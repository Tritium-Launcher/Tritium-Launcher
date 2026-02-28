package io.github.footermandev.tritium.ui.project

import io.github.footermandev.tritium.connect
import io.github.footermandev.tritium.core.project.ProjectBase
import io.github.footermandev.tritium.core.project.ProjectMngr
import io.github.footermandev.tritium.extension.core.BuiltinRegistries
import io.github.footermandev.tritium.extension.core.CoreSettingValues
import io.github.footermandev.tritium.io.VPath
import io.github.footermandev.tritium.logger
import io.github.footermandev.tritium.platform.GameLauncher
import io.github.footermandev.tritium.registry.DeferredRegistryBuilder
import io.github.footermandev.tritium.ui.helpers.runOnGuiThread
import io.github.footermandev.tritium.ui.notifications.NotificationLink
import io.github.footermandev.tritium.ui.notifications.NotificationMngr
import io.github.footermandev.tritium.ui.notifications.NotificationRenderContext
import io.github.footermandev.tritium.ui.notifications.Toaster
import io.github.footermandev.tritium.ui.project.editor.EditorArea
import io.github.footermandev.tritium.ui.project.editor.pane.SettingsEditorPane
import io.github.footermandev.tritium.ui.project.menu.ProjectMenuBar
import io.github.footermandev.tritium.ui.project.sidebar.SidePanelMngr
import io.github.footermandev.tritium.ui.settings.SettingsLink
import io.github.footermandev.tritium.ui.theme.TColors
import io.github.footermandev.tritium.ui.theme.TIcons
import io.github.footermandev.tritium.ui.theme.qt.icon
import io.github.footermandev.tritium.ui.theme.qt.setThemedStyle
import io.github.footermandev.tritium.ui.widgets.constructor_functions.label
import io.github.footermandev.tritium.ui.widgets.constructor_functions.vBoxLayout
import io.github.footermandev.tritium.ui.widgets.constructor_functions.widget
import io.github.footermandev.tritium.util.ByteUtils
import io.qt.Nullable
import io.qt.core.QByteArray
import io.qt.core.QTimer
import io.qt.core.Qt.ItemDataRole.UserRole
import io.qt.gui.QAction
import io.qt.gui.QCloseEvent
import io.qt.gui.QResizeEvent
import io.qt.gui.QShowEvent
import io.qt.widgets.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.random.Random

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
    private val defaultWindowSize: Pair<Int, Int> = CoreSettingValues.projectWindowDefaultSize()

    private val menuBarBuilder = ProjectMenuBar()
    private val menuBottomDivider = widget(this) {
        objectName = "projectMenuBottomDivider"
        setAttribute(io.qt.core.Qt.WidgetAttribute.WA_TransparentForMouseEvents, true)
        setThemedStyle {
            selector("#projectMenuBottomDivider") {
                backgroundColor(TColors.Surface2)
                border()
            }
        }
        hide()
    }
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
    private lateinit var notificationOverlay: Toaster

    private var uiState: ProjectUIState = loadState()
    private var unsubscribeGameProcessListener: (() -> Unit)? = null
    private var unsubscribeRuntimePreparationListener: (() -> Unit)? = null
    private var unsubscribeTaskListener: (() -> Unit)? = null

    private val menuItemsRegistry = BuiltinRegistries.MenuItem

    init {
        windowTitle = "Tritium | " + project.name
        menuBarBuilder.attach(this)

        setCentralWidget(editorArea.widget())
        notificationOverlay = Toaster(project, this)
        QTimer.singleShot(0) { updateMenuBottomDivider() }
        applyState(uiState)
        installNotificationTestShortcut()

        DeferredRegistryBuilder(menuItemsRegistry) {
            runOnGuiThread {
                menuBarBuilder.rebuildFor(this, project, null)
            }
        }

        unsubscribeGameProcessListener = GameLauncher.addGameProcessListener {
            runOnGuiThread {
                if (!isVisible) return@runOnGuiThread
                rebuildMenus()
            }
        }
        unsubscribeRuntimePreparationListener = GameLauncher.addRuntimePreparationListener {
            runOnGuiThread {
                if (!isVisible) return@runOnGuiThread
                rebuildMenus()
            }
        }
        unsubscribeTaskListener = ProjectTaskMngr.addListener {
            runOnGuiThread {
                if (!isVisible) return@runOnGuiThread
                rebuildMenus()
            }
        }

        destroyed.connect {
            unsubscribeGameProcessListener?.invoke()
            unsubscribeGameProcessListener = null
            unsubscribeRuntimePreparationListener?.invoke()
            unsubscribeRuntimePreparationListener = null
            unsubscribeTaskListener?.invoke()
            unsubscribeTaskListener = null
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
                resize(defaultWindowSize.first, defaultWindowSize.second)
            }

            editorArea.restoreOpenFiles(state.openFiles)
        } catch (t: Throwable) {
            logger.warn("Failed to apply UI state for '{}'", project.name, t)
            resize(defaultWindowSize.first, defaultWindowSize.second)
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
                stateFile.writeBytesAtomic(txt.toByteArray())
            } catch (t: Throwable) {
                logger.warn("Failed to persist UI state for '{}'", project.name, t)
            }
        }
    }

    private fun installNotificationTestShortcut() {
        val action = QAction(this).apply {
            setShortcut("Ctrl+Alt+Shift+N") //TODO: Keymap
            toolTip = "Emit a random notification test payload"
        }
        action.triggered.connect {
            emitRandomTestNotification()
        }
        addAction(action)
    }

    private fun emitRandomTestNotification() {
        val seed = Random.nextInt(1000, 9999)
        val header = listOf(
            "Test Notification #$seed"
        ).random()
        val description = listOf(
            "Description."
        ).random()

        val icon = listOf(
            TIcons.QuestionMark.icon,
            TIcons.Build.icon,
            TIcons.Run.icon,
            TIcons.Tritium.icon
        ).random()

        val links: List<NotificationLink>? = if (Random.nextInt(100) < 70) {
            listOf(
                listOf(
                    NotificationLink("HTTP Link", "https://github.com/")
                ).random()
            )
        } else {
            null
        }

        val customWidgetFactory: ((NotificationRenderContext) -> QWidget)? = if (Random.nextInt(100) < 55) {
            { _: NotificationRenderContext ->
                QWidget().apply {
                    objectName = "notificationTestCustomWidget"
                    val progressValue = Random.nextInt(5, 100)
                    val layout = vBoxLayout(this) {
                        setContentsMargins(0, 4, 0, 0)
                        setSpacing(3)
                    }
                    layout.addWidget(label("Custom widget payload: $progressValue%"))
                    layout.addWidget(QProgressBar().apply {
                        setRange(0, 100)
                        value = progressValue
                        textVisible = false
                        maximumHeight = 8
                    })
                }
            }
        } else {
            null
        }

        NotificationMngr.post(
            id = "generic",
            project = project,
            header = header,
            description = description,
            icon = icon,
            links = links,
            customWidgetFactory = customWidgetFactory,
            metadata = mapOf(
                "source" to "project_hotkey",
                "seed" to seed.toString()
            )
        )
    }

    override fun showEvent(event: @Nullable QShowEvent?) {
        super.showEvent(event)
        updateMenuBottomDivider()
        if(::notificationOverlay.isInitialized) notificationOverlay.reposition()
    }

    override fun resizeEvent(event: @Nullable QResizeEvent?) {
        super.resizeEvent(event)
        updateMenuBottomDivider()
        if(::notificationOverlay.isInitialized) notificationOverlay.reposition()
    }

    override fun closeEvent(event: @Nullable QCloseEvent?) {
        persistStateAsync()
        super.closeEvent(event)
    }

    fun rebuildMenus() {
        menuBarBuilder.rebuildFor(this, project, null)
        QTimer.singleShot(0) { updateMenuBottomDivider() }
    }

    /**
     * Opens the project settings editor tab and optionally focuses [link].
     *
     * @param link Optional target setting deep link.
     */
    fun openSettings(link: SettingsLink? = null) {
        val pane = editorArea.openFile(project.projectDir.resolve(".tr/settings.conf"))
        if (link != null && pane is SettingsEditorPane) {
            pane.openLink(link)
        }
    }

    private fun updateMenuBottomDivider() {
        val menu = menuWidget() ?: run {
            menuBottomDivider.hide()
            return
        }
        val menuRect = menu.geometry
        if(menuRect.height() <= 0 || width() <= 0) {
            menuBottomDivider.hide()
            return
        }
        val y = menuRect.y() + menuRect.height() - 1
        menuBottomDivider.setGeometry(0, y, width(), 1)
        menuBottomDivider.show()
        menuBottomDivider.raise()
    }

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

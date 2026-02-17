package io.github.footermandev.tritium.ui.wizard

import io.github.footermandev.tritium.*
import io.github.footermandev.tritium.core.project.ProjectGenerator
import io.github.footermandev.tritium.core.project.ProjectMngr
import io.github.footermandev.tritium.core.project.ProjectType
import io.github.footermandev.tritium.coroutines.UIDispatcher
import io.github.footermandev.tritium.extension.core.BuiltinRegistries
import io.github.footermandev.tritium.io.VPath
import io.github.footermandev.tritium.ui.dashboard.Dashboard
import io.github.footermandev.tritium.ui.theme.TColors
import io.github.footermandev.tritium.ui.theme.qt.setThemedStyle
import io.github.footermandev.tritium.ui.widgets.TPushButton
import io.github.footermandev.tritium.ui.widgets.constructor_functions.hBoxLayout
import io.github.footermandev.tritium.ui.widgets.constructor_functions.vBoxLayout
import io.github.footermandev.tritium.ui.widgets.constructor_functions.widget
import io.qt.core.QSize
import io.qt.core.QTimer
import io.qt.core.Qt
import io.qt.core.Qt.ItemDataRole.UserRole
import io.qt.widgets.*
import kotlinx.coroutines.Job

/**
 * The main window for creating new Projects.
 */
class NewProjectDialog internal constructor(parent: QWidget? = null): QDialog(parent) {
    private val list            = QListWidget()
    private val stacked         = QStackedWidget()
    private val projectName     = QLineEdit()
    private val createButton    = TPushButton()
    private val cancelButton    = TPushButton()
    private val statusLabel     = QLabel()

    private val creator = ProjectGenerator(UIDispatcher)
    private var currentJob: Job? = null
    private val variablesPerType = mutableMapOf<String, MutableMap<String, String>>()
    private val typeById = mutableMapOf<String, ProjectType>()

    private val logger = logger()

    init {
        windowTitle = "New Project"
        minimumWidth = 800
        minimumHeight = 520
        objectName = "ProjectDialog"

        val mainLayout = hBoxLayout(this) {
            widgetSpacing = 0
            contentsMargins = 0.m
        }
        val leftPanel  = widget(this) {
            objectName = "leftPanel"
        }
        val leftLayout = vBoxLayout(leftPanel) {
            contentsMargins = 6.m
            widgetSpacing = 6
        }
        list.apply {
            selectionMode = QAbstractItemView.SelectionMode.SingleSelection
            minimumWidth  = 150
            sizePolicy = QSizePolicy(QSizePolicy.Policy.Preferred, QSizePolicy.Policy.Expanding)
            uniformItemSizes = false
            spacing = 0
            iconSize = qs(40, 40)
            frameShape = QFrame.Shape.NoFrame
            frameShadow = QFrame.Shadow.Plain
        }
        leftLayout.addWidget(list)

        val rightPanel  = widget {
            objectName = "rightPanel"
            autoFillBackground = true
            setAttribute(Qt.WidgetAttribute.WA_StyledBackground, true)
        }
        val rightLayout = vBoxLayout(rightPanel)

        rightLayout.addWidget(stacked, 1)

        statusLabel.text = ""
        rightLayout.addWidget(statusLabel)

        createButton.apply {
            text = "Create"
            minimumHeight = 36
        }
        cancelButton.apply {
            text = "Cancel"
            minimumHeight = 36
        }

        val btnRow = QWidget()
        val btnLayout = hBoxLayout(btnRow) {
            addStretch(1)
            addWidget(createButton)
            addWidget(cancelButton)
        }
        rightLayout.addWidget(btnRow)

        mainLayout.addWidget(leftPanel, 0)
        mainLayout.addWidget(rightPanel, 1)
        setLayout(mainLayout)

        setThemedStyle {
            selector("#ProjectDialog") {
                border(1, TColors.Surface0, "top")
                backgroundColor(TColors.Surface0)
            }

            selector("#leftPanel") {
                border(1, TColors.Surface0, "top")
                border(1, TColors.Surface0, "right")
                padding(0)
                backgroundColor(TColors.Surface0)
            }

            selector("#rightPanel") {
                border(1, TColors.Surface0, "top")
                backgroundColor(TColors.Surface0)
                padding(0)
            }

            selector("QListWidget") {
                border()
                background("transparent")
                padding(6)
            }

            selector("QListView::item") {
                border()
                showDecorationSelected()
                borderRadius(8)
                background("transparent")
                color(TColors.Text)
                padding(6, 8, 6, 10)
            }

            selector("QListView::item:selected") {
                border()
                backgroundColor(TColors.SelectedUI)
                borderRadius(8)
                color(TColors.SelectedText)
            }

        }

        setupTypes()
        connectSignals()
    }

    private fun setupTypes() {
        val types = BuiltinRegistries.ProjectType
        logger.info("Registered Project Types: ${types.toListString()}")

        for(t in types.all()) {
            typeById[t.id] = t
            val item = QListWidgetItem(t.displayName)
            item.setToolTip(t.description)
            item.setData(UserRole, t.id)
            item.setIcon(t.icon.pixmap(32, 32))
            item.setSizeHint(QSize(0, 32))
            list.addItem(item)

            val vars = variablesPerType.getOrPut(t.id) { mutableMapOf() }
            val widget = t.createSetupWidget(null, vars)
            stacked.addWidget(widget)
        }
        if(list.count > 0) {
            list.currentRow = 0
            stacked.currentIndex = 0
        }
    }

    private fun connectSignals() {
        list.currentRowChanged.connect { row ->
            stacked.currentIndex = row
            val item = list.item(row)
            val typeId = item?.data(UserRole) as? String ?: return@connect
            val vars = variablesPerType[typeId]
            vars?.let {
                val suggested = it["packName"] ?: it["defaultName"]
                if(suggested != null && projectName.text.isBlank()) projectName.text = suggested
            }
        }

        cancelButton.onClicked { reject() }

        createButton.onClicked {
            startCreate()
        }
    }

    private fun startCreate() {
        // Cancel any previous run to avoid overlapping UI state.
        currentJob?.cancel()

        val row = list.currentRow
        if(row < 0) {
            statusLabel.text = "No project type selected."
            logger.warn("Create requested but no project type is selected")
            return
        }
        val item = list.item(row)
        val typeId = item?.data(UserRole) as? String ?: run {
            statusLabel.text = "Project type not found."
            logger.warn("Create requested but project type id missing")
            return
        }
        val type = typeById[typeId] ?: run {
            statusLabel.text = "Project type not registered."
            logger.warn("Create requested for unknown project type: {}", typeId)
            return
        }

        val vars = variablesPerType[typeId]?.toMap() ?: emptyMap()
        val merged = vars.toMutableMap()
        if (projectName.text.isNotBlank()) {
            merged["packName"] = projectName.text
        }
        logger.info("Starting create for type={} vars={}", typeId, merged)

        statusLabel.text = "Creating Project..."
        createButton.isEnabled = false
        cancelButton.isEnabled = false
        ProjectMngr.generationActive = true

        currentJob = creator.createProjectAsync(type, merged, onProgress = { msg ->
            logger.info("Project generation progress: {}", msg)
            statusLabel.text = msg
        }) { result ->
            logger.info("Project generation onComplete invoked (success? {})", result.isSuccess)
            createButton.isEnabled = true
            cancelButton.isEnabled = true
            ProjectMngr.generationActive = false
            val jobWas = currentJob
            currentJob = null
            logger.info("Project generation completed (job active? {})", jobWas?.isActive)

            result.fold(
                onSuccess = { res ->
                    if (!res.successful) {
                        statusLabel.text = "Project creation failed."
                        logger.warn("Project template reported failure: {}", res.logs.joinToString("\n"))
                        return@fold
                    }

                    statusLabel.text = "Project created: ${res.projectRoot}"
                    logger.info("Project template success: {}", res.projectRoot)
                    val projectDir = VPath.get(res.projectRoot)
                    val project = try {
                        ProjectMngr.loadProject(projectDir)
                            ?: ProjectMngr.refreshProjects(ProjectMngr.RefreshSource.BACKGROUND)
                                .find { it.projectDir.toAbsolute() == projectDir.toAbsolute() }
                    } catch (t: Throwable) {
                        logger.warn("Failed to load newly created project from {}", projectDir, t)
                        null
                    }

                    if(project == null) {
                        statusLabel.text = "Project created, but failed to load (trproj.json missing?)."
                        finishDialog()
                        return@fold
                    }

                    ProjectMngr.notifyCreatedExternal(project)

                    try {
                        statusLabel.text = "Opening project..."
                        logger.info("Opening project window for {}", project.name)
                        ProjectMngr.openProject(project)
                    } catch (t: Throwable) {
                        logger.warn("Failed to open project window for {}", project.name, t)
                    }

                    try {
                        Dashboard.I?.let { dash ->
                            try { dash.close() } catch (_: Throwable) {}
                        }
                    } catch (t: Throwable) {
                        logger.debug("Failed closing dashboard post-create", t)
                    }

                    finishDialog()
                },
                onFailure = { err ->
                    statusLabel.text = "Project creation failed: ${err.message}"
                    logger.error("Project creation failed", err)
                }
            )
        }

        // Watchdog to log if UI never receives completion.
        QTimer.singleShot(60_000) {
            if (currentJob != null && currentJob?.isCompleted == false) {
                logger.warn("Project creation watchdog: job still running after 60s (active={})", currentJob?.isActive)
                statusLabel.text = "Still creating... (taking longer than expected)"
                createButton.isEnabled = true
                cancelButton.isEnabled = true
            }
        }
    }

    private fun finishDialog() {
        logger.info("finishDialog invoked; hiding/closing dialog")
        try { creator.dispose() } catch (_: Throwable) {}
        try { currentJob?.cancel() } catch (_: Throwable) {}
        currentJob = null
        try { hide() } catch (_: Throwable) {}
        try { close() } catch (_: Throwable) {}
        try { super.accept() } catch (_: Throwable) {}
    }

    override fun reject() {
        finishDialog()
    }

    override fun accept() {
        finishDialog()
    }
}

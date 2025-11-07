package io.github.footermandev.tritium.ui.dashboard

import io.github.footermandev.tritium.*
import io.github.footermandev.tritium.core.project.ProjectBase
import io.github.footermandev.tritium.core.project.ProjectDirWatcher
import io.github.footermandev.tritium.core.project.ProjectMngr
import io.github.footermandev.tritium.ui.components.SearchIconButton
import io.github.footermandev.tritium.ui.theme.ColorPart
import io.github.footermandev.tritium.ui.theme.TIcons
import io.github.footermandev.tritium.ui.theme.ThemeMngr
import io.github.footermandev.tritium.ui.theme.applyThemeStyle
import io.github.footermandev.tritium.ui.theme.qt.qtStyle
import io.qt.Nullable
import io.qt.core.*
import io.qt.gui.*
import io.qt.widgets.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class NewProjectsPanel: QWidget() {
    private val logger = logger()

    private val scrollArea = QScrollArea()
    private val listContainer = QWidget()
    private val listLayout = QVBoxLayout(listContainer)
    private val watcher: ProjectDirWatcher
    private var currentProjects: List<ProjectBase> = emptyList()
    private var searchFilter: String = ""

    private enum class LayoutMode { List, Grid, CompactList }

    private interface ProjectLayout {
        val widget: QWidget
        fun setProjects(projects: List<ProjectBase>)
        fun applyFilter(filter: String)
        fun clear()
    }

    private val searchDebounceTimer = QTimer().apply {
        isSingleShot = true
        interval = 20
    }
    private var noResultsLabel: QLabel? = null

    private val layouts: Map<LayoutMode, ProjectLayout> by lazy {
        mapOf(
            LayoutMode.List to ListProjectLayout(),
            LayoutMode.Grid to GridProjectLayout(),
            LayoutMode.CompactList to CompactProjectLayout()
        )
    }
    private var activeMode: LayoutMode = LayoutMode.List
    private var currentLayout: ProjectLayout = layouts[activeMode]!!

    init {
        val mainLayout = QVBoxLayout(this)
        mainLayout.contentsMargins = 10.m
        mainLayout.widgetSpacing = 0

        val btnRow = QWidget()
        val btnLayout = QHBoxLayout(btnRow)
        btnRow.sizePolicy = QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        btnLayout.contentsMargins = 0.m
        btnLayout.widgetSpacing = 10

        val searchField = QLineEdit().apply {
            objectName = "searchField"
            placeholderText = "Search"
            minimumHeight = 40
            sizePolicy = QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)

            val icon = SearchIconButton(
                this,
                TIcons.Search.scaled(32, 32),
                TIcons.CloseSearch.scaled(32, 32),
                this
            )
            icon.placeAt(8, 4)

            styleSheet = qtStyle {
                selector("searchField") {
                    padding(left = 40)
                    border()
                    borderRadius(8)
                    backgroundColor(ThemeMngr.getColorHex("ButtonBg")!!)
                }
            }.toCss()

            textChanged.connect(this@NewProjectsPanel, "onSearchTextChanged(String)")

            textChanged.connect { newText: String ->
                if(newText.isNotEmpty()) {
                    icon.activateOnce()
                } else {
                    icon.deactivateOnce()
                }
                icon.updateCursor()
            }

            icon.updateCursor()
        }

        val newProject = QPushButton("New Project").apply {
            minimumHeight = 40
            sizePolicy = QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
            applyThemeStyle("ButtonBg", ColorPart.Bg)
            icon = QIcon(TIcons.NewProject)
            iconSize = qs(32, 32)
            onClicked {
                dashboardLogger.info("New Project")
            }
        }

        val importProject = QPushButton("Import Project").apply {
            minimumHeight = 40
            sizePolicy = QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
            applyThemeStyle("ButtonBg", ColorPart.Bg)
            icon = QIcon(TIcons.Import)
            iconSize = qs(32, 32)
            isEnabled = true // MARK: Not yet implemented
        }

        val cloneFromGit = QPushButton("Clone from Git").apply {
            minimumHeight = 40
            sizePolicy = QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
            applyThemeStyle("ButtonBg", ColorPart.Bg)
            icon = QIcon(TIcons.Git)
            iconSize = qs(32, 32)
            isEnabled = true // MARK: Not yet implemented
        }

        val listBtn = QToolButton().apply {
            text = ""
            toolTip = "List"
            checkable = true
            icon = QIcon(TIcons.ListView)
            clicked.connect { switchLayout(LayoutMode.List) }
        }
        val gridBtn = QToolButton().apply {
            text = ""
            toolTip = "Grid"
            checkable = true
            icon = QIcon(TIcons.GridView)
            clicked.connect { switchLayout(LayoutMode.Grid) }
        }
        val compactBtn = QToolButton().apply {
            text = ""
            toolTip = "Compact"
            checkable = true
            icon = QIcon(TIcons.CompactView)
            clicked.connect { switchLayout(LayoutMode.CompactList) }
        }

        listBtn.isChecked = true

        val layoutButtonsWidget = QWidget().apply {
            val v = QVBoxLayout(this)
            v.contentsMargins = 0.m
            v.widgetSpacing = 6
            v.addWidget(listBtn)
            v.addWidget(gridBtn)
            v.addWidget(compactBtn)

            sizePolicy = QSizePolicy(QSizePolicy.Policy.Fixed, QSizePolicy.Policy.Fixed)
            minimumWidth = 12
            maximumWidth = 12
            maximumHeight = 36
            setFixedWidth(12)
        }

        btnLayout.addWidget(searchField)
        btnLayout.addWidget(newProject)
        btnLayout.addWidget(importProject)
        btnLayout.addWidget(cloneFromGit)
        btnLayout.addWidget(layoutButtonsWidget)
        btnLayout.setStretch(0,1)
        btnLayout.setStretch(1,1)
        btnLayout.setStretch(2,1)
        btnLayout.setStretch(3, 1)
        mainLayout.addWidget(btnRow)

        scrollArea.widgetResizable = true
        scrollArea.verticalScrollBarPolicy = Qt.ScrollBarPolicy.ScrollBarAlwaysOff
        scrollArea.frameShape = QFrame.Shape.NoFrame
        scrollArea.setWidget(currentLayout.widget)
        mainLayout.addWidget(scrollArea, 1)

        listLayout.setAlignment(Qt.AlignmentFlag.AlignTop)
        listLayout.contentsMargins = 20.m
        listLayout.widgetSpacing = 10

        searchDebounceTimer.timeout.connect(this, "applyFilter()")

        watcher = ProjectDirWatcher(ProjectMngr.projectsDir.toPath())
        watcher.start(onChange = {
            QTimer.singleShot(0) { refresh() }
        })

        refresh()
    }

    private fun switchLayout(mode: LayoutMode) {
        if (mode == activeMode) return

        val vbar = scrollArea.verticalScrollBar()
        val prevScroll = vbar?.value() ?: 0

        // swap widget
        activeMode = mode
        currentLayout = layouts[mode]!!
        scrollArea.takeWidget()?.let {
            it.hide()
            it.setParent(null)
            it.disposeLater()
        }
        scrollArea.setWidget(currentLayout.widget)

        currentLayout.setProjects(getFilteredProjects())
        currentLayout.applyFilter(searchFilter)

        QTimer.singleShot(0) { vbar?.value = prevScroll }
    }

    fun exit() { watcher.stop() }

    @OptIn(DelicateCoroutinesApi::class)
    private fun refresh() {
        GlobalScope.launch(Dispatchers.IO) {
            bgDashboardLogger.info("Refreshing projects...")
            val projects = ProjectMngr.refreshProjects()
            QTimer.singleShot(0) {
                if(!projectsEqual(currentProjects, projects)) {
                    currentProjects = projects
                    currentLayout.setProjects(getFilteredProjects())
                } else bgDashboardLogger.debug("Project list unchanged, skipping UI update")
            }
        }
    }

    private fun getFilteredProjects(): List<ProjectBase> = if(searchFilter.isBlank()) {
        currentProjects
    } else {
        currentProjects.filter { project ->
            project.name.lowercase().contains(searchFilter)
        }
    }

    private fun projectsEqual(a: List<ProjectBase>, b: List<ProjectBase>): Boolean {
        if (a.size != b.size) return false
        return a.zip(b).all { (p1, p2) ->
            p1.path == p2.path && p1.name == p2.name && p1.icon == p2.icon
        }
    }

    private fun clearLayout(layout: QLayout) {
        for(i in layout.count() - 1 downTo 0) {
            val item = layout.takeAt(i) ?: continue
            item.widget()?.let { w ->
                layout.removeWidget(w)
                w.hide()
                w.setParent(null)
                w.disposeLater()
            }
        }
    }

    private fun applyFilter() {
        currentLayout.applyFilter(searchFilter)
        val vbar = scrollArea.verticalScrollBar()
        val prev = vbar?.value() ?: 0
        QTimer.singleShot(0) { vbar?.value = prev }
    }

    fun onSearchTextChanged(text: String) {
        searchFilter = text.lowercase()
        searchDebounceTimer.start()
    }

    private inner class ListProjectLayout : ProjectLayout {
        override val widget = QWidget()
        private val layout = QVBoxLayout(widget).apply {
            setAlignment(Qt.AlignmentFlag.AlignTop)
            contentsMargins = 20.m
            widgetSpacing = 10
        }

        private val items = mutableListOf<ProjectCard>()

        override fun setProjects(projects: List<ProjectBase>) {
            clear()
            items.clear()

            if (projects.isEmpty()) {
                val label = QLabel("No available projects")
                label.setAlignment(Qt.AlignmentFlag.AlignCenter)
                layout.addWidget(label)
                layout.addStretch(1)
                return
            }

            projects.forEach { p ->
                logger.info("Listed Project: {}", p.name)
                val card = ProjectCard(p)
                layout.addWidget(card)
                items += card
            }
            layout.addStretch(1)
            applyFilter(searchFilter)
        }

        override fun applyFilter(filter: String) {
            val q = filter.trim()
            val qLower = q.lowercase()
            val pattern = if (q.isBlank()) null else Regex(Regex.escape(q), RegexOption.IGNORE_CASE)

            var visibleCount = 0
            items.forEach { card ->
                val matches = q.isBlank() || card.project.name.lowercase().contains(qLower)
                if (matches) {
                    visibleCount++
                    card.setHighlight(pattern)
                    card.setVisibleWithFade(true)
                } else {
                    card.setHighlight(null)
                    card.setVisibleWithFade(false)
                }
            }

            if (visibleCount == 0) {
                if (noResultsLabel == null) {
                    noResultsLabel = QLabel("No projects match your search").apply {
                        setAlignment(Qt.AlignmentFlag.AlignCenter)
                    }
                    layout.addWidget(noResultsLabel)
                    layout.addStretch(1)
                } else {
                    noResultsLabel?.isVisible = true
                }
            } else {
                noResultsLabel?.isVisible = false
            }
        }

        override fun clear() {
            clearLayout(layout)
        }
    }

    private inner class GridProjectLayout : ProjectLayout {
        override val widget = QWidget()
        private val outerLayout = QVBoxLayout(widget).apply {
            contentsMargins = 12.m
            widgetSpacing = 8
        }
        private val gridContainer = QWidget()
        private val gridLayout = QGridLayout(gridContainer).apply {
            setHorizontalSpacing(12)
            setVerticalSpacing(12)
            contentsMargins = 0.m
        }

        private val items = mutableListOf<ProjectCard>()
        private var columns = 1

        init {
            outerLayout.addWidget(gridContainer)
            // Recompute columns on resize
            gridContainer.installEventFilter(object : QObject() {
                override fun eventFilter(o: QObject?, e: QEvent?): Boolean {
                    if (e is QResizeEvent) {
                        recomputeAndRelayout()
                    }
                    return super.eventFilter(o, e)
                }
            })
        }

        override fun setProjects(projects: List<ProjectBase>) {
            clear()
            items.clear()

            if (projects.isEmpty()) {
                val label = QLabel("No available projects")
                label.setAlignment(Qt.AlignmentFlag.AlignCenter)
                outerLayout.addWidget(label)
                outerLayout.addStretch(1)
                return
            }

            projects.forEach { p ->
                val card = ProjectCard(p).apply {
                    // make cards a bit more compact for grid
                    minimumSize = qs(180, 90)
                }
                items += card
            }

            recomputeAndRelayout()
            applyFilter(searchFilter)
        }

        private fun recomputeAndRelayout() {
            val w = gridContainer.width.takeIf { it > 0 } ?: gridContainer.sizeHint.width()
            val cardWidth = 220
            val newCols = maxOf(1, w / cardWidth)
            if (newCols == columns && gridLayout.count() > 0) return

            columns = newCols
            clearLayout(gridLayout)

            items.forEachIndexed { idx, card ->
                val row = idx / columns
                val col = idx % columns
                gridLayout.addWidget(card, row, col)
            }
            widget.update()
            widget.repaint()
        }

        override fun applyFilter(filter: String) {
            val q = filter.trim()
            val qLower = q.lowercase()
            val pattern = if (q.isBlank()) null else Regex(Regex.escape(q), RegexOption.IGNORE_CASE)

            var visibleCount = 0
            items.forEach { card ->
                val matches = q.isBlank() || card.project.name.lowercase().contains(qLower)
                if (matches) {
                    visibleCount++
                    card.setHighlight(pattern)
                    card.setVisibleWithFade(true)
                } else {
                    card.setHighlight(null)
                    card.setVisibleWithFade(false)
                }
            }

            if (visibleCount == 0) {
                if (noResultsLabel == null) {
                    noResultsLabel = QLabel("No projects match your search").apply {
                        setAlignment(Qt.AlignmentFlag.AlignCenter)
                    }
                    outerLayout.addWidget(noResultsLabel)
                    outerLayout.addStretch(1)
                } else {
                    noResultsLabel?.isVisible = true
                }
            } else {
                noResultsLabel?.isVisible = false
            }

            recomputeAndRelayout()
        }

        override fun clear() {
            clearLayout(gridLayout)
            clearLayout(outerLayout)
            outerLayout.addWidget(gridContainer)
        }
    }

    private inner class CompactProjectLayout : ProjectLayout {
        override val widget = QWidget()
        private val layout = QVBoxLayout(widget).apply {
            setAlignment(Qt.AlignmentFlag.AlignTop)
            contentsMargins = 10.m
            widgetSpacing = 6
        }
        private val items = mutableListOf<ProjectCard>()

        override fun setProjects(projects: List<ProjectBase>) {
            clear()
            items.clear()

            if (projects.isEmpty()) {
                val label = QLabel("No available projects")
                label.setAlignment(Qt.AlignmentFlag.AlignCenter)
                layout.addWidget(label)
                layout.addStretch(1)
                return
            }

            projects.forEach { p ->
                val card = ProjectCard(p).apply {
                    minimumSize = qs(0, 56)
                    titleLabel.styleSheet = "font-size: 13px;"
                }
                layout.addWidget(card)
                items += card
            }
            layout.addStretch(1)
            applyFilter(searchFilter)
        }

        override fun applyFilter(filter: String) {
            val q = filter.trim()
            val qLower = q.lowercase()
            val pattern = if (q.isBlank()) null else Regex(Regex.escape(q), RegexOption.IGNORE_CASE)

            var visibleCount = 0
            items.forEach { card ->
                val matches = q.isBlank() || card.project.name.lowercase().contains(qLower)
                if (matches) {
                    visibleCount++
                    card.setHighlight(pattern)
                    card.setVisibleWithFade(true)
                } else {
                    card.setHighlight(null)
                    card.setVisibleWithFade(false)
                }
            }

            if (visibleCount == 0) {
                if (noResultsLabel == null) {
                    noResultsLabel = QLabel("No projects match your search").apply {
                        setAlignment(Qt.AlignmentFlag.AlignCenter)
                    }
                    layout.addWidget(noResultsLabel)
                    layout.addStretch(1)
                } else {
                    noResultsLabel?.isVisible = true
                }
            } else {
                noResultsLabel?.isVisible = false
            }
        }

        override fun clear() {
            clearLayout(layout)
        }
    }
}

class ProjectCard(val project: ProjectBase): QWidget() {

    private var hovered = false
    private var selected = false

    val titleLabel = QLabel(project.name).apply {
        textFormat = Qt.TextFormat.RichText
        styleSheet = "font-size: 16px;"
        text = escapeHtml(project.name)
    }

    private val opacityEffect = QGraphicsOpacityEffect(this).apply {
        opacity = 1.0
        setParent(this@ProjectCard)
    }

    private var currentAnimation: QPropertyAnimation? = null
    private var visibleState = true

    init {
        objectName = "ProjectCard_${System.identityHashCode(this)}"
        setAttribute(Qt.WidgetAttribute.WA_StyledBackground, true)

        focusPolicy = Qt.FocusPolicy.StrongFocus

        val layout = QHBoxLayout(this)
        layout.contentsMargins = 10.m
        layout.widgetSpacing = 5

        val iconLabel = QLabel()
        val pix: QPixmap? = try {
            loadQtImage(project.icon, 50, 50)
        } catch (_: Throwable) {
            null
        }
        iconLabel.pixmap = pix ?: QPixmap()
        iconLabel.minimumSize = qs(50, 50)
        layout.addWidget(iconLabel)

        layout.addWidget(titleLabel, 1)

        setGraphicsEffect(opacityEffect)

        updateStyle()
        cursor = QCursor(Qt.CursorShape.PointingHandCursor)

        QTimer.singleShot(0) {
            setHighlight(null)
            opacityEffect.opacity = 1.0
        }
    }

    fun setHighlight(pattern: Regex?) {
        if (pattern == null) {
            titleLabel.text = escapeHtml(project.name)
            return
        }

        try {
            val sb = StringBuilder()
            var lastIndex = 0
            val matches = pattern.findAll(project.name).toList()
            if (matches.isEmpty()) {
                titleLabel.text = escapeHtml(project.name)
                return
            }

            val color = ThemeMngr.getColorHex("Accent")
            for (m in matches) {
                val start = m.range.first
                val end = m.range.last + 1
                if (start > lastIndex) {
                    sb.append(escapeHtml(project.name.substring(lastIndex, start)))
                }
                val matched = escapeHtml(project.name.substring(start, end))
                sb.append("<span style=\"background-color: $color; border-radius: 4px; padding: 0 2px;\">")
                sb.append(matched)
                sb.append("</span>")
                lastIndex = end
            }
            if (lastIndex < project.name.length) {
                sb.append(escapeHtml(project.name.substring(lastIndex)))
            }
            titleLabel.text = sb.toString()
        } catch (_: Throwable) {
            titleLabel.text = escapeHtml(project.name)
        }
    }


    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun setVisibleWithFade(show: Boolean, durationMs: Int = 180, immediate: Boolean = false) {
        if (show == visibleState && currentAnimation == null) return

        currentAnimation?.let {
            it.stop()
            currentAnimation = null
        }

        if (immediate) {
            opacityEffect.opacity = if (show) 1.0 else 0.0
            isVisible = show
            visibleState = show
            return
        }

        val anim = QPropertyAnimation(opacityEffect, "opacity")
        anim.setParent(this)
        anim.duration = durationMs
        anim.easingCurve = QEasingCurve(QEasingCurve.Type.InOutQuad)

        if (show) {
            if (!isVisible) isVisible = true
            val start = opacityEffect.opacity
            anim.startValue = start
            anim.endValue = 1.0
            anim.finished.connect {
                visibleState = true
                currentAnimation = null
            }
            currentAnimation = anim
            anim.start()
        } else {
            val start = opacityEffect.opacity
            anim.startValue = start
            anim.endValue = 0.0
            anim.finished.connect {
                QTimer.singleShot(0) {
                    isVisible = false
                    setHighlight(null)
                    visibleState = false
                    currentAnimation = null
                }
            }
            currentAnimation = anim
            anim.start()
        }
    }

    private fun openProject() {
        dashboardLogger.info("Open Project ${project.name}")


        try {
//            ProjectMngr.openProject(project)
        } catch (t: Throwable) {
            dashboardLogger.error("Failed to open project: ${t.message}", t)
        }

//        Dashboard.I?.let {
//            try { it.close() } catch (_: Throwable) {}
//        }
    }

    private fun updateStyle() {
        val radius = 8

        val hoverBg = ThemeMngr.getColorHex("Accent")!!.hexToRgbString()
        val id = objectName.takeIf { !it.isNullOrBlank() } ?: "ProjectCard"
        val bgValue = if(hovered || selected) hoverBg else "transparent"

        val sheet = buildString {
            append("background-color: $bgValue;")
            append("border-radius: ${radius}px;")
        }

        setAttribute(Qt.WidgetAttribute.WA_StyledBackground, true)
        styleSheet = sheet

        update()
        repaint()
    }

    override fun enterEvent(event: @Nullable QEnterEvent?) {
        super.enterEvent(event)
        if(!hasFocus()) setFocus()
        hovered = true
        updateStyle()
    }

    override fun leaveEvent(event: @Nullable QEvent?) {
        super.leaveEvent(event)
        hovered = false
        updateStyle()
    }

    override fun focusInEvent(event: @Nullable QFocusEvent?) {
        super.focusInEvent(event)
        selected = true
        updateStyle()
    }

    override fun focusOutEvent(event: @Nullable QFocusEvent?) {
        super.focusOutEvent(event)
        selected = false
        hovered = false
        updateStyle()
    }

    override fun mousePressEvent(event: @Nullable QMouseEvent?) {
        super.mousePressEvent(event)
        setFocus()
        if(event?.button() == Qt.MouseButton.LeftButton) {
            openProject()
        }
    }
}
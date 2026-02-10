package io.github.footermandev.tritium.ui.project.sidebar

import io.github.footermandev.tritium.connect
import io.github.footermandev.tritium.core.project.ProjectBase
import io.github.footermandev.tritium.extension.core.BuiltinRegistries
import io.github.footermandev.tritium.logger
import io.github.footermandev.tritium.registry.DeferredRegistryBuilder
import io.github.footermandev.tritium.ui.helpers.runOnGuiThread
import io.github.footermandev.tritium.ui.theme.TIcons
import io.github.footermandev.tritium.ui.theme.qt.icon
import io.github.footermandev.tritium.ui.widgets.constructor_functions.hBoxLayout
import io.github.footermandev.tritium.ui.widgets.constructor_functions.label
import io.github.footermandev.tritium.ui.widgets.constructor_functions.toolButton
import io.github.footermandev.tritium.ui.widgets.constructor_functions.widget
import io.qt.core.Qt
import io.qt.gui.QAction
import io.qt.gui.QPixmap
import io.qt.widgets.*

/**
 * Manages side panel docks and toolbars for a project window.
 *
 * Panels are discovered from the `ui.side_panel` registry.
 */
class SidePanelMngr(
    private val project: ProjectBase,
    private val parent: QMainWindow,
    private val onDockCreated: (String, DockWidget) -> Unit = { _, _ -> },
) {
    private val docks = LinkedHashMap<String, DockWidget>()

    private val leftBar   = createSidebar(Qt.ToolBarArea.LeftToolBarArea)
    private val rightBar  = createSidebar(Qt.ToolBarArea.RightToolBarArea)
    private val bottomBar = createSidebar(Qt.ToolBarArea.BottomToolBarArea)

    private val dockActions = mutableMapOf<String, QAction>()

    private val logger = logger()

    init {
        DeferredRegistryBuilder(BuiltinRegistries.SidePanel) { providers ->
            runOnGuiThread {
                buildProviders(providers.sortedBy { it.order })
            }
        }
    }

    private fun createSidebar(area: Qt.ToolBarArea): QToolBar = QToolBar().apply {
        isMovable = false
        val vertical = area != Qt.ToolBarArea.BottomToolBarArea
        orientation = if(vertical) Qt.Orientation.Vertical else Qt.Orientation.Horizontal
        parent.addToolBar(area, this)
    }

    private fun buildProviders(providers: List<SidePanelProvider>) {
        for(p in providers) {
            try {
                val dock = p.create(project)
                docks[p.id] = dock

                var features: QDockWidget.DockWidgetFeatures = QDockWidget.DockWidgetFeatures(QDockWidget.DockWidgetFeature.NoDockWidgetFeatures)
                if(p.closeable) features = features.combined(QDockWidget.DockWidgetFeature.DockWidgetClosable)
                if(p.floatable) features = features.combined(QDockWidget.DockWidgetFeature.DockWidgetFloatable)
                dock.features = features

                val action = QAction(p.icon, "").apply {
                    isCheckable = true
                    isChecked = dock.isVisible
                    toolTip = p.displayName
                    triggered.connect { visible -> dock.isVisible = visible }
                }
                dock.visibilityChanged.connect { visible -> action.isChecked = visible }
                dockActions[p.id] = action

                parent.addDockWidget(Qt.DockWidgetArea.LeftDockWidgetArea, dock)
                leftBar.addAction(action)

                dock.objectName = p.id
                dock.applyIcon(p.icon)

                setupTitleBar(dock, p, Qt.DockWidgetArea.LeftDockWidgetArea)
                onDockCreated(p.id, dock)
            } catch (t: Throwable) {
                logger.warn("Failed to create side panel {}", p.id, t)
            }
        }
    }

    private fun setupTitleBar(dock: DockWidget, provider: SidePanelProvider, currentArea: Qt.DockWidgetArea) {
        val titleBar = widget()
        val layout = hBoxLayout(titleBar) {
            setContentsMargins(5,2,5,2)
            widgetSpacing = 5
        }

        layout.addWidget(label { pixmap = provider.icon.pixmap(16,16) ?: QPixmap() })
        layout.addWidget(label(provider.displayName))
        layout.addStretch()

        val toolBtn = toolButton {
            icon = TIcons.ListView.icon
            popupMode = QToolButton.ToolButtonPopupMode.InstantPopup
        }

        val menu = QMenu(toolBtn)
        val areas = mapOf(
            "Move to Left" to Qt.DockWidgetArea.LeftDockWidgetArea,
            "Move to Right" to Qt.DockWidgetArea.RightDockWidgetArea,
            "Move to Bottom" to Qt.DockWidgetArea.BottomDockWidgetArea,
        )

        for((label, area) in areas) {
            if(area == currentArea) continue
            menu.addAction(label)?.triggered?.connect { moveDock(dock, provider, area) }
        }

        toolBtn.setMenu(menu)
        layout.addWidget(toolBtn)
        dock.setTitleBarWidget(titleBar)
    }

    private fun moveDock(dock: DockWidget, provider: SidePanelProvider, newArea: Qt.DockWidgetArea) {
        parent.addDockWidget(newArea, dock)

        val action = dockActions[provider.id] ?: return
        leftBar.removeAction(action)
        rightBar.removeAction(action)
        bottomBar.removeAction(action)

        when (newArea) {
            Qt.DockWidgetArea.LeftDockWidgetArea -> leftBar.addAction(action)
            Qt.DockWidgetArea.RightDockWidgetArea -> rightBar.addAction(action)
            Qt.DockWidgetArea.BottomDockWidgetArea -> bottomBar.addAction(action)
            else -> leftBar.addAction(action)
        }

        setupTitleBar(dock, provider, newArea)
    }

    fun dockWidgets(): Map<String, QDockWidget> = HashMap(docks)
}

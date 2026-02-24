package io.github.footermandev.tritium.ui.project.sidebar

import io.github.footermandev.tritium.core.project.ProjectBase
import io.github.footermandev.tritium.registry.Registrable
import io.qt.core.Qt
import io.qt.gui.QIcon

/**
 * Provides a dockable side panel for a project window.
 */
interface SidePanelProvider: Registrable {

    val displayName: String
    val icon: QIcon
    val order: Int

    val closeable: Boolean get() = true
    val floatable: Boolean get() = true
    val preferredArea: Qt.DockWidgetArea get() = Qt.DockWidgetArea.LeftDockWidgetArea

    fun create(project: ProjectBase): DockWidget
}

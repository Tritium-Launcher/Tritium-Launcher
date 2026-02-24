package io.github.footermandev.tritium.ui.project.sidebar

import io.github.footermandev.tritium.core.project.ProjectBase
import io.github.footermandev.tritium.ui.notifications.ProjectNotificationListPanel
import io.github.footermandev.tritium.ui.theme.TIcons
import io.github.footermandev.tritium.ui.theme.qt.icon
import io.qt.core.Qt
import io.qt.gui.QIcon

/**
 * Side panel showing project notification history.
 */
class ProjectNotificationsSidePanelProvider : SidePanelProvider {
    override val id: String = "notifications"
    override val displayName: String = "Notifications"
    override val icon: QIcon = TIcons.QuestionMark.icon
    override val order: Int = 20

    override val closeable: Boolean = false
    override val floatable: Boolean = false
    override val preferredArea: Qt.DockWidgetArea = Qt.DockWidgetArea.RightDockWidgetArea

    override fun create(project: ProjectBase): DockWidget {
        val dock = DockWidget(displayName, null)
        dock.setWidget(ProjectNotificationListPanel(project))
        return dock
    }
}

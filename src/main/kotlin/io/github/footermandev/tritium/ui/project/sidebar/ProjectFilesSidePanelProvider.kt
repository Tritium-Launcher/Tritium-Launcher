package io.github.footermandev.tritium.ui.project.sidebar

import io.github.footermandev.tritium.connect
import io.github.footermandev.tritium.core.project.ProjectBase
import io.github.footermandev.tritium.core.project.ProjectDirWatcher
import io.github.footermandev.tritium.extension.core.BuiltinRegistries
import io.github.footermandev.tritium.io.VPath
import io.github.footermandev.tritium.registry.DeferredRegistryBuilder
import io.github.footermandev.tritium.ui.theme.TIcons
import io.github.footermandev.tritium.ui.theme.qt.icon
import io.qt.core.Qt.ItemDataRole.UserRole
import io.qt.gui.QIcon
import io.qt.widgets.QAbstractItemView
import io.qt.widgets.QTreeWidget
import io.qt.widgets.QTreeWidgetItem

/**
 * The standard project files view while a Project is open.
 *
 * @see SidePanelProvider
 * @see DockWidget
 */
class ProjectFilesSidePanelProvider: SidePanelProvider {

    override val id: String = "project_files"
    override val displayName: String = "Project Files"
    override val icon: QIcon = TIcons.Folder.icon
    override val order: Int = 0

    override val closeable: Boolean = false
    override val floatable: Boolean = false

    override fun create(project: ProjectBase): DockWidget {
        val dock = DockWidget(displayName, null)
        val tree = QTreeWidget().apply {
            headerHidden = true
            selectionMode = QAbstractItemView.SelectionMode.SingleSelection
            alternatingRowColors = true
        }
        dock.setWidget(tree)

        fun refresh() {
            populateTree(project, tree)
        }

        DeferredRegistryBuilder(BuiltinRegistries.FileType) { _ ->
            refresh()
        }

        val watcher = ProjectDirWatcher(project.projectDir)
        watcher.start({
            refresh()
        })

        dock.destroyed.connect { watcher.stop() }

        return dock
    }

    private fun populateTree(project: ProjectBase, tree: QTreeWidget) {
        tree.clear()
        buildNode(project.projectDir, tree.invisibleRootItem(), project)
    }

    private fun buildNode(path: VPath, parent: QTreeWidgetItem, project: ProjectBase) {
        val files = path.list().takeIf { it.isNotEmpty() } ?: return
        val sorted = files.sortedWith(compareBy({ !it.isDir() }, { it.fileName() }))
        val ftr = BuiltinRegistries.FileType
        for(c in sorted) {
            val item = QTreeWidgetItem(parent)
            item.setText(0, c.fileName())
            item.setData(0, UserRole, c)
            val matches = ftr.all().filter { desc -> desc.matches(c, project) }.sortedBy { it.order }
            val primary = matches.firstOrNull()
            if(primary != null && primary.icon != null) item.setIcon(0, primary.icon ?: TIcons.File.icon)
            if(c.isDir()) buildNode(c, item, project)
        }
    }
}
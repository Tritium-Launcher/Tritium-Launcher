package io.github.footermandev.tritium.ui.project.toolbar.icon

import io.github.footermandev.tritium.core.project.ProjectBase
import io.github.footermandev.tritium.core.project.ProjectMngr
import io.github.footermandev.tritium.io.VPath

data class FileContext(
    val project: ProjectBase,
    val path: VPath
) {
    val isInRoot: Boolean
        get() = path.parent().toAbsolute() == project.path

    val isDirectory: Boolean
        get() = path.isDir()

    val isProjectName: Boolean
        get() = path.isFileName(project.name)

    val hasSibling: (String) -> Boolean = { siblingName ->
        path.parent().list().any { it.fileName() == siblingName }
    }

    companion object {
        fun fromFile(file: VPath): FileContext {
            val project = ProjectMngr.activeProject
                ?: error("No active project available for FileContext")
            return FileContext(project, file)
        }
    }
}

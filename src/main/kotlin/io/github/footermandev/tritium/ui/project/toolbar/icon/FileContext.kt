package io.github.footermandev.tritium.ui.project.toolbar.icon

import io.github.footermandev.tritium.core.project.ProjectBase
import io.github.footermandev.tritium.core.project.ProjectMngr
import java.io.File

data class FileContext(
    val project: ProjectBase,
    val path: File
) {
    val isInRoot: Boolean
        get() = path.parentFile?.absolutePath == project.path

    val isDirectory: Boolean
        get() = path.isDirectory

    val isProjectName: Boolean
        get() = path.name.equals(project.name, ignoreCase = true)

    val hasSibling: (String) -> Boolean = { siblingName ->
        path.parentFile?.listFiles()?.any { it.name == siblingName } == true
    }

    companion object {
        fun fromFile(file: File): FileContext {
            val project = ProjectMngr.activeProject
                ?: error("No active project available for FileContext")
            return FileContext(project, file)
        }
    }
}

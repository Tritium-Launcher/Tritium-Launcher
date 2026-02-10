package io.github.footermandev.tritium.ui.project.menu

import io.github.footermandev.tritium.core.project.ProjectBase
import io.qt.widgets.QMainWindow

data class MenuActionContext(
    val project: ProjectBase?,
    val window: QMainWindow?,
    val selection: Any?,
    val meta: Map<String, String> = emptyMap()
)

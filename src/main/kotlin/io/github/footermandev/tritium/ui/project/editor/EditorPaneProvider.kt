package io.github.footermandev.tritium.ui.project.editor

import io.github.footermandev.tritium.core.project.ProjectBase
import io.github.footermandev.tritium.io.VPath
import io.github.footermandev.tritium.registry.Registrable

interface EditorPaneProvider: Registrable {

    val displayName: String
    val order: Int

    fun canOpen(file: VPath, project: ProjectBase): Boolean

    fun create(project: ProjectBase, file: VPath): EditorPane
}
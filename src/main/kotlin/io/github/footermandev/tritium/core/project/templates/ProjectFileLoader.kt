package io.github.footermandev.tritium.core.project.templates

import io.github.footermandev.tritium.core.project.ProjectBase
import io.github.footermandev.tritium.core.project.TrProjectFile
import io.github.footermandev.tritium.io.VPath

/**
 * Optional hook for loading projects using the unified project definition file.
 */
interface ProjectFileLoader {
    fun loadFromProjectFile(projectFile: TrProjectFile, projectDir: VPath): ProjectBase
}

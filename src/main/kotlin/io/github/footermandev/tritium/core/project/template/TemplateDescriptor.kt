package io.github.footermandev.tritium.core.project.template

import io.github.footermandev.tritium.core.project.ProjectBase
import kotlinx.serialization.KSerializer
import java.io.File

interface TemplateDescriptor<T: Any> {
    val typeId: String
    val serializer: KSerializer<T>
    val projectName: String
    val defaultIcon: String
    val currentSchema: Int

    fun createProjectFromMeta(meta: T, schemaVersion: Int, projectDir: File): ProjectBase
}
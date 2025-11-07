package io.github.footermandev.tritium.core.project

import io.github.footermandev.tritium.userHome
import kotlinx.serialization.json.JsonObject
import java.io.File

class Project<T: Any>(
    typeId: String,
    projectDir: File,
    name: String,
    icon: String,
    rawMeta: JsonObject,
    val typedMeta: T
): ProjectBase(typeId, projectDir, name, icon, rawMeta)

open class ProjectBase(
    val typeId: String,
    val projectDir: File,
    val name: String,
    val icon: String,
    val rawMeta: JsonObject
) {
    val path: String
        get() = projectDir.path

    fun getIconPath(): String {
        val path: String = if(icon.startsWith("~")) {
            "$userHome/${icon.removePrefix("~")}"
        } else icon

        return path
    }
}
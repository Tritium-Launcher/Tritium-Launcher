package io.github.footermandev.tritium.core.project.template.builtin

import io.github.footermandev.tritium.core.mod.Mod
import io.github.footermandev.tritium.core.project.Project
import io.github.footermandev.tritium.core.project.ProjectBase
import io.github.footermandev.tritium.core.project.template.TemplateDescriptor
import io.github.footermandev.tritium.logger
import io.github.footermandev.tritium.ui.theme.TIcons
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * @param name The name of the project
 * @param path The path to the project
 * @param icon The path to the icon for the project
 * @param version The version of the project
 * @param changelogFile The changelog specifications
 *
 * @param minecraftVersion The Minecraft version of the project
 *
 * @param modLoader The Mod Loader of the project
 * @param modLoaderVersion The Mod Loader version of the project
 *
 * @param source The source of the project
 *
 * @param mods The mods in the project
 *
 * @param authors The authors of the project
 *
 * @param titleColor The title color of the project. Supports hex color codes, via #, 0x or without prefixes.
 */
@Serializable
data class ModpackProjectMetadata(
    var name: String,
    var path: String,
    var version: String,

    var minecraftVersion: String,

    var modLoader: String,
    var modLoaderVersion: String,

    var source: String,

    var mods: List<Mod>,

    var authors: List<Pair<String, String>>, // Name, Title

    var titleColor: String?
)

object ModpackProjectTemplateDescriptor : TemplateDescriptor<ModpackProjectMetadata> {
    override val typeId: String
        get() = "modpack-generic"

    override val serializer: KSerializer<ModpackProjectMetadata>
        get() = ModpackProjectMetadata.serializer()

    override val projectName: String
        get() = "Unknown"

    override val defaultIcon: String
        get() = TIcons.defaultProjectIcon

    override val currentSchema: Int
        get() = 1

    private val logger = logger()

    override fun createProjectFromMeta(
        meta: ModpackProjectMetadata,
        schemaVersion: Int,
        projectDir: File
    ): ProjectBase {
        val raw = Json.encodeToJsonElement(serializer, meta).jsonObject
        return Project(typeId, projectDir, projectName, this.defaultIcon,raw, meta)
    }
}
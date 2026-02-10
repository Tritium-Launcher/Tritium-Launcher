package io.github.footermandev.tritium.extension.core

import io.github.footermandev.tritium.accounts.AccountProvider
import io.github.footermandev.tritium.core.modloader.ModLoader
import io.github.footermandev.tritium.core.modpack.ModSource
import io.github.footermandev.tritium.core.project.ProjectType
import io.github.footermandev.tritium.core.project.templates.generation.license.License
import io.github.footermandev.tritium.registry.RegistryMngr
import io.github.footermandev.tritium.ui.dashboard.ProjectListStyleProvider
import io.github.footermandev.tritium.ui.project.editor.EditorPaneProvider
import io.github.footermandev.tritium.ui.project.editor.file.FileTypeDescriptor
import io.github.footermandev.tritium.ui.project.editor.syntax.SyntaxLanguage
import io.github.footermandev.tritium.ui.project.menu.MenuItem
import io.github.footermandev.tritium.ui.project.sidebar.SidePanelProvider

/**
 * Central registry handles for core and UI extension points.
 *
 * Each property returns or creates the named registry via [RegistryMngr] so extensions can
 * register their implementations in a consistent location.
 */
object BuiltinRegistries {
    val ModLoader       = RegistryMngr.getOrCreateRegistry<ModLoader>("core.mod_loader")
    val ModSource       = RegistryMngr.getOrCreateRegistry<ModSource>("core.mod_source")
    val ProjectType     = RegistryMngr.getOrCreateRegistry<ProjectType>("core.project_type")
    val License         = RegistryMngr.getOrCreateRegistry<License>("core.license")
    val AccountProvider = RegistryMngr.getOrCreateRegistry<AccountProvider>("core.account_provider")
    val SidePanel       = RegistryMngr.getOrCreateRegistry<SidePanelProvider>("ui.side_panel")
    val MenuItem        = RegistryMngr.getOrCreateRegistry<MenuItem>("ui.menu")
    val FileType        = RegistryMngr.getOrCreateRegistry<FileTypeDescriptor>("ui.file_type")
    val SyntaxLanguage  = RegistryMngr.getOrCreateRegistry<SyntaxLanguage>("ui.syntax")
    val EditorPane      = RegistryMngr.getOrCreateRegistry<EditorPaneProvider>("ui.editor_pane")
    val ProjectListStyle = RegistryMngr.getOrCreateRegistry<ProjectListStyleProvider>("ui.project_list_style")
}

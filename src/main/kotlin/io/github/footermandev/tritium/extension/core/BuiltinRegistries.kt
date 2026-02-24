package io.github.footermandev.tritium.extension.core

import io.github.footermandev.tritium.accounts.AccountProvider
import io.github.footermandev.tritium.core.modloader.ModLoader
import io.github.footermandev.tritium.core.modpack.ModSource
import io.github.footermandev.tritium.core.project.ProjectType
import io.github.footermandev.tritium.core.project.templates.generation.license.License
import io.github.footermandev.tritium.registry.RegistryMngr
import io.github.footermandev.tritium.ui.dashboard.ProjectListStyleProvider
import io.github.footermandev.tritium.ui.notifications.NotificationDefinition
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
    val AccountProvider = RegistryMngr.getOrCreateRegistry<AccountProvider>("account_provider")
    val EditorPane      = RegistryMngr.getOrCreateRegistry<EditorPaneProvider>("editor_pane")
    val FileType        = RegistryMngr.getOrCreateRegistry<FileTypeDescriptor>("file_type")
    val License         = RegistryMngr.getOrCreateRegistry<License>("license")
    val MenuItem        = RegistryMngr.getOrCreateRegistry<MenuItem>("menu")
    val ModLoader       = RegistryMngr.getOrCreateRegistry<ModLoader>("mod_loader")
    val ModSource       = RegistryMngr.getOrCreateRegistry<ModSource>("mod_source")
    val Notification    = RegistryMngr.getOrCreateRegistry<NotificationDefinition>("notification")
    val ProjectType     = RegistryMngr.getOrCreateRegistry<ProjectType>("project_type")
    val ProjectListStyle = RegistryMngr.getOrCreateRegistry<ProjectListStyleProvider>("project_list_style")
    val SidePanel       = RegistryMngr.getOrCreateRegistry<SidePanelProvider>("side_panel")
    val SyntaxLanguage  = RegistryMngr.getOrCreateRegistry<SyntaxLanguage>("syntax")
}

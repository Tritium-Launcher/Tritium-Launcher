package io.github.footermandev.tritium.extension.core

import io.github.footermandev.tritium.accounts.ui.MicrosoftAccountProvider
import io.github.footermandev.tritium.core.modloader.Fabric
import io.github.footermandev.tritium.core.modloader.NeoForge
import io.github.footermandev.tritium.core.modpack.CurseForge
import io.github.footermandev.tritium.core.modpack.Modrinth
import io.github.footermandev.tritium.core.project.ModpackProjectType
import io.github.footermandev.tritium.core.project.ModpackTemplateDescriptor
import io.github.footermandev.tritium.core.project.templates.TemplateRegistry
import io.github.footermandev.tritium.core.project.templates.generation.license.*
import io.github.footermandev.tritium.extension.Extension
import io.github.footermandev.tritium.registry.RegistryMngr
import io.github.footermandev.tritium.settings.SettingsMngr
import io.github.footermandev.tritium.ui.dashboard.DvdStyleProvider
import io.github.footermandev.tritium.ui.dashboard.GridStyleProvider
import io.github.footermandev.tritium.ui.dashboard.ListStyleProvider
import io.github.footermandev.tritium.ui.project.editor.file.builtin.BuiltinFileTypes
import io.github.footermandev.tritium.ui.project.editor.pane.ImageViewerProvider
import io.github.footermandev.tritium.ui.project.editor.pane.SettingsEditorPaneProvider
import io.github.footermandev.tritium.ui.project.editor.syntax.builtin.JsonLanguage
import io.github.footermandev.tritium.ui.project.editor.syntax.builtin.PythonLanguage
import io.github.footermandev.tritium.ui.project.menu.builtin.BuiltinMenuItems
import io.github.footermandev.tritium.ui.project.sidebar.ProjectFilesSidePanelProvider
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Core extension that registers Tritium's base content and features.
 */
internal object CoreExtension : Extension {
    private val coreModule = module {
        single(createdAtStart = true) {
            val rm: RegistryMngr = get()
            val settings: SettingsMngr = get()

            val modLoaders        = BuiltinRegistries.ModLoader
            val modSources        = BuiltinRegistries.ModSource
            val projectTypes      = BuiltinRegistries.ProjectType
            val licenses          = BuiltinRegistries.License
            val accountProviders  = BuiltinRegistries.AccountProvider
            val fileTypes         = BuiltinRegistries.FileType
            val sidePanels        = BuiltinRegistries.SidePanel
            val menuItems         = BuiltinRegistries.MenuItem
            val syntax            = BuiltinRegistries.SyntaxLanguage
            val editorPanes       = BuiltinRegistries.EditorPane
            val projectListStyles = BuiltinRegistries.ProjectListStyle

            settings.register(this@CoreExtension.namespace, CoreSettings.registration)

            accountProviders.register(MicrosoftAccountProvider())

            modLoaders.register(Fabric())
            modLoaders.register(NeoForge())

            modSources.register(Modrinth())
            modSources.register(CurseForge())

            projectTypes.register(ModpackProjectType())

            licenses.register(listOf(
                NoLicense(),
                MITLicense(),
                Apache2License(),
                Gpl3License(),
                Gpl2License(),
                Gpl21LesserLicense(),
                Bsd2License(),
                Bsd3License(),
                ISCLicense(),
                MPL2License(),
                Unlicense(),
                AllRightsReservedLicense()
            ))

            fileTypes.register(BuiltinFileTypes.all())

            sidePanels.register(ProjectFilesSidePanelProvider())

            menuItems.register(BuiltinMenuItems.All)

            syntax.register(listOf(
                JsonLanguage(),
                PythonLanguage(),
            ))

            TemplateRegistry.register(ModpackTemplateDescriptor)

            editorPanes.register(ImageViewerProvider())
            editorPanes.register(SettingsEditorPaneProvider())
            projectListStyles.register(listOf(GridStyleProvider, ListStyleProvider, DvdStyleProvider))
        }
    }

    override val namespace: String = "tritium"

    override val modules: List<Module> = listOf(coreModule)
}

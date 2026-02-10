package io.github.footermandev.tritium.extension

import org.koin.core.module.Module

/**
 * Implement this to create an Extension loaded on startup.
 *
 * Extensions can be used to add features not available in Tritium natively, such as:
 *
 * Mod Loaders
 *
 * Project Types
 *
 * 3rd Party Service Integration
 *
 * File Types
 *
 * Language Support
 *
 * Editor Panes
 * @see io.github.footermandev.tritium.extension.core.CoreExtension
 * @see ExtensionLoader
 * @see ExtensionDirectoryLoader
 * @see io.github.footermandev.tritium.registry.Registry
 *
 * @see io.github.footermandev.tritium.core.modloader.ModLoader
 * @see io.github.footermandev.tritium.core.project.ProjectType
 * @see io.github.footermandev.tritium.accounts.AccountProvider
 * @see io.github.footermandev.tritium.ui.project.editor.file.FileTypeDescriptor
 * @see io.github.footermandev.tritium.ui.project.editor.syntax.SyntaxLanguage
 * @see io.github.footermandev.tritium.ui.project.editor.EditorPane
 */
interface Extension {
    val id: String
    val modules: List<Module>
}
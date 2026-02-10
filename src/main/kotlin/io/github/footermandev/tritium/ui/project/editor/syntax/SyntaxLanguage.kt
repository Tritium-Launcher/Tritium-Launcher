package io.github.footermandev.tritium.ui.project.editor.syntax

import io.github.footermandev.tritium.io.VPath
import io.github.footermandev.tritium.registry.Registrable

/**
 * Describes a syntax highlighting language and optional LSP integration.
 *
 * Languages are registered in the `ui.syntax` registry and used by the editor to
 * pick highlighting rules based on file matches.
 */
interface SyntaxLanguage: Registrable {
    val displayName: String
    val rules: List<SyntaxRule>
    val parentLanguage: String? get() = null

    val lspCmd: List<String>? get() = null
    val lspCmds: List<List<String>>? get() = null

    fun matches(file: VPath): Boolean

    companion object {
        fun create(
            id: String,
            displayName: String,
            predicate: VPath.() -> Boolean,
            rules: List<SyntaxRule> = emptyList(),
            lspCmd: List<String>? = null,
            lspCmds: List<List<String>>? = null
        ): SyntaxLanguage = object : SyntaxLanguage {
            override val id: String = id
            override val displayName: String = displayName
            override val rules: List<SyntaxRule> = rules
            override val lspCmd: List<String>? = lspCmd
            override val lspCmds: List<List<String>>? = lspCmds

            override fun matches(file: VPath): Boolean = predicate(file)
        }
    }
}

data class SyntaxRule(val pattern: Regex, val tokenType: String)

package io.github.footermandev.tritium.ui.project.editor.syntax.builtin

import io.github.footermandev.tritium.io.VPath
import io.github.footermandev.tritium.matches
import io.github.footermandev.tritium.ui.project.editor.syntax.SyntaxLanguage
import io.github.footermandev.tritium.ui.project.editor.syntax.SyntaxRule

class JsonLanguage : SyntaxLanguage {
    override val id: String = "json"
    override val displayName: String = "JSON"

    override val rules: List<SyntaxRule> = listOf(

        // Keywords
        SyntaxRule(Regex("\\b(true|false|null)\\b"), "Keyword"),

        // Numbers
        SyntaxRule(Regex("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?"), "Number"),

        // Punctuation
        SyntaxRule(Regex("[{}\\[\\],:]"), "Punctuation"),

        // String value
        SyntaxRule(
            Regex("\"(?:[^\"\\\\\\u0000-\\u001F]|\\\\[\"\\\\/bfnrt]|\\\\u[0-9a-fA-F]{4})*\""),
            "String"
        ),

        // Keys
        SyntaxRule(
            Regex("\"(?:[^\"\\\\\\u0000-\\u001F]|\\\\[\"\\\\/bfnrt]|\\\\u[0-9a-fA-F]{4})*\"(?=\\s*:)"),
            "Key"
        )
    )

    override fun matches(file: VPath): Boolean = file.extension().matches("json")
}

package io.github.footermandev.tritium.ui.project.editor.syntax.builtin

import io.github.footermandev.tritium.io.VPath
import io.github.footermandev.tritium.matches
import io.github.footermandev.tritium.ui.project.editor.syntax.SyntaxLanguage
import io.github.footermandev.tritium.ui.project.editor.syntax.SyntaxRule

class XmlLanguage : SyntaxLanguage {
    override val id: String = "xml"
    override val displayName: String = "XML"

    override val rules: List<SyntaxRule> = listOf(

        // Doctype
        SyntaxRule(Regex("<!DOCTYPE(?:.|\\R)*?>", RegexOption.DOT_MATCHES_ALL), "Keyword"),

        // Processing Instructions (e.g., <?xml ... ?>)
        SyntaxRule(Regex("<\\?.*?\\?>", RegexOption.DOT_MATCHES_ALL), "Keyword"),

        // Tag names
        SyntaxRule(Regex("</?[A-Za-z_][\\w:.-]*"), "Keyword"),

        // Attributes
        SyntaxRule(Regex("\\b[A-Za-z_][\\w:.-]*(?=\\s*=)"), "Key"),

        // Entities
        SyntaxRule(Regex("&(?:[A-Za-z_][\\w:.-]*|#\\d+|#x[0-9A-Fa-f]+);"), "Keyword"),

        // String Values
        SyntaxRule(Regex("\"(?:[^\"<&]|&[^;]+;)*\"|'(?:[^'<&]|&[^;]+;)*'"), "String"),

        // Comments
        SyntaxRule(Regex("<!--(?:.|\\R)*?-->", RegexOption.DOT_MATCHES_ALL), "Comment"),

        // CDATA
        SyntaxRule(Regex("<!\\[CDATA\\[(?:.|\\R)*?]]>", RegexOption.DOT_MATCHES_ALL), "String")
    )

    override fun matches(file: VPath): Boolean = file.extension().matches("xml", "svg", "svgs", "xhtml", "xsd")
}

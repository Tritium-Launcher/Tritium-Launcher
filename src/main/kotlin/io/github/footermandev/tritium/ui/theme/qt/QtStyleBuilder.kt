package io.github.footermandev.tritium.ui.theme.qt

import io.qt.widgets.QWidget

@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
annotation class QtStyleMarker

@QtStyleMarker
class StyleBuilder internal constructor(private val selector: String? = null) {
    private val props = LinkedHashMap<String, String>()
    private val children = mutableListOf<StyleBuilder>()

    fun backgroundColor(color: String) { props["background-color"] = color }
    fun color(color: String) { props["color"] = color }
    fun border(color: String, width: Int = 1, style: String = "solid") { props["border"] = "$width" + "px $style $color" }
    fun border() { props["border"] = "none" }
    fun borderRadius(radiusPx: Int) { props["border-radius"] = "${radiusPx}px"}
    fun padding(allPx: Int) { props["padding"] = "${allPx}px"}
    fun padding(top: Int = 0, right: Int = 0, bottom: Int = 0, left: Int = 0) { props["padding"] = "${top}px ${right}px ${bottom}px ${left}px" }
    fun margin(allPx: Int) { props["margin"] = "${allPx}px"}
    fun minHeight(px: Int) { props["min-height"] = "${px}px"}
    fun minWidth(px: Int) { props["min-width"] = "${px}px"}
    fun maxHeight(px: Int) { props["max-height"] = "${px}px"}
    fun maxWidth(px: Int) { props["max-width"] = "${px}px"}
    fun cursor(cursor: String) { props["cursor"] = cursor}
    fun any(name: String, value: String) { props[name] = value }

    fun descendant(selectorSuffix: String, block: StyleBuilder.() -> Unit) {
        val childSelector = when {
            selector == null -> selectorSuffix
            selectorSuffix.startsWith(":") -> "$selector$selectorSuffix"
            else -> "$selector $selectorSuffix"
        }
        val child = StyleBuilder(childSelector).apply(block)
        children.add(child)
    }

    fun pseudo(pseudoName: String, block: StyleBuilder.() -> Unit) {
        val childSelector = (selector ?: "") + pseudoName
        val child = StyleBuilder(childSelector).apply(block)
        children.add(child)
    }

    internal fun toCss(): String {
        val sb = StringBuilder()
        if(props.isNotEmpty()) {
            val sel = selector ?: "QWidget"
            sb.append(sel).append(" {")
            props.forEach { (k, v) ->
                sb.append(k).append(": ").append(v).append(';')
            }
            sb.append("}\n")
        }
        children.forEach { sb.append(it.toCss()) }
        return sb.toString()
    }

    companion object {
        fun selector(sel: String, block: StyleBuilder.() -> Unit): StyleBuilder =
            StyleBuilder(sel).apply(block)
    }
}

@QtStyleMarker
class QtStyleSheet {
    private val blocks = mutableListOf<StyleBuilder>()

    fun selector(sel: String, block: StyleBuilder.() -> Unit) {
        val str = if(!sel.startsWith("#")) "#$sel" else sel
        blocks.add(StyleBuilder.selector(str, block))
    }

    fun widget(block: StyleBuilder.() -> Unit) {
        blocks.add(StyleBuilder(null).apply(block))
    }

    fun toCss(): String = blocks.joinToString(separator = "\n") { it.toCss() }

    fun applyTo(widget: QWidget) {
        widget.styleSheet = toCss()
        widget.update()
        widget.repaint()
    }
}

fun qtStyle(block: QtStyleSheet.() -> Unit): QtStyleSheet {
    return QtStyleSheet().apply(block)
}

object QtColor {
    fun rgb(r: Int, g: Int, b: Int) = "rgb($r,$g,$b)"
    fun rgba(r: Int, g: Int, b: Int, a: Double) = "rgba($r,$g,$b,$a)"
    fun hex(hex: String) = if (hex.startsWith("#")) hex else "#$hex"
}
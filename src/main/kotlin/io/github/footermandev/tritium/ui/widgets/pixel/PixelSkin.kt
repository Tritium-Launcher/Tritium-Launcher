package io.github.footermandev.tritium.ui.widgets.pixel

import io.qt.gui.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** DSL marker for pixel skin builders. */
@DslMarker
annotation class PixelDsl

/** Defines named colors for a pixel skin. */
@PixelDsl
class PixelPaletteBuilder {
    private val colors = mutableMapOf<String, QColor>()

    /** Register a palette color by name. */
    fun color(name: String, color: QColor) {
        colors[name] = color
    }

    /** Register a palette color by hex string (e.g. "#ff00ff"). */
    fun color(name: String, hex: String) {
        colors[name] = QColor(hex)
    }

    internal fun build(): Map<String, QColor> = colors.toMap()
}

@PixelDsl
class PixelStateBuilder {
    private val ops = mutableListOf<(PixelDrawScope) -> Unit>()

    /** Add a draw step for this state. */
    fun draw(block: PixelDrawScope.() -> Unit) {
        ops += { scope -> scope.block() }
    }

    internal fun build(): PixelState = PixelState(ops.toList())
}

@PixelDsl
class PixelSkinBuilder {
    /** Base pixel size before DPR scaling. */
    var pixelSize: Int = 2
    private val palette = PixelPaletteBuilder()
    private val states = mutableMapOf<String, PixelState>()

    /** Define the palette for this skin. */
    fun palette(block: PixelPaletteBuilder.() -> Unit) {
        palette.block()
    }

    /** Define a visual state by name (e.g. "normal", "pressed"). */
    fun state(name: String, block: PixelStateBuilder.() -> Unit) {
        val builder = PixelStateBuilder().apply(block)
        states[name] = builder.build()
    }

    internal fun build(): PixelSkin = PixelSkin(
        pixelSize = pixelSize.coerceAtLeast(1),
        palette = palette.build(),
        states = states.toMap()
    )
}

/** Build a pixel skin with a DSL. */
fun pixelSkin(block: PixelSkinBuilder.() -> Unit): PixelSkin =
    PixelSkinBuilder().apply(block).build()

/** Drawing scope for pixel skins. Units are in device pixels. */
class PixelDrawScope internal constructor(
    private val painter: QPainter,
    val width: Int,
    val height: Int,
    val px: Int,
    private val palette: Map<String, QColor>
) {
    /** Convert logical units to device pixels. */
    fun u(units: Int): Int = units * px

    /** Get a palette color by name. */
    fun color(name: String): QColor =
        palette[name] ?: throw IllegalArgumentException("Unknown color '$name' in pixel palette")

    /** Fill a rect in device pixels using a color value. */
    fun fillRect(x: Int, y: Int, w: Int, h: Int, color: QColor) {
        painter.fillRect(x, y, w, h, QBrush(color))
    }

    /** Fill a rect in device pixels using a palette color name. */
    fun fillRect(x: Int, y: Int, w: Int, h: Int, colorName: String) {
        fillRect(x, y, w, h, color(colorName))
    }
}

/** A named visual state for a pixel skin. */
class PixelState internal constructor(
    private val ops: List<(PixelDrawScope) -> Unit>
) {
    internal fun draw(scope: PixelDrawScope) {
        ops.forEach { it(scope) }
    }
}

class PixelSkin internal constructor(
    private val pixelSize: Int,
    private val palette: Map<String, QColor>,
    private val states: Map<String, PixelState>
) {
    private val cache = ConcurrentHashMap<String, QPixmap>()

    /**
     * Render the skin for a specific state and size.
     * Results are cached by state, size, and DPR.
     */
    fun render(state: String, width: Int, height: Int, dpr: Double): QPixmap {
        val key = cacheKey(state, width, height, dpr)
        return cache.computeIfAbsent(key) { renderInternal(state, width, height, dpr) }
    }

    /** Clear the render cache. */
    fun clearCache(disposePixmaps: Boolean = false) {
        if (disposePixmaps) {
            cache.values.forEach { it.dispose() }
        }
        cache.clear()
    }

    /**
     * Render the pixmap as an image to keep it from mutating when changing screens.
     */
    private fun renderInternal(state: String, width: Int, height: Int, dpr: Double): QPixmap {
        val quantStep = 0.25
        val quantDpr = ( (dpr / quantStep).roundToInt() * quantStep )

        val physW = ceil(width * quantDpr).toInt().coerceAtLeast(1)
        val physH = ceil(height * quantDpr).toInt().coerceAtLeast(1)
        val px    = max(1, ceil(pixelSize * quantDpr).toInt())

        val img = QImage(physW, physH, QImage.Format.Format_ARGB32)
        img.fill(0)

        val painter = QPainter(img)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing, false)

        val chosen = states[state] ?: states["normal"] ?: states.values.firstOrNull()
        if(chosen != null) {
            val scope = PixelDrawScope(painter, physW, physH, px, palette)
            chosen.draw(scope)
        }

        painter.end()
        return QPixmap.fromImage(img)
    }

    private fun cacheKey(state: String, width: Int, height: Int, dpr: Double): String {
        val quantStep = 0.25
        val quantDpr = ( (dpr / quantStep).roundToInt() * quantStep )
        val dprKey = String.format("%.3f", quantDpr)
        return "$state|${width}x${height}@$dprKey"
    }
}

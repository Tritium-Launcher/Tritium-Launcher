package io.github.footermandev.tritium.ui.widgets

import io.github.footermandev.tritium.currentDpr
import io.github.footermandev.tritium.qs
import io.github.footermandev.tritium.ui.widgets.pixel.pixelSkin
import io.qt.Nullable
import io.qt.core.QEvent
import io.qt.core.QSize
import io.qt.core.Qt
import io.qt.gui.*
import io.qt.widgets.QWidget
import kotlin.math.abs

/**
 * Minecraft-styled on/off switch widget.
 * Emits [toggled] when the checked state changes.
 */
class TToggleSwitch(parent: QWidget? = null) : QWidget(parent) {
    var isChecked: Boolean = false
        private set
    private var lastDpr: Double = -1.0

    val toggled = Signal1<Boolean>()

    init {
        focusPolicy = Qt.FocusPolicy.StrongFocus
        minimumSize = qs(28, 14)
    }

    /**
     * Update checked state and repaint if it changed.
     */
    fun setChecked(checked: Boolean) {
        if (isChecked == checked) return
        isChecked = checked
        toggled.emit(isChecked)
        update()
    }

    /**
     * Toggle checked state.
     */
    fun toggle() {
        setChecked(!isChecked)
    }

    override fun mousePressEvent(event: @Nullable QMouseEvent?) {
        if (event?.button() == Qt.MouseButton.LeftButton) {
            toggle()
        }
        super.mousePressEvent(event)
    }

    override fun keyPressEvent(event: @Nullable QKeyEvent?) {
        if (event?.key() == Qt.Key.Key_Space.value() || event?.key() == Qt.Key.Key_Return.value()) {
            toggle()
            return
        }
        super.keyPressEvent(event)
    }

    override fun paintEvent(event: QPaintEvent?) {
        val painter = QPainter(this)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing, false)

        val w = width()
        val h = height()
        val dpr = try { painter.device().devicePixelRatio()
        } catch (_: Throwable) {
            try { currentDpr(this) } catch (_: Throwable) { 1.0 }
        }
        handleDprChange(dpr)

        val state = when {
            !isEnabled && isChecked -> State.DisabledOn
            !isEnabled -> State.DisabledOff
            isChecked -> State.On
            else -> State.Off
        }

        val bg = skin.render(state.key, w, h, dpr)
        if (!bg.isNull) {
            painter.drawPixmap(0, 0, w, h, bg)
        }

        painter.end()
    }

    override fun changeEvent(e: @Nullable QEvent?) {
        super.changeEvent(e)
        when (e?.type()) {
            QEvent.Type.StyleChange,
            QEvent.Type.PaletteChange,
            QEvent.Type.DevicePixelRatioChange,
            QEvent.Type.ScreenChangeInternal -> skin.clearCache(disposePixmaps = true)
            else -> {}
        }
    }

    override fun event(e: @Nullable QEvent?): Boolean {
        if (e?.type() == QEvent.Type.ScreenChangeInternal || e?.type() == QEvent.Type.DevicePixelRatioChange) {
            handleDprChange(currentDpr(this))
        }
        return super.event(e)
    }

    override fun moveEvent(event: @Nullable QMoveEvent?) {
        super.moveEvent(event)
        handleDprChange(currentDpr(this))
    }

    override fun showEvent(event: @Nullable QShowEvent?) {
        super.showEvent(event)
        handleDprChange(currentDpr(this))
    }

    private fun handleDprChange(dpr: Double) {
        if (lastDpr < 0.0 || abs(lastDpr - dpr) > 0.001) {
            skin.clearCache(disposePixmaps = true)
            lastDpr = dpr
            update()
        }
    }

    override fun sizeHint(): QSize = qs(32, 16)

    private enum class State(val key: String) {
        Off("off"),
        On("on"),
        DisabledOff("disabled_off"),
        DisabledOn("disabled_on")
    }

    companion object {
        private val skin = pixelSkin {
            pixelSize = 2
            palette { //TODO: Don't hardcode colors
                color("border", QColor(0, 0, 0))
                color("trackOff", QColor(60, 60, 60))
                color("trackOn", QColor(76, 166, 76))
                color("trackDisabled", QColor(32, 32, 32))
                color("knob", QColor(230, 230, 230))
                color("knobBorder", QColor(0, 0, 0))
                color("knobDisabled", QColor(120, 120, 120))
            }

            state("off") {
                draw {
                    val p = px
                    val w = width
                    val h = height
                    fillRect(0, 0, w, h, "border")
                    fillRect(p, p, w - p * 2, h - p * 2, "trackOff")

                    val knob = h - p * 2
                    val x = p
                    val y = p
                    fillRect(x, y, knob, knob, "knobBorder")
                    fillRect(x + p, y + p, knob - p * 2, knob - p * 2, "knob")
                }
            }

            state("on") {
                draw {
                    val p = px
                    val w = width
                    val h = height
                    fillRect(0, 0, w, h, "border")
                    fillRect(p, p, w - p * 2, h - p * 2, "trackOn")

                    val knob = h - p * 2
                    val x = w - p - knob
                    val y = p
                    fillRect(x, y, knob, knob, "knobBorder")
                    fillRect(x + p, y + p, knob - p * 2, knob - p * 2, "knob")
                }
            }

            state("disabled_off") {
                draw {
                    val p = px
                    val w = width
                    val h = height
                    fillRect(0, 0, w, h, "border")
                    fillRect(p, p, w - p * 2, h - p * 2, "trackDisabled")

                    val knob = h - p * 2
                    val x = p
                    val y = p
                    fillRect(x, y, knob, knob, "knobBorder")
                    fillRect(x + p, y + p, knob - p * 2, knob - p * 2, "knobDisabled")
                }
            }

            state("disabled_on") {
                draw {
                    val p = px
                    val w = width
                    val h = height
                    fillRect(0, 0, w, h, "border")
                    fillRect(p, p, w - p * 2, h - p * 2, "trackDisabled")

                    val knob = h - p * 2
                    val x = w - p - knob
                    val y = p
                    fillRect(x, y, knob, knob, "knobBorder")
                    fillRect(x + p, y + p, knob - p * 2, knob - p * 2, "knobDisabled")
                }
            }
        }
    }
}

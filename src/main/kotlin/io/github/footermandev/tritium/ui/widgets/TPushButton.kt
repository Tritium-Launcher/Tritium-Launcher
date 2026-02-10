package io.github.footermandev.tritium.ui.widgets

import io.github.footermandev.tritium.connect
import io.github.footermandev.tritium.currentDpr
import io.github.footermandev.tritium.ui.theme.TColors
import io.github.footermandev.tritium.ui.theme.ThemeMngr
import io.github.footermandev.tritium.ui.widgets.pixel.pixelSkin
import io.qt.Nullable
import io.qt.core.QEvent
import io.qt.gui.*
import io.qt.widgets.QPushButton
import io.qt.widgets.QStyle
import io.qt.widgets.QStyleOptionButton
import io.qt.widgets.QWidget
import kotlin.math.abs

/**
 * Minecraft-styled push button.
 */
class TPushButton(
    parent: QWidget? = null
) : QPushButton(parent) {
    private var lastDpr: Double = -1.0

    init {
        toggled.connect { checked ->
            if (isCheckable) {
                isDown = checked
            }
        }
    }

    override fun paintEvent(event: QPaintEvent?) {
        val painter = QPainter(this)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing, false)

        val w = width()
        val h = height()

        val state = when {
            !isEnabled -> State.Disabled
            isDown || isChecked -> State.Pressed
            else -> State.Normal
        }

        val dpr = detectDpr(this)
        handleDprChange(dpr)

        val bg = skin.render(state.key, w, h, dpr)

        if(!bg.isNull) {
            painter.drawPixmap(0, 0, w, h, bg)
        }

        drawLabel(painter, dpr)

        painter.end()
    }

    private fun drawLabel(painter: QPainter, dpr: Double) {
        val opt = QStyleOptionButton()
        initStyleOption(opt)

        val r = opt.rect
        if(isDown || isChecked) {
            r.translate(0, 1)
        } else {
            r.translate(0, -1)
        }
        opt.rect = r

        painter.save()
        style()?.drawControl(QStyle.ControlElement.CE_PushButtonLabel, opt, painter, this)
        painter.restore()
    }

    override fun changeEvent(e: @Nullable QEvent?) {
        super.changeEvent(e)
        when (e?.type()) {
            QEvent.Type.StyleChange,
            QEvent.Type.PaletteChange,
            QEvent.Type.DevicePixelRatioChange,
            QEvent.Type.ScreenChangeInternal -> {
                skin.clearCache(disposePixmaps = true)
                lastDpr = -1.0
                update()
            }
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

    private fun detectDpr(widget: QWidget?): Double {
        try {
            val screenDpr = widget?.window()?.windowHandle()?.screen()?.devicePixelRatio
            if (screenDpr != null && screenDpr > 0.0) return screenDpr
        } catch (_: Throwable) {}

        try {
            val wOwn = widget?.devicePixelRatio()
            if (wOwn != null && wOwn > 0.0) return wOwn
        } catch (_: Throwable) {}

        try {
            val primary = QGuiApplication.primaryScreen()?.devicePixelRatio
            if (primary != null && primary > 0.0) return primary
        } catch (_: Throwable) {}

        return 1.0
    }

    private fun handleDprChange(dpr: Double) {
        if (lastDpr < 0.0 || abs(lastDpr - dpr) > 0.001) {
            skin.clearCache(disposePixmaps = true)
            lastDpr = dpr
            update()
        }
    }

    enum class State(val key: String) { Normal("normal"), Pressed("pressed"), Disabled("disabled") }

    companion object {
        private var skin = buildSkin()

        init {
            ThemeMngr.addListener {
                val prev = skin
                skin = buildSkin()
                prev.clearCache(disposePixmaps = true)
            }
        }

        private fun buildSkin() = pixelSkin {
            pixelSize = 2
            palette {
                color("border", TColors.Button0)
                color("shadow", TColors.Button1)
                color("primary", TColors.Button2)
                color("bright", TColors.Button3)
                color("disabledBorder", TColors.ButtonDisabled2)
                color("disabled", TColors.ButtonDisabled0)
                color("disabledBright", TColors.ButtonDisabled1)
            }

            state("normal") {
                draw {
                    val p = px
                    val w = width
                    val h = height
                    fillRect(0, 0, w, h, "border")
                    fillRect(p, p, w - p * 2, h - p * 2, "primary")
                    fillRect(p, p, w - p * 2, p, "bright")
                    fillRect(p, p, p, h - p * 5, "bright")
                    fillRect(w - p * 2, p, p, h - p * 5, "bright")
                    fillRect(p, h - p * 4, w - p * 2, p, "bright")
                    fillRect(p, h - p * 3, w - p * 2, p * 2, "shadow")
                }
            }

            state("pressed") {
                draw {
                    val p = px
                    val w = width
                    val h = height
                    fillRect(0, p, w, h - p, "border")
                    fillRect(p, p + 2, w - p * 2, h - p - 4, "primary")
                    fillRect(p, p + 2, w - p * 2, p, "bright")
                    fillRect(p, p + 2, p, h - p * 4, "bright")
                    fillRect(w - p * 2, p + 2, p, h - p * 4, "bright")
                    fillRect(p, h - p * 2, w - p * 2, p, "bright")
                }
            }

            state("disabled") {
                draw {
                    val p = px
                    val w = width
                    val h = height
                    fillRect(0, 0, w, h, "disabledBorder")
                    fillRect(p, p, w - p * 2, h - p * 2, "disabled")
                    fillRect(p, p, w - p * 2, p, "disabledBright")
                    fillRect(p, p, p, h - p * 3, "disabledBright")
                    fillRect(w - p * 2, p, p, h - p * 3, "disabledBright")
                    fillRect(p, h - p * 2, w - p * 2, p, "disabledBright")
                }
            }
        }

        operator fun invoke(parent: QWidget? = null, block: TPushButton.() -> Unit = {}): TPushButton =
            TPushButton(parent).apply(block)
    }
}

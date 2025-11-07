package io.github.footermandev.tritium.ui.components

import io.github.footermandev.tritium.connect
import io.github.footermandev.tritium.stopIfActive
import io.github.footermandev.tritium.stopIfRunning
import io.qt.Nullable
import io.qt.core.QEasingCurve
import io.qt.core.QPropertyAnimation
import io.qt.core.QTimer
import io.qt.core.Qt
import io.qt.gui.QCursor
import io.qt.gui.QMouseEvent
import io.qt.gui.QPixmap
import io.qt.widgets.QGraphicsOpacityEffect
import io.qt.widgets.QLabel
import io.qt.widgets.QLineEdit
import io.qt.widgets.QWidget
import kotlin.math.abs

class SearchIconButton(
    private val ownerLineEdit: QLineEdit,
    searchPixmap: QPixmap,
    closePixmap: QPixmap,
    parent: QWidget? = null
): QWidget(parent) {

    private val rotatingLabel = RotatingLabel(this).apply {
        pixmap = searchPixmap
        setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents, true)
        setFixedSize(searchPixmap.width(), searchPixmap.height())
        isVisible = true
    }

    private val closeLabel = QLabel(this).apply {
        pixmap = closePixmap
        setFixedSize(closePixmap.size())
        setAlignment(Qt.AlignmentFlag.AlignCenter)
    }

    private val closeEffect = QGraphicsOpacityEffect(this).apply {
        opacity = 0.0
        closeLabel.setGraphicsEffect(this)
    }

    private val rotationTimer = QTimer(this).apply {
        interval = 16
    }

    private var rotationTarget = 0.0
    private var rotationDirection = 1.0
    private var rotationPerMs = 0.0
    private var lastTickMs = 0L

    private val fadeInAnim = QPropertyAnimation(closeEffect, "opacity").apply {
        startValue = 0.0
        endValue = 1.0
        duration = 400
        easingCurve = QEasingCurve(QEasingCurve.Type.InOutQuad)
        loopCount = -1
    }

    private val fadeOutAnim = QPropertyAnimation(closeEffect, "opacity").apply {
        startValue = 1.0
        endValue = 0.0
        duration = 400
        loopCount = -1
        easingCurve = QEasingCurve(QEasingCurve.Type.InOutQuad)
    }

    private var state = State.SearchVisible

    init {
        val w = searchPixmap.width()
        val h = searchPixmap.height()
        setFixedSize(w,h)
        cursor = QCursor(Qt.CursorShape.ArrowCursor)
        closeLabel.raise()

        rotationTimer.timeout.connect {
            val now = System.currentTimeMillis()
            val last = if (lastTickMs == 0L) now else lastTickMs
            val dt = (now - last).coerceAtLeast(0L)
            lastTickMs = now

            val delta = rotationPerMs * dt * rotationDirection
            val newAngle = rotatingLabel.angle + delta

            if (rotationDirection > 0) {
                if (newAngle >= rotationTarget) {
                    rotatingLabel.angle = rotationTarget
                    stopRotationTimer()
                } else {
                    rotatingLabel.angle = newAngle
                }
            } else {
                if (newAngle <= rotationTarget) {
                    rotatingLabel.angle = rotationTarget
                    stopRotationTimer()
                } else {
                    rotatingLabel.angle = newAngle
                }
            }
        }

        fadeInAnim.finished.connect {
            stopRotationTimer()
            closeEffect.opacity = 1.0
            rotatingLabel.isVisible = false
            rotatingLabel.angle = rotationTarget
            state = State.CloseVisible
            updateCursor()
        }

        fadeOutAnim.finished.connect {
            stopRotationTimer()
            closeEffect.opacity = 0.0
            rotatingLabel.isVisible = true
            rotatingLabel.angle = 0.0
            state = State.SearchVisible
            updateCursor()
        }
    }

    private fun startRotationOnce(durationMs: Int, forward: Boolean) {
        rotationTimer.stopIfActive()
        lastTickMs = 0L

        if(forward) rotatingLabel.isVisible = true

        rotationTarget    = if(forward) 360.0 else 0.0
        rotationDirection = if(forward) 1.0 else -1.0

        val currentAngle = rotatingLabel.angle
        val degreesToTravel = abs(rotationTarget - currentAngle).coerceAtLeast(0.0)

        rotationPerMs = if(durationMs > 0 && degreesToTravel > 0.0) degreesToTravel / durationMs else 0.0

        if(degreesToTravel <= 0.0) {
            rotatingLabel.angle = rotationTarget
            return
        }

        rotationTimer.start()
    }

    private fun stopRotationTimer() {
        rotationTimer.stopIfActive()
        lastTickMs = 0L
    }

    fun activateOnce() {
        if (state == State.CloseVisible || state == State.TransitionToClose) return

        fadeOutAnim.stopIfRunning()
        stopRotationTimer()

        state = State.TransitionToClose
        rotatingLabel.isVisible = true
        val dur = fadeInAnim.duration
        startRotationOnce(dur, forward = true)
        fadeInAnim.start()
        updateCursor()
    }

    fun deactivateOnce() {
        if (state == State.SearchVisible || state == State.TransitionToSearch) return

        fadeInAnim.stopIfRunning()
        stopRotationTimer()

        state = State.TransitionToSearch
        rotatingLabel.isVisible = true
        val dur = fadeOutAnim.duration
        startRotationOnce(dur, forward = false)
        fadeOutAnim.start()
        updateCursor()
    }

    fun placeAt(x: Int, y: Int) {
        setGeometry(x, y, width, height)
        rotatingLabel.setGeometry(0, 0, width, height)
        closeLabel.setGeometry(0, 0, width, height)
    }

    override fun mousePressEvent(event: @Nullable QMouseEvent?) {
        val text = ownerLineEdit.text ?: ""
        if(text.isEmpty()) {
            ownerLineEdit.setFocus()
        } else {
            ownerLineEdit.text = ""
            deactivateOnce()
        }
        event?.accept()
    }

    fun updateCursor() {
        val cur = when (state) {
            State.SearchVisible, State.TransitionToSearch -> Qt.CursorShape.IBeamCursor
            State.CloseVisible, State.TransitionToClose -> Qt.CursorShape.PointingHandCursor
        }
        cursor = QCursor(cur)
    }
}

private enum class State {
    SearchVisible,
    TransitionToClose,
    CloseVisible,
    TransitionToSearch
}
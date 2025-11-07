package io.github.footermandev.tritium

import io.qt.core.*
import io.qt.widgets.QAbstractButton
import io.qt.widgets.QLayout
import io.qt.widgets.QWidget
import kotlinx.io.IOException
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.URL
import java.nio.file.Path
import kotlin.math.PI

private val logger = LoggerFactory.getLogger("ExtensionFunctions")

/**
 * Includes various extension functions.
 */

fun String.toUrl(): URL {
    return URI(this).toURL()
}

fun String.toURI(): URI {
    return URI(this)
}

//String to Path
fun String.toPath(): Path {
    return Path.of(this)
}

fun String.shortenHome(): String {
    var index = -1
    repeat(4) {
        index = this.indexOf('/', index + 1)
        if(index == -1) return this
    }

    return "~${this.substring(index)}"
}

/**
 * Converts a hex color to an RGB [String] for Qt.
 *
 * E.g "#ffffff" -> "rgb(255,255,255)"
 */
fun String.hexToRgbString(): String {
    val raw = this.trim().removePrefix("#")
    val fullHex = when(raw.length) {
        6 -> raw
        3 -> buildString { for(c in raw) append(c).append(c) }
        else -> throw IllegalArgumentException("Hex color must be 3 or 6 digits.")
    }

    require(fullHex.matches(Regex("^[0-9a-fA-F]{6}$"))) { "Hex color contains invalid characters." }

    val r = fullHex.substring(0,2).toInt(16)
    val g = fullHex.substring(2,4).toInt(16)
    val b = fullHex.substring(4,6).toInt(16)

    return "rgb($r,$g,$b)"
}

/** Checks if this string matches any of the provided strings. */
fun String.matches(vararg strings: String): Boolean = strings.any { this == it }

fun Double.toRadians(): Double = this * (PI / 180.0)

fun Path.mkdir(): Boolean {
    return try { this.toFile().mkdir() } catch (e: IOException) { logger.error("Error creating directory: {}", e.message, e); false}
}

fun Path.mkdirs(): Boolean {
    return try { this.toFile().mkdirs() } catch (e: IOException) { logger.error("Error creating directory: {}", e.message, e); false}
}

fun String.toFile(): File = File(this)

val Int.m: QMargins
    get() = QMargins(this, this, this, this)

fun QLayout.add(vararg widgets: QWidget?) = widgets.forEach { w -> this.addWidget(w) }

@JvmName("onClickedButton")
fun QAbstractButton.onClicked(handler: () -> Unit) {
    val slotHolder = object : io.qt.core.QObject(this) {
        @Suppress("unused")
        fun handleClick() {
            try {
                handler()
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }

    this.setProperty("__clickHandler", slotHolder)

    this.clicked.connect(slotHolder, "handleClick()")
}

inline fun <T> QObject.Signal1Default1<T>.connect(crossinline handler: (T) -> Unit): QMetaObject.Slot1<T> {
    val slot = QMetaObject.Slot1<T> { arg -> handler(arg) }
    this.connect(slot)
    return slot
}

inline fun QObject.PrivateSignal0.connect(crossinline handler: () -> Unit): QMetaObject.Slot0 {
    val slot = QMetaObject.Slot0 { handler() }
    this.connect(slot)
    return slot
}

inline fun QObject.Signal0.connect(crossinline handler: () -> Unit): QMetaObject.Slot0 {
    val slot = QMetaObject.Slot0 { handler() }
    this.connect(slot)
    return slot
}

inline fun <T> QObject.Signal1<T>.connect(crossinline handler: (T) -> Unit): QMetaObject.Slot1<T> {
    val slot = QMetaObject.Slot1<T> { arg -> handler(arg) }
    this.connect(slot)
    return slot
}

inline fun <A, B> QObject.Signal2<A, B>.connect(crossinline handler: (A, B) -> Unit): QMetaObject.Slot2<A, B> {
    val slot = QMetaObject.Slot2<A, B> { a, b -> handler(a, b) }
    this.connect(slot)
    return slot
}

fun QTimer.stopIfActive() { if(this.isActive) this.stop() }

fun QPropertyAnimation.stopIfRunning() { if(this.state == QAbstractAnimation.State.Running) this.stop() }
package io.github.footermandev.tritium

import io.qt.core.QSize
import io.qt.core.Qt
import io.qt.gui.QIcon
import io.qt.gui.QImage
import io.qt.gui.QPixmap
import org.koin.core.context.GlobalContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.prefs.Preferences
import javax.imageio.ImageIO

private val logger = LoggerFactory.getLogger("GlobalFunctions") //TODO: Make a central io.github.footermandev.tritium.logger for things like this and others
val koinLogger: Logger = LoggerFactory.getLogger("Koin")

/**
 * Shortens the [System.getProperty] call
 */
fun getProperty(key: String): String {
    return System.getProperty(key)
}

/**
 * Shortens the [System.getenv] call
 */
fun getEnv(key: String): String? {
    return System.getenv(key)
}

/**
 * Returns File from ~/
 */
fun fromHome(child: String): File {
    return File(getProperty("user.home"), child)
}

/**
 * Returns File from ~/.tritium
 */
fun fromTR(child: String = ""): File {
    return File(fromHome("tritium"), child)
}

/**
 * Returns the user home directory
 */
val userHome get() = getProperty("user.home")

/**
 * Returns the Tritium directory
 */
val trHome: String get() = fromTR("").absolutePath

/**
 * Returns the XDG Config Home directory
 */
val xdgConfigHome get() = getEnv("XDG_CONFIG_HOME")

/**
 * Returns the OS name
 */
val osName get() = getProperty("os.name")

fun loadQtImage(pathStr: String, targetW: Int, targetH: Int): QPixmap? {
    try {
        val p: java.nio.file.Path = java.nio.file.Path.of(pathStr)
        if (!p.toFile().exists()) return null

        val qimg = QImage(pathStr)
        if (qimg.isNull) {
            return null
        }

        val sourceW = qimg.width()
        val sourceH = qimg.height()

        val transformMode = if (sourceW <= 32 || sourceH <= 32) {
            Qt.TransformationMode.FastTransformation
        } else {
            Qt.TransformationMode.SmoothTransformation
        }

        val scaled = qimg.scaled(
            targetW,
            targetH,
            Qt.AspectRatioMode.IgnoreAspectRatio,
            transformMode
        )

        return QPixmap.fromImage(scaled)
    } catch (t: Throwable) {
        logger.error("Failed to load project icon via Qt: ${t.message}", t)
        return null
    }
}

fun resourceIcon(resource: String, classLoader: ClassLoader): QIcon? {
    val stream = classLoader.getResourceAsStream(resource) ?: run {
        mainLogger.warn("Icon resource not found: $resource")
        return null
    }

    stream.use {
        val data = it.readBytes()
        val pixmap = QPixmap()
        if (!pixmap.loadFromData(data)) {
            mainLogger.warn("Failed to load icon pixmap from data: $resource (bytes=${data.size})")
            return null
        }
        return QIcon(pixmap)
    }
}

// TODO: Ugh god, this needs to not use AWT nonsense.
fun loadImageQt(url: String, width: Int? = null, height: Int? = null, border: Boolean = false): QPixmap? = try {
    val original: BufferedImage = ImageIO.read(url.toUrl())
        ?: return null

    val image: Image = when {
        width == null && height == null -> original
        width != null && height != null -> original.getScaledInstance(width, height, BufferedImage.SCALE_SMOOTH)
        width != null -> original.getScaledInstance(width, original.height, BufferedImage.SCALE_SMOOTH)
        else -> original.getScaledInstance(original.width, height!!, BufferedImage.SCALE_SMOOTH)
    }

    val bufferedWithBorder = if (border) {
        val w = image.getWidth(null) + 4
        val h = image.getHeight(null) + 4
        val buffered = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = buffered.createGraphics()
        g.color = java.awt.Color(0, 0, 0)
        g.fillRect(0, 0, w, h)
        g.drawImage(image, 2, 2, null)
        g.dispose()
        buffered
    } else {
        when (image) {
            is BufferedImage -> image
            else -> {
                val b = BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB)
                val g = b.createGraphics()
                g.drawImage(image, 0, 0, null)
                g.dispose()
                b
            }
        }
    }

    val baos = ByteArrayOutputStream()
    ImageIO.write(bufferedWithBorder, "png", baos)
    baos.flush()
    val bytes = baos.toByteArray()
    baos.close()

    val qimage = QImage.fromData(bytes)
    QPixmap.fromImage(qimage)
} catch (e: Exception) {
    logger.error("Failed to load image for Qt: ${e.message}", e)
    null
}

/**
 * Makes a quick [Logger] with the name of the class it is created in.
 */
fun Any.logger(): Logger {
    return LoggerFactory.getLogger(this::class.java)
}

fun logger(name: String): Logger = LoggerFactory.getLogger(name)

fun compareMCVersions(ver1: String, ver2: String): Boolean {
    fun parse(v: String): Triple<Int, Int, Int> {
        val parts = v.split('.')
        val major = parts[0].toInt()
        val minor = parts.getOrNull(1)?.toInt() ?: 0
        val patch = parts.getOrNull(2)?.toInt() ?: 0
        return Triple(major, minor, patch)
    }

    val (_, min1, pat1) = parse(ver1)
    val (_, min2, pat2) = parse(ver2)

    return when {
        min1 > min2 -> true
        min1 < min2 -> false
        pat1 >= pat2 -> true
        else -> false
    }
}

fun tPreferences(path: String) = Preferences.userRoot().node("tritiumlauncher/$path")

inline fun <reified T> getFromKoin(): List<T> {
    val all = GlobalContext.get().getAll<T>()
    koinLogger.info("Found ${all.size} instances of ${T::class.simpleName}")
    return all
}

fun qs(w: Int, h: Int): QSize = QSize(w, h)
package io.github.footermandev.tritium.ui.theme

import io.github.footermandev.tritium.mainLogger
import io.github.footermandev.tritium.qs
import io.qt.core.QByteArray
import io.qt.core.QRectF
import io.qt.core.QSize
import io.qt.core.Qt
import io.qt.gui.*
import io.qt.svg.QSvgRenderer

object TIcons {
    val Tritium = pix("icons/tritium.svg", 16, 16)

    /* File Icons */
    val File       = pix("icons/file.svg", 16, 16)
    val Folder     = pix("icons/folder.svg", 16, 16)
    val Image      = pix("icons/image.svg", 16, 16)
    val CSV        = pix("icons/csv.svg", 16, 16)
    val HTML       = pix("icons/html.svg", 16, 16)
    val JavaScript = pix("icons/javascript.svg", 16, 16)
    val TypeScript = pix("icons/typescript.svg", 16, 16)
    val JSON       = pix("icons/json.svg", 16, 16)
    val TOML       = pix("icons/toml.svg", 16, 16)
    val Archive    = pix("icons/archive.svg", 16, 16)
    val Markdown   = pix("icons/markdown.svg", 16, 16)

    val ModConfig   = pix("icons/config.svg", 16, 16)
    val TrMeta      = pix("icons/tr_config.svg", 16, 16)
    val WorldBackup = pix("icons/world_backup.svg", 16, 16)
    val PlayerData  = pix("icons/player_data.svg", 16, 16)
    val KubeScript  = pix("icons/kube.svg", 16, 16)
    val ZenScript   = pix("icons/zenscript.svg", 16, 16)

    // Menu Icons
    val CurseForge = pix("icons/curseforge.svg")
    val Modrinth   = pix("icons/modrinth.svg")

    val Fabric = pix("icons/fabric.svg")

    val NewProject  = pix("icons/new_project.svg", 32, 32)
    val Import      = pix("icons/folder-import.svg", 32, 32)
    val Git         = pix("icons/git.svg", 32, 32)
    val Search      = pix("icons/search.svg", 32, 32)
    val CloseSearch = pix("icons/close_search.svg", 32, 32)
    val ListView    = pix("icons/list_view.svg")
    val GridView    = pix("icons/grid_view.svg")
    val CompactView = pix("icons/compact_view.svg")

    val Build = pix("icons/build.svg", 16, 16)
    val Run   = pix("icons/run.svg", 16, 16)

    val NPM   = pix("icons/npm.svg", 16, 16)
    val Shell = pix("icons/shell.svg", 16, 16)

    private fun icon(resourcePath: String, size: QSize? = null): QIcon {
        val svgIcon = tryLoadSvg(resourcePath, size)
        if(svgIcon != null) return QIcon(svgIcon)

        val pix = tryLoadPixmap(resourcePath, size)
        if(pix != null) return QIcon(pix)

        val pngPath = resourcePath.replace(Regex("\\.svg$"), ".png")
        val pngPix = tryLoadPixmap(pngPath, size)
        if(pngPix != null) return QIcon(pngPix)

        mainLogger.warn("Failed to create icon from '$resourcePath'")
        return QIcon()
    }

    private fun pix(resourcePath: String, width: Int? = null, height: Int? = null): QPixmap
        = pix(resourcePath, if(width != null && height != null) qs(width, height) else null)

    private fun pix(resourcePath: String, size: QSize? = null): QPixmap {
        val path = normalize(resourcePath)

        val svgIcon = tryLoadSvg(path, size)
        if(svgIcon != null) return svgIcon

        val pix = tryLoadPixmap(path, size)
        if(pix != null) return pix

        val pngPath = path.replace(Regex("\\.svg$", RegexOption.IGNORE_CASE), ".png")
        val pngPix = tryLoadPixmap(pngPath, size)
        if(pngPix != null) return pngPix

        mainLogger.warn("Failed to create pixmap from '$path'")
        return QPixmap()
    }

    private fun tryLoadSvg(resourcePath: String, size: QSize? = null): QPixmap? {
        val stream = this::class.java.getResourceAsStream(resourcePath)
            ?: this::class.java.classLoader.getResourceAsStream(resourcePath.removePrefix("/"))
            ?: return null

        try {
            val data = stream.readBytes()
            val renderer = QSvgRenderer(QByteArray(data))

            val targetSize = size ?: QSize(16, 16)
            var w = targetSize.width()
            var h = targetSize.height()

            if(w <= 0 || h <= 0) {
                val def = renderer.defaultSize()
                w = if(def.width() > 0)  def.width()  else 16
                h = if(def.height() > 0) def.height() else 16
            }

            val vb = renderer.viewBoxF()
            val vbW = if (vb != null && vb.width() > 0) vb.width() else renderer.defaultSize().width().coerceAtLeast(1).toDouble()
            val vbH = if (vb != null && vb.height() > 0) vb.height() else renderer.defaultSize().height().coerceAtLeast(1).toDouble()

            val dpr = try { QGuiApplication.primaryScreen()?.devicePixelRatio ?: 1.0 } catch (_: Throwable) { 1.0 }
            val backingW = (w * dpr).toInt().coerceAtLeast(1)
            val backingH = (h * dpr).toInt().coerceAtLeast(1)

            val image = QImage(backingW, backingH, QImage.Format.Format_ARGB32_Premultiplied)
            image.setDevicePixelRatio(dpr)
            image.fill(Qt.GlobalColor.transparent)

            val painter = QPainter(image)

            try {
                painter.scale(dpr, dpr)

                val scale = minOf(w.toDouble() / vbW, h.toDouble() / vbH)
                val renderW = vbW * scale
                val renderH = vbH * scale
                val offsetX = (w - renderW) / 2.0
                val offsetY = (h - renderH) / 2.0
                val targetRect = QRectF(offsetX, offsetY, renderW, renderH)

                renderer.render(painter, targetRect)
            } finally { painter.end() }

            val pm = QPixmap.fromImage(image)
            pm.setDevicePixelRatio(dpr)
            return pm
        } catch (e: Exception) {
            mainLogger.error("Error occurred during SVG rendering for resource: $resourcePath", e)
            return null
        } finally {
            stream.close()
        }
    }

    private fun tryLoadPixmap(resourcePath: String, size: QSize? = null): QPixmap? {
        val url = this::class.java.getResource(resourcePath) ?: return null
        val pix = QPixmap(url.toString())
        if (pix.isNull || pix.isNull) return null

        if (size != null) {
            val scaled = pix.scaled(size, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
            return scaled
        }
        return pix
    }

    private fun normalize(p: String): String = if (p.startsWith("/")) p else "/$p"

    internal val defaultProjectIcon = javaClass.getResource("/icons/folder.png")!!.path
}
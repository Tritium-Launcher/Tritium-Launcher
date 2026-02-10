package io.github.footermandev.tritium

import io.github.footermandev.tritium.io.VPath

object TConstants {
    const val TR = "Tritium"
    const val TR_SERVICE = "TritiumLauncher"
    const val VERSION = "0.0.0"
    val TR_DIR: VPath = fromTR()

    object Dirs {
        const val PROJECTS = "projects"
        const val EXTENSIONS = "extensions"
        const val LOADERS = "loaders"
        const val CACHE = "cache"
        const val PROFILES = ".profiles"
        const val MSAL = ".msal"
        const val ASSETS = "assets"
    }

    val EXT_DIR = fromTR(Dirs.EXTENSIONS)
    val classLoader: ClassLoader = javaClass.classLoader

    object Lists {
        val ImageExtensions = listOf(
            "png", "jpg", "jpeg", "jpe", "gif", "bmp", "tiff", "tif", "webp", "avif", "heic", "heif", "jp2",
            "jxl", "ico", "cur", "dds", "exr", "svg", "svgz", "eps", "pdf", "ai", "cdr", "raw", "dng", "nef", "cr2",
            "cr3", "arw", "orf", "rw2", "pef", "aseprite"
        )
    }
}

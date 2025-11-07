package io.github.footermandev.tritium

object TConstants {
    const val TR = "Tritium"
    const val TR_SERVICE = "TritiumLauncher"
    const val CLIENT_ID = "6d6b484d-842d-47c9-abe1-e4f0c5f07c77"
    const val FACE_URL = "https://crafatar.com/avatars/"
    val TR_DIR = "${System.getProperty("user.home")}/tritium"
    val OS = System.getProperty("os.name").lowercase()

    val PLUGIN_DIR = fromTR("plugins")
    val classLoader: ClassLoader = javaClass.classLoader
}
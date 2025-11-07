package io.github.footermandev.tritium.ui.theme

import kotlinx.serialization.json.Json
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

object ThemeLoader {
    private val json = Json { ignoreUnknownKeys = true }

    @Throws(Exception::class)
    fun loadFromStream(stream: InputStream): ThemeFile {
        val bytes = stream.readBytes()
        return json.decodeFromString(ThemeFile.serializer(), bytes.decodeToString())
    }

    @Throws(Exception::class)
    fun loadFromFile(path: Path): ThemeFile = Files.newInputStream(path).use { return loadFromStream(it) }

    fun merge(base: ThemeFile?, override: ThemeFile): ThemeFile {
        if(base == null) return override
        return ThemeFile(
            meta = override.meta,
            colors = base.colors + override.colors,
            icons = base.icons + override.icons,
            stylesheets = base.stylesheets + override.stylesheets,
        )
    }
}
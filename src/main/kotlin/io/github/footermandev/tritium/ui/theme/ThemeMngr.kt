package io.github.footermandev.tritium.ui.theme

import io.github.footermandev.tritium.fromTR
import io.github.footermandev.tritium.logger
import io.github.footermandev.tritium.ui.helpers.runOnGuiThread
import io.qt.core.QByteArray
import io.qt.core.QSize
import io.qt.core.Qt
import io.qt.gui.QColor
import io.qt.gui.QIcon
import io.qt.gui.QPainter
import io.qt.gui.QPixmap
import io.qt.svg.QSvgRenderer
import io.qt.widgets.QApplication
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.nio.file.StandardWatchEventKinds.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.jar.JarFile
import java.util.prefs.Preferences
import kotlin.concurrent.thread

object ThemeMngr {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    var currentThemeId: String = ""
        private set

    private val themes = mutableMapOf<String, ThemeFile>()
    private val bundledThemes = mutableMapOf<String, ThemeFile>()
    private val pathToId = mutableMapOf<Path, String>()
    private lateinit var defaultTheme: ThemeFile
    val userThemesDir: Path = Paths.get(fromTR("themes").absolutePath)
    private val schemaFile: Path = userThemesDir.resolve("schema.json")

    private val iconCache = ConcurrentHashMap<Triple<String, String, Int>, QIcon>()


    // TODO: Eventually move this to whatever the main Settings system will be
    private val prefs: Preferences = Preferences.userRoot().node("/tritium")
    private const val PREF_SELECTED_THEME = "selectedThemeId"

    private val json = Json { prettyPrint = true }
    internal val logger = logger()

    fun init() {
        logger.info("Initializing Theme Manager...")
        try {
            if (!Files.exists(userThemesDir)) Files.createDirectories(userThemesDir)

            loadDefault()
            loadBundledThemes()
            loadUserThemes()
            restorePersistedSelectionIfAny()

            if (currentThemeId.isBlank()) {
                val chosen = when {
                    themes.containsKey(defaultTheme.meta.id) -> defaultTheme.meta.id
                    themes.isNotEmpty() -> themes.keys.first()
                    else -> defaultTheme.meta.id
                }
                setTheme(chosen)
            }

            generateSchema()

            startWatcherThread()

            logger.info("Found themes:")
            themes.keys.toList().forEach { t ->
                logger.info(t + "\n")
            }
            logger.info("Finished initializing Theme Manager.")
        } catch (e: Exception) {
            logger.error("Theme Manager init failed", e)
            try {
                setTheme(defaultTheme.meta.id)
            } catch (_: Exception) {}
        }
    }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    fun loadDefault() {
        val resStream: InputStream = this::class.java.getResourceAsStream("/themes/default.json")
            ?: throw IllegalStateException("Missing bundled default theme at /themes/default.json")
        defaultTheme = ThemeLoader.loadFromStream(resStream).also { validateTheme(it) }
        themes[defaultTheme.meta.id] = defaultTheme
    }

    private fun loadUserThemes() {
        try {
            val defaultId = defaultTheme.meta.id
            if(!themes.containsKey(defaultId)) themes[defaultId] = defaultTheme

            pathToId.clear()

            Files.list(userThemesDir).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().lowercase().endsWith(".json") }
                    .forEach { path ->
                        try {
                            val theme = ThemeLoader.loadFromFile(path)
                            validateTheme(theme)
                            themes[theme.meta.id] = theme
                            pathToId[path] = theme.meta.id
                            logger.info("Loaded user theme from ${path.fileName}: id='${theme.meta.id}'")
                        } catch (e: Exception) {
                            logger.error("Skipping invalid theme file ${path.fileName}", e)
                        }
                    }
            }

            val copy = HashMap(themes)
            for ((id, theme) in copy) {
                val baseId = theme.meta.base
                if (baseId != null && themes.containsKey(baseId)) {
                    val base = themes[baseId]
                    if (base != null) {
                        val merged = ThemeLoader.merge(base, theme)
                        themes[id] = merged
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Error loading user themes", e)
        }
    }

    private fun loadBundledThemes() {
        val pref = "themes/"
        val clazz = this::class.java
        val dirUrl = clazz.getResource("/$pref")

        if (dirUrl == null) {
            logger.debug("No bundled themes directory found on classpath at '/{}'", pref)
            return
        }

        try {
            when (dirUrl.protocol) {
                "file" -> {
                    try {
                        val dir = File(dirUrl.toURI())
                        if (dir.exists() && dir.isDirectory) {
                            dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { f ->
                                try {
                                    if (f.name == "default.json") return@forEach
                                    val theme = ThemeLoader.loadFromFile(f.toPath())
                                    validateTheme(theme)
                                    bundledThemes[theme.meta.id] = theme
                                    themes.putIfAbsent(theme.meta.id, theme)
                                    logger.info("Loaded bundled theme from file: ${f.name} (id='${theme.meta.id}')")
                                } catch (e: Exception) {
                                    logger.info("Skipping invalid bundled theme file ${f.name}: ${e.message}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logger.warn("Could not load bundled themes from file:// resource: ${e.message}")
                    }
                }

                "jar" -> {
                    try {
                        val urlStr = dirUrl.toString()
                        val jarPathPart = urlStr.substringAfter("jar:").substringBefore("!/")
                        val jarPath =
                            URLDecoder.decode(jarPathPart.removePrefix("file:"), StandardCharsets.UTF_8.name())
                        JarFile(jarPath).use { jar ->
                            val entries = jar.entries()
                            while (entries.hasMoreElements()) {
                                val entry = entries.nextElement()
                                val name = entry.name
                                if (name.startsWith(pref) && name.endsWith(".json")) {
                                    if (name.equals("$pref/default.json")) continue
                                    val resStream = clazz.getResourceAsStream("/$name")
                                    if (resStream != null) {
                                        try {
                                            val theme = ThemeLoader.loadFromStream(resStream)
                                            validateTheme(theme)
                                            bundledThemes[theme.meta.id] = theme
                                            themes.putIfAbsent(theme.meta.id, theme)
                                            logger.info("Loaded bundled theme from JAR resource: $name (id='${theme.meta.id}')")
                                        } catch (ex: Exception) {
                                            logger.warn("Skipping invalid bundled theme resource $name: ${ex.message}")
                                        } finally {
                                            resStream.close()
                                        }
                                    }
                                }
                            }
                        }
                    } catch (ex: Exception) {
                        logger.warn("Could not enumerate bundled themes inside jar: ${ex.message}")
                    }
                }

                else -> {
                    try {
                        val resources = clazz.classLoader.getResources(pref)
                        while (resources.hasMoreElements()) {
                            val url = resources.nextElement()
                            logger.debug("Found classpath resource for themes: {}", url)
                        }
                    } catch (ex: Exception) {
                        logger.warn("Failed to enumerate bundled themes via classloader: ${ex.message}")
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("::loadBundledThemesFromResources failed", e)
        }
    }

    fun setTheme(id: String) {
        val theme = themes[id] ?: run {
            logger.error("Requested theme '{}' not found", id)
            defaultTheme
        }
        iconCache.clear()
        applyTheme(theme)
        currentThemeId = theme.meta.id
        persistSelectedThemeId(currentThemeId)

        val snapshot = ArrayList(listeners)
        runOnGuiThread {
            for(l in snapshot) {
                try { l() } catch (t: Throwable) { logger.warn("Theme listener failed: {}", t.message) }
            }
        }
    }

    private fun applyTheme(theme: ThemeFile) {
        applyStylesheet(theme)
    }

    private fun applyStylesheet(theme: ThemeFile) {
        try {
            val compiled = theme.stylesheets.values.joinToString("\n") { tpl ->
                tpl.replace(Regex("\\$\\{([^}]+)}")) { m ->
                    val key = m.groupValues[0]
                    colorOf(theme, key) ?: "#FF00FF"
                }
            }
            QApplication.setStyle(compiled)
        } catch (e: Exception) {
            logger.error("Failed to apply stylesheet for theme '{}': {}", theme.meta.id, e.message)
        }
    }

    private fun colorOf(theme: ThemeFile, key: String): String? = theme.colors[key]

    fun getIcon(iconKey: String, dpr: Int = 1): QIcon? {
        val theme = themes[currentThemeId] ?: defaultTheme
        val mapping = theme.icons[iconKey] ?: return null
        val cacheKey = Triple(mapping, theme.meta.id, dpr)
        return iconCache[cacheKey] ?: loadIconFromReference(mapping, theme, dpr)?.also { iconCache[cacheKey] = it }
    }

    fun getColorHex(key: String): String? {
        val active = themes[currentThemeId] ?: themes.values.firstOrNull() ?: defaultTheme
        val fromActive = active.colors[key]
        if(!fromActive.isNullOrBlank()) return fromActive
        val fromDefault = defaultTheme.colors[key]
        if(!fromDefault.isNullOrBlank()) return fromDefault
        return null
    }

    fun getQColor(key: String): QColor? {
        val hex = getColorHex(key) ?: return null
        return try {
            QColor(hex)
        } catch (_: Exception) {
            logger.warn("Invalid color value for key '{}': {}", key, hex)
            null
        }
    }

    private fun loadIconFromReference(ref: String, theme: ThemeFile, dpr: Int = 1): QIcon? {
        return try {
            val iconStream: InputStream? = when {
                ref.startsWith("resource:") -> {
                    val r = ref.removePrefix("resource:")
                    this::class.java.getResourceAsStream(r)
                }

                ref.startsWith("/") -> {
                    Files.newInputStream(Paths.get(ref))
                }

                else -> {
                    val p = userThemesDir.resolve(ref)
                    if (Files.exists(p)) Files.newInputStream(p) else {
                        this::class.java.getResourceAsStream("/$ref")
                    }
                }
            }

            if (iconStream == null) {
                logger.warn("Icon reference '$ref' not found (No resource or file).")
                return null
            }

            val raw: ByteArray = iconStream.use { it.readBytes() }

            val peek = String(raw, 0, minOf(raw.size, 512), Charsets.UTF_8).lowercase()
            val isSvg = peek.contains("<svg")

            if (isSvg) {
                var svgText = String(raw, Charsets.UTF_8)

                val patternDollar = Regex("\\$\\{([^}]+)}")
                svgText = svgText.replace(patternDollar) { match ->
                    val key = match.groupValues[1]
                    theme.colors[key] ?: match.value
                }

                val patternToken = Regex("#TOKEN_([A-Za-z0-9_]+)")
                svgText = svgText.replace(patternToken) { match ->
                    val name = match.groupValues[1]
                    val keyTry1 = "Icon.$name"
                    val keyTry2 = name
                    theme.colors[keyTry1] ?: theme.colors[keyTry2] ?: match.value
                }

                if (svgText.contains("currentColor")) {
                    val currentColor = theme.colors["Icon.current"]
                        ?: theme.colors["Icon.primary"]
                        ?: theme.colors["Icon.Primary"]
                    if (currentColor != null) {
                        val svgOpenTagRegex = Regex("<svg([^>]*)>", RegexOption.IGNORE_CASE)
                        val svgOpenMatch = svgOpenTagRegex.find(svgText)
                        if (svgOpenMatch != null) {
                            val attrs = svgOpenMatch.groupValues[1]
                            val styleRegex = Regex("style\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)

                            val newAttrs = if (styleRegex.containsMatchIn(attrs)) {
                                attrs.replace(styleRegex) { innerMatch ->
                                    val existing = innerMatch.groupValues[1]
                                    val updated = if (existing.trim()
                                            .endsWith(";")
                                    ) "$existing color: $currentColor" else "$existing; color: $currentColor"
                                    "style=\"$updated\""
                                }
                            } else {
                                val trimmed = attrs.trimEnd()
                                if (trimmed.isEmpty()) " style=\"color: $currentColor\"" else "$trimmed style=\"color: $currentColor\""
                            }

                            svgText = svgText.replaceRange(svgOpenMatch.range, "<svg$newAttrs>")
                        } else {
                            logger.warn(
                                "SVG contains 'currentColor' but <svg> open tag was not located for ref '{}'",
                                ref
                            )
                        }
                    }
                }

                val renderer = QSvgRenderer(QByteArray(svgText.toByteArray(Charsets.UTF_8)))
                val baseSize = try {
                    val defaultSize = renderer.defaultSize()
                    if (defaultSize.width() <= 0 || defaultSize.height() <= 0) QSize(16 * dpr, 16 * dpr) else QSize(
                        defaultSize.width() * dpr,
                        defaultSize.height() * dpr
                    )
                } catch (_: Throwable) {
                    QSize(16 * dpr, 16 * dpr)
                }

                val pix = QPixmap(baseSize)
                pix.fill(Qt.GlobalColor.transparent)
                val painter = QPainter(pix)
                renderer.render(painter)
                painter.end()
                return QIcon(pix)
            } else {
                val pix = QPixmap()
                val loaded = pix.loadFromData(raw)
                if (!loaded) {
                    logger.warn("Raster icon failed to load from data for ref '{}'", ref)
                    return null
                }
                return QIcon(pix)
            }
        } catch (e: Exception) {
            logger.error("Failed to load icon reference '$ref': ${e.message}")
            null
        } as QIcon?
    }

    private fun startWatcherThread() {
        thread(isDaemon = true, name = "theme-watcher") {
            try {
                val watchService = FileSystems.getDefault().newWatchService()
                userThemesDir.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
                while (true) {
                    val key = watchService.take()
                    for (e in key.pollEvents()) {
                        val kind = e.kind()
                        val wEvent = e as WatchEvent<Path>
                        val fileName = wEvent.context()
                        val full = userThemesDir.resolve(fileName)
                        when (kind) {
                            ENTRY_CREATE -> {
                                try {
                                    val theme = ThemeLoader.loadFromFile(full)
                                    validateTheme(theme)
                                    themes[theme.meta.id] = theme
                                    pathToId[full] = theme.meta.id

                                    val base = theme.meta.base?.let { themes[it] }
                                    themes[theme.meta.id] = ThemeLoader.merge(base, theme)
                                    listeners.forEach { it() }
                                } catch (e: Exception) {
                                    logger.error("Error loading created theme ${fileName}: ${e.message}")
                                }
                            }

                            ENTRY_MODIFY -> {
                                try {
                                    val theme = ThemeLoader.loadFromFile(full)
                                    validateTheme(theme)
                                    themes[theme.meta.id] = theme
                                    pathToId[full] = theme.meta.id
                                    val base = theme.meta.base?.let { themes[it] }
                                    themes[theme.meta.id] = ThemeLoader.merge(base, theme)
                                    listeners.forEach { it() }
                                } catch (e: Exception) {
                                    logger.error("Error reloading modified theme ${fileName}: ${e.message}")
                                }
                            }

                            ENTRY_DELETE -> {
                                val removedId = pathToId.remove(full)
                                if (removedId != null) {
                                    val bundled = bundledThemes[removedId]
                                    if(bundled != null) {
                                        themes[removedId] = bundled
                                        logger.info("User theme removed: Restored bundled theme id='$removedId'")
                                    } else {
                                        themes.remove(removedId)
                                        logger.info("User theme removed and no bundled fallback: id='$removedId' removed")
                                    }

                                    if (removedId == currentThemeId) {
                                        val toApply = themes[removedId] ?: defaultTheme
                                        applyTheme(toApply)
                                        currentThemeId = toApply.meta.id
                                        listeners.forEach { it() }
                                    } else {
                                        listeners.forEach { it() }
                                    }
                                }
                            }
                        }
                    }
                    key.reset()
                }
            } catch (_: InterruptedException) { /* exit */
            } catch (e: Exception) {
                logger.error("Theme watcher failed: ${e.message}")
            }
        }
    }

    private fun validateTheme(theme: ThemeFile) {
        val id = theme.meta.id
        val name = theme.meta.name
        if (id.isBlank()) throw IllegalArgumentException("Theme meta.id must not be blank")
        if (name.isBlank()) throw IllegalArgumentException("Theme meta.name must not be blank")
    }

    private fun generateSchema() {
        try {
            val discoveredColorKeys = mutableSetOf<String>()
            val discoveredIconKeys = mutableSetOf<String>()

            discoveredColorKeys += defaultTheme.colors.keys
            discoveredIconKeys += defaultTheme.icons.keys

            for (t in themes.values) {
                discoveredColorKeys += t.colors.keys
                discoveredIconKeys += t.icons.keys
            }

            val colorValueSchema = buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("pattern", JsonPrimitive("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})\$|^rgba?\\(\\s*\\d{1,3}\\s*,\\s*\\d{1,3}\\s*,\\s*\\d{1,3}(?:\\s*,\\s*(0|1|0?\\.\\d+))?\\s*\\)\$"))
            }

            val colorsProperties = discoveredColorKeys.sorted().associateWith { colorValueSchema as JsonElement }
            val colorsObjectSchema = buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", JsonObject(colorsProperties))
                put("additionalProperties", JsonPrimitive(true))
            }

            val iconValueSchema = buildJsonObject { put("type", JsonPrimitive("string")) }
            val iconsProperties = discoveredIconKeys.sorted().associateWith { iconValueSchema as JsonElement }
            val iconsObjectSchema = buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", JsonObject(iconsProperties))
                put("additionalProperties", JsonPrimitive(true))
            }

            val baseProps = buildPropertiesForDescriptor(ThemeFile.serializer().descriptor).toMutableMap()
            baseProps["colors"] = colorsObjectSchema
            baseProps["icons"] = iconsObjectSchema

            val schemaRoot = buildJsonObject {
                put("\$schema", JsonPrimitive("http://json-schema.org/draft-07/schema#"))
                put("title", JsonPrimitive("Tritium Theme Schema"))
                put("type", JsonPrimitive("object"))
                put("properties", JsonObject(baseProps))
                put("required", JsonArray(listOf(JsonPrimitive("meta"))))
                put("additionalProperties", JsonPrimitive(false))
            }

            Files.createDirectories(userThemesDir)
            val bytes = json.encodeToString(JsonObject.serializer(), schemaRoot).toByteArray()
            Files.write(schemaFile, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            logger.info("Wrote theme schema to $schemaFile with ${discoveredColorKeys.size} color keys and ${discoveredIconKeys.size} icon keys")
        } catch (e: Exception) {
            logger.error("Failed to generate/write theme schema", e)
        }
    }

    private fun buildPropertiesForDescriptor(descriptor: SerialDescriptor): JsonObject {
        val properties = mutableMapOf<String, JsonElement>()
        for (i in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(i)
            val child = descriptor.getElementDescriptor(i)
            properties[name] = schemaForDescriptor(child)
        }
        return JsonObject(properties)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun schemaForDescriptor(descriptor: SerialDescriptor): JsonElement {
        return when (val kind = descriptor.kind) {
            is PrimitiveKind -> when (kind) {
                PrimitiveKind.BOOLEAN -> JsonObject(mapOf("type" to JsonPrimitive("boolean")))
                PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG ->
                    JsonObject(mapOf("type" to JsonPrimitive("integer")))

                PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE ->
                    JsonObject(mapOf("type" to JsonPrimitive("number")))

                PrimitiveKind.CHAR, PrimitiveKind.STRING ->
                    JsonObject(mapOf("type" to JsonPrimitive("string")))

            }

            is StructureKind -> when (kind) {
                StructureKind.LIST -> {
                    val elementDesc = descriptor.getElementDescriptor(0)
                    JsonObject(mapOf("type" to JsonPrimitive("array"), "items" to schemaForDescriptor(elementDesc)))
                }

                StructureKind.MAP -> {
                    val valueDesc = descriptor.getElementDescriptor(1)
                    JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("object"),
                            "additionalProperties" to schemaForDescriptor(valueDesc)
                        )
                    )
                }

                StructureKind.CLASS, StructureKind.OBJECT -> {
                    val props = mutableMapOf<String, JsonElement>()
                    val required = mutableListOf<JsonElement>()
                    for (i in 0 until descriptor.elementsCount) {
                        val nm = descriptor.getElementName(i)
                        val d = descriptor.getElementDescriptor(i)
                        props[nm] = schemaForDescriptor(d)
                        val isOptional = descriptor.isElementOptional(i)
                        val isNullable = d.isNullable
                        if (!isOptional && !isNullable) required.add(JsonPrimitive(nm))
                    }
                    val map = mutableMapOf(
                        "type" to JsonPrimitive("object"),
                        "properties" to JsonObject(props)
                    )
                    if (required.isNotEmpty()) map["required"] = JsonArray(required)
                    JsonObject(map)
                }

            }

            is PolymorphicKind -> {
                JsonObject(mapOf("type" to JsonPrimitive("object")))
            }

            else -> {
                if (kind == SerialKind.ENUM) {
                    val choices = descriptor.elementNames.map { JsonPrimitive(it) }
                    JsonObject(mapOf("type" to JsonPrimitive("string"), "enum" to JsonArray(choices)))
                } else {
                    JsonObject(mapOf("type" to JsonPrimitive("string")))
                }
            }
        }
    }

    fun availableThemeIds(): List<String> = themes.keys.toList()

    private fun persistSelectedThemeId(id: String) {
        try {
            prefs.put(PREF_SELECTED_THEME, id)
            prefs.flush()
            logger.debug("Persisted selected theme id='{}'", id)
        } catch (e: Exception) {
            logger.warn("Failed to persist selected theme id='{}': {}", id, e.message)
        }
    }

    private fun loadPersistedThemeId(): String? = try {
        prefs.get(PREF_SELECTED_THEME, null)
    } catch (e: Exception) {
        logger.warn("Failed to read persisted selected theme id: {}", e.message)
        null
    }

    private fun restorePersistedSelectionIfAny() {
        val persisted = loadPersistedThemeId()
        if(!persisted.isNullOrBlank() && themes.containsKey(persisted)) {
            try {
                setTheme(persisted)
                logger.info("Restored persisted theme id='{}'", persisted)
            } catch (e: Exception) {
                logger.warn("Failed to restore persisted theme id='{}': {}", persisted, e.message)
            }
        }
    }
}
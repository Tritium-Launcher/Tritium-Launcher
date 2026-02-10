package io.github.footermandev.tritium.platform

import io.github.footermandev.tritium.accounts.MicrosoftAuth
import io.github.footermandev.tritium.accounts.ProfileMngr
import io.github.footermandev.tritium.core.modloader.LaunchContext
import io.github.footermandev.tritium.core.modloader.ModLoader
import io.github.footermandev.tritium.core.project.ModpackMeta
import io.github.footermandev.tritium.core.project.ProjectBase
import io.github.footermandev.tritium.io.VPath
import io.github.footermandev.tritium.koin.getRegistry
import io.github.footermandev.tritium.logger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File

/**
 * Responsible for preparing and launching a Minecraft instance for a project:
 * - Ensures base MC + loader assets exist
 * - Builds merged version JSON + classpath/arguments
 * - Spawns the JVM process and streams early output
 */
object GameLauncher {
    private val logger = logger()
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val pathSeparator = File.pathSeparator ?: ":"

    /**
     * Public entry point for launching a project; executes asynchronously on IO scope.
     */
    fun launch(project: ProjectBase) {
        scope.launch {
            try {
                launchInternal(project)
            } catch (t: Throwable) {
                logger.error("Launch failed for {}", project.name, t)
            }
        }
    }

    /**
     * Resolve metadata, ensure required files, and start the game process for a modpack project.
     */
    private suspend fun launchInternal(project: ProjectBase) {
        if (project.typeId != "modpack") {
            logger.warn("Launch is only supported for modpack projects (type={})", project.typeId)
            return
        }

        val metaFile = project.projectDir.resolve("trmodpack.json")
        val metaText = metaFile.readTextOrNull()
        if (metaText.isNullOrBlank()) {
            logger.warn("Cannot launch; trmodpack.json missing at {}", metaFile.toAbsolute())
            return
        }

        val meta = runCatching { json.decodeFromString(ModpackMeta.serializer(), metaText) }.getOrNull()
        if (meta == null) {
            logger.warn("Cannot launch; failed to parse trmodpack.json at {}", metaFile.toAbsolute())
            return
        }

        val mcVersion = meta.minecraftVersion
        val loaderId = meta.loader
        val loaderVersion = meta.loaderVersion
        val mergedId = "$mcVersion-$loaderId-$loaderVersion"

        val loader = getRegistry<ModLoader>("core.mod_loader").all().find { it.id == loaderId }
        if (loader == null) {
            logger.warn("No mod loader registered for id={}", loaderId)
            return
        }

        if (!ensureBaseAndLoader(meta, project.projectDir, loader)) {
            logger.warn("Cannot launch; required game files not ready for {}", project.name)
            return
        }

        val versionsDir = project.projectDir.resolve(".tr").resolve("versions")
        val mergedJson = versionsDir.resolve(mergedId).resolve("$mergedId.json")
        val baseJson = versionsDir.resolve(mcVersion).resolve("$mcVersion.json")
        val versionJsonFile = if (mergedJson.exists()) mergedJson else baseJson
        val versionJsonText = versionJsonFile.readTextOrNull()
        if (versionJsonText.isNullOrBlank()) {
            logger.warn("Cannot launch; version json missing at {}", versionJsonFile.toAbsolute())
            return
        }

        val versionObj = json.parseToJsonElement(versionJsonText).jsonObject
        val mainClass = readMainClass(versionObj)
        if (mainClass.isNullOrBlank()) {
            logger.warn("Cannot launch; mainClass missing in {}", versionJsonFile.toAbsolute())
            return
        }

        val classpathEntries = buildClasspathEntries(project.projectDir, versionObj, mergedId, mcVersion)
        val context = LaunchContext(project.projectDir, mcVersion, loaderVersion, mergedId, versionObj)
        loader.prepareLaunchClasspath(context, classpathEntries)
        if (classpathEntries.isEmpty()) {
            logger.warn("Cannot launch; classpath is empty for {}", project.name)
            return
        }
        val classpath = classpathEntries.joinToString(pathSeparator)
        val assetsDir = project.projectDir.resolve(".tr").resolve("assets")
        val assetIndexId = versionObj["assetIndex"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull ?: "legacy"
        val nativesDir = project.projectDir.resolve(".tr").resolve("natives").resolve(mcVersion)

        val profile = ProfileMngr.Cache.get()
        val username = profile?.name
        val uuid = profile?.id
        var accessToken = ProfileMngr.Cache.getToken()
        if (accessToken.isNullOrBlank()) {
            accessToken = MicrosoftAuth.getActiveMcTokenOrNull()
        }

        if (username.isNullOrBlank() || uuid.isNullOrBlank() || accessToken.isNullOrBlank()) {
            logger.warn("Cannot launch; missing account info (username={}, uuid={}, token={})", username, uuid, accessToken != null)
            return
        }

        val javaExec = findJava21()
        if (javaExec == null) {
            logger.warn("Cannot launch; Java 21 not found on PATH or JAVA_HOME")
            return
        }

        val gameArgs = buildGameArgs(versionObj, project.projectDir, assetsDir, assetIndexId, mcVersion, username, uuid, accessToken, mergedId)
        val jvmArgs = buildJvmArgs(versionObj, project.projectDir, nativesDir, classpath).toMutableList()
        loader.prepareLaunchJvmArgs(context, classpathEntries, jvmArgs)
        if (loader.shouldStripMinecraftClientArtifacts(context)) {
            stripMinecraftArtifactsFromModulePath(jvmArgs)
        }
        val finalJvmArgs = ensureClasspathArg(stripUnsupportedArgs(jvmArgs), classpath)

        val command = mutableListOf<String>()
        command += javaExec.toAbsolute().toString()
        command += listOf(
            "-Xms1G",
            "-Xmx2G"
        )
        command += finalJvmArgs
        // Strip duplicate main-jar entries from the classpath
        val cpEntries = classpathEntries.filter { it.isNotBlank() }
        val dedupCp = cpEntries.distinct()
        val classpathToUse = dedupCp.joinToString(pathSeparator)
        if (!finalJvmArgs.contains("-cp")) {
            command += listOf("-cp", classpathToUse)
        }
        command += mainClass
        command += gameArgs

        logger.info("Launching Minecraft: {}", command.joinToString(" "))
        logger.info("Classpath entries ({}): {}", dedupCp.size, dedupCp.joinToString(", "))
        logger.info("JVM args ({}): {}", finalJvmArgs.size, finalJvmArgs.joinToString(" "))
        logger.info("Game args ({}): {}", gameArgs.size, gameArgs.joinToString(" "))
        try {
            val process = withContext(Dispatchers.IO) {
                ProcessBuilder(command)
                    .directory(project.projectDir.toJFile())
                    .redirectErrorStream(true)
                    .start()
            }

            // Stream child output to logs for early failures
            scope.launch(Dispatchers.IO) {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.take(400).forEach { line ->
                            logger.info("MC: {}", line)
                        }
                    }
                } catch (t: Throwable) {
                    logger.debug("Failed reading MC output", t)
                }
            }

            scope.launch(Dispatchers.IO) {
                val exit = process.waitFor()
                logger.info("Minecraft exited with code {}", exit)
            }
        } catch (t: Throwable) {
            logger.error("Failed to start Minecraft process", t)
        }
    }

    /**
     * TODO: This is currently hardcoded to Java 21 versions of MC, it will not be forever when Settings are implemented.
     */
    private fun findJava21(): VPath? {
        val candidates = Java.locateJavaExecutablesWithVersion()
        val match = candidates.firstOrNull { (ver, _) -> ver.startsWith("21") }
        return match?.second?.let { VPath.parse(it) }
    }

    /**
     * Build the launch classpath entries from the merged version JSON (libraries + main jar)
     */
    private fun buildClasspathEntries(projectDir: VPath, versionObj: JsonObject, mergedId: String, mcVersion: String): MutableList<String> {
        val libsDir = projectDir.resolve(".tr").resolve("libraries")
        val libs = versionObj["libraries"]?.jsonArray ?: JsonArray(emptyList())
        val entries = mutableListOf<String>()
        for (el in libs) {
            val obj = el as? JsonObject ?: continue
            if(!rulesAllow(obj["rules"]?.jsonArray)) continue
            val path = obj["downloads"]?.jsonObject
                ?.get("artifact")?.jsonObject
                ?.get("path")?.jsonPrimitive?.contentOrNull
                ?: obj["name"]?.jsonPrimitive?.contentOrNull?.let { mavenPath(it) }
            if (path != null) {
                // Avoid Minecraft client artifacts, launch with the merged jar
                if (path.contains("net/minecraft/client/")) continue
                if (!path.endsWith(".jar")) continue
                val jar = libsDir.resolve(path)
                if (jar.exists()) entries.add(jar.toAbsolute().toString())
            }
        }

        val versionsDir = projectDir.resolve(".tr").resolve("versions")
        val mergedJar = versionsDir.resolve(mergedId).resolve("$mergedId.jar")
        val baseJar = versionsDir.resolve(mcVersion).resolve("$mcVersion.jar")
        val mainJar = if (mergedJar.exists()) mergedJar else baseJar
        if (mainJar.exists()) entries.add(0, mainJar.toAbsolute().toString())

        return entries
    }

    /**
     * Construct Minecraft game arguments from the merged version JSON, replacing tokens
     * and stripping quickplay/demo/placeholder entries
     */
    private fun buildGameArgs(
        versionObj: JsonObject,
        gameDir: VPath,
        assetsDir: VPath,
        assetIndexId: String,
        mcVersion: String,
        username: String,
        uuid: String,
        accessToken: String,
        mergedId: String
    ): List<String> {
        val replacements = mapOf(
            "\${auth_player_name}" to username,
            "\${version_name}" to mergedId,
            "\${game_directory}" to gameDir.toAbsolute().toString(),
            "\${assets_root}" to assetsDir.toAbsolute().toString(),
            "\${assets_index_name}" to assetIndexId,
            "\${auth_uuid}" to uuid,
            "\${auth_access_token}" to accessToken,
            "\${user_type}" to "msa",
            "\${version_type}" to "release",
            "\${launcher_name}" to "Tritium",
            "\${launcher_version}" to "0.0.0",
            "\${clientid}" to "",
            "\${auth_xuid}" to "",
            "\${resolution_width}" to "1280",
            "\${resolution_height}" to "720",
            "\${quickPlayPath}" to ""
        )

        val args = mutableListOf<String>()
        val argumentsObj = versionObj["arguments"]?.jsonObject
        if (argumentsObj != null) {
            val gameArr = argumentsObj["game"]?.jsonArray ?: JsonArray(emptyList())
            for (entry in gameArr) {
                args += parseArgEntry(entry, replacements)
            }
            return stripUnsupportedArgs(removeQuickPlayArgs(args))
        }

        val legacy = versionObj["minecraftArguments"]?.jsonPrimitive?.contentOrNull
        if (!legacy.isNullOrBlank()) {
            val split = legacy.split(" ")
            split.filter { it.isNotBlank() }.forEach { args += replaceTokens(it, replacements) }
            return stripUnsupportedArgs(removeQuickPlayArgs(args))
        }

        args += listOf(
            "--username", username,
            "--version", mergedId,
            "--gameDir", gameDir.toAbsolute().toString(),
            "--assetsDir", assetsDir.toAbsolute().toString(),
            "--assetIndex", assetIndexId,
            "--uuid", uuid,
            "--accessToken", accessToken,
            "--userType", "msa",
            "--versionType", "release"
        )
        return stripUnsupportedArgs(removeQuickPlayArgs(args))
    }

    /**
     * Build JVM arguments from the version JSON, applying token replacements.
     */
    private fun buildJvmArgs(versionObj: JsonObject, gameDir: VPath, nativesDir: VPath, classpath: String): List<String> {
        val args = mutableListOf<String>()
        args += "-Djava.library.path=${nativesDir.toAbsolute()}"

        val argumentsObj = versionObj["arguments"]?.jsonObject
        if (argumentsObj != null) {
            val mergedId = findMergedIdFromClasspath(classpath)
            val replacements = mapOf(
                "\${natives_directory}" to nativesDir.toAbsolute().toString(),
                "\${classpath_separator}" to pathSeparator,
                "\${library_directory}" to gameDir.resolve(".tr").resolve("libraries").toAbsolute().toString(),
                "\${classpath}" to classpath,
                "\${launcher_name}" to "Tritium",
                "\${launcher_version}" to "0.0.0",
                "\${version_name}" to (mergedId ?: ""),
                "\${version_id}" to (mergedId ?: "")
            )
            val jvmArr = argumentsObj["jvm"]?.jsonArray ?: JsonArray(emptyList())
            for (entry in jvmArr) {
                args += parseArgEntry(entry, replacements)
            }
        }

        return args
    }

    /**
     * Interpret an argument entry from the version JSON (supports rules + arrays).
     */
    private fun parseArgEntry(entry: JsonElement, replacements: Map<String, String>): List<String> {
        val primitive = entry.jsonPrimitiveOrNull()
        if (primitive != null && !primitive.isString) return emptyList()
        if (primitive != null && primitive.isString) {
            return listOf(replaceTokens(primitive.content, replacements))
        }

        val obj = entry as? JsonObject ?: return emptyList()
        val rules = obj["rules"]?.jsonArray
        if(!rulesAllow(rules)) return emptyList()
        val valueEl = obj["value"] ?: return emptyList()
        return when (valueEl) {
            is JsonPrimitive -> listOf(replaceTokens(valueEl.content, replacements))
            is JsonArray -> valueEl.mapNotNull { it.jsonPrimitiveOrNull()?.contentOrNull }
                .map { replaceTokens(it, replacements) }
            else -> emptyList()
        }
    }

    /**
     * Evaluate rule blocks from version JSON to decide whether an argument/library applies.
     */
    private fun rulesAllow(rules: JsonArray?): Boolean {
        if (rules == null) return true
        var allowed = false
        for (ruleEl in rules) {
            val rule = ruleEl as? JsonObject ?: continue
            val action = rule["action"]?.jsonPrimitive?.contentOrNull ?: "allow"
            val osObj = rule["os"]?.jsonObject
            if (osObj != null && !osMatches(osObj)) continue
            allowed = action == "allow"
        }
        return allowed
    }

    /**
     * Check whether the supplied OS rule matches the current platform.
     */
    private fun osMatches(os: JsonObject): Boolean {
        val name = os["name"]?.jsonPrimitive?.contentOrNull
        val arch = os["arch"]?.jsonPrimitive?.contentOrNull
        if (name != null) {
            val ok = when (name) {
                "windows" -> Platform.isWindows
                "osx" -> Platform.isMacOS
                "linux" -> Platform.isLinux
                else -> false
            }
            if (!ok) return false
        }
        if (arch != null) {
            val a = System.getProperty("os.arch") ?: ""
            val is64 = a.contains("64")
            if (arch == "x86" && is64) return false
            if (arch == "x64" && !is64) return false
        }
        return true
    }

    /**
     * Replace argument tokens with resolved values.
     */
    private fun replaceTokens(value: String, replacements: Map<String, String>): String {
        var out = value
        for ((k, v) in replacements) {
            out = out.replace(k, v)
        }
        return out
    }

    /**
     * Remove quick play arguments (and their values) from the launch list.
     */
    private fun removeQuickPlayArgs(args: List<String>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            val isQuickPlay = a == "--quickPlaySingleplayer" || a == "--quickPlayMultiplayer" || a == "--quickPlayRealms"
            if (isQuickPlay) {
                i += if (i + 1 < args.size) 2 else 1
                continue
            }
            out.add(a)
            i++
        }
        return out
    }

    /**
     * Remove unsupported or unresolved arguments before launch.
     */
    private fun stripUnsupportedArgs(args: List<String>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a == "--demo") { i++; continue }
            if (a.contains("\${")) { i++; continue }
            if (a == "--quickPlayPath") { i += if (i + 1 < args.size) 2 else 1; continue }
            out.add(a)
            i++
        }
        return out
    }

    /**
     * Ensure an existing classpath argument has a valid value, if present.
     */
    private fun ensureClasspathArg(args: List<String>, classpath: String): List<String> {
        val out = args.toMutableList()
        val idx = out.indexOf("-cp")
        if (idx >= 0) {
            val next = out.getOrNull(idx + 1)
            if (next.isNullOrBlank()) {
                if (idx + 1 < out.size) out[idx + 1] = classpath else out.add(classpath)
            }
            return out
        }
        return out
    }

    /**
     * Remove slim/srg client artifacts from the JVM module path argument.
     */
    private fun stripMinecraftArtifactsFromModulePath(jvmArgs: MutableList<String>) {
        val pIdx = jvmArgs.indexOf("-p")
        if (pIdx < 0 || pIdx + 1 >= jvmArgs.size) return
        val current = jvmArgs[pIdx + 1]
        val cleaned = removeMinecraftSlimArtifacts(current)
        if (cleaned != current) {
            jvmArgs[pIdx + 1] = cleaned
        }
    }

    /**
     * Extract the merged version id from a classpath string when present.
     */
    private fun findMergedIdFromClasspath(classpath: String): String? {
        val entries = classpath.split(pathSeparator)
        for (entry in entries) {
            val path = VPath.parse(entry)
            val segments = path.segments()
            val idx = segments.indexOf(".tr")
            if (idx >= 0 && segments.getOrNull(idx + 1) == "versions") {
                val fileName = path.fileName()
                if (fileName.endsWith(".jar")) return fileName.removeSuffix(".jar")
            }
        }
        return null
    }

    /**
     * Remove slim/srg artifacts from a module path list.
     */
    private fun removeMinecraftSlimArtifacts(paths: String): String {
        if (paths.isBlank()) return paths
        return paths.split(pathSeparator)
            .filterNot { it.contains("net/minecraft/client/") && (it.contains("-srg.jar") || it.contains("-slim.jar") || it.contains("-extra.jar")) }
            .joinToString(pathSeparator)
    }

    /**
     * Remove cached Minecraft intermediates for non-NeoForge loaders.
     */
    private fun cleanupMinecraftIntermediates(projectDir: VPath, mcVersion: String, loaderId: String) {
        if (loaderId == "neoforge") return
        val clientRoot = projectDir.resolve(".tr").resolve("libraries").resolve("net/minecraft/client")
        if (!clientRoot.exists()) return
        val dirs = clientRoot.listFiles { it.isDir() && it.fileName().startsWith(mcVersion) }
        for (dir in dirs) {
            val toDelete = dir.listFiles { path ->
                val name = path.fileName()
                name.contains("-srg") || name.contains("-slim") || name.contains("-extra") || name.endsWith(".cache")
            }
            toDelete.forEach { f ->
                try {
                    if (f.delete()) {
                        logger.info("Removed intermediate MC artifact {}", f.toAbsolute())
                    }
                } catch (_: Throwable) {
                    logger.debug("Failed to remove intermediate MC artifact {}", f.toAbsolute())
                }
            }
        }
    }

    /**
     * Convert a Maven coordinate into a repository path.
     */
    private fun mavenPath(name: String): String {
        val parts = name.split(":")
        if (parts.size < 3) return name
        val group = parts[0].replace('.', '/')
        val artifact = parts[1]
        val version = parts[2]
        val classifier = parts.getOrNull(3)
        val file = if (classifier != null) "$artifact-$version-$classifier.jar" else "$artifact-$version.jar"
        return "$group/$artifact/$version/$file"
    }

    /**
     * Return the JsonPrimitive if the element is a primitive, otherwise null.
     */
    private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? {
        return this as? JsonPrimitive
    }

    /**
     * Read a mainClass which may be a string or an object keyed by side.
     */
    private fun readMainClass(versionObj: JsonObject): String? {
        val el = versionObj["mainClass"] ?: return null
        val primitive = el as? JsonPrimitive
        if (primitive != null) return primitive.contentOrNull
        val obj = el as? JsonObject ?: return null
        return obj["client"]?.jsonPrimitive?.contentOrNull
            ?: obj["main"]?.jsonPrimitive?.contentOrNull
            ?: obj["default"]?.jsonPrimitive?.contentOrNull
    }

    /**
     * Ensure the base Minecraft version and selected loader are installed for the project,
     * writing a merged version JSON used for launching.
     */
    private suspend fun ensureBaseAndLoader(meta: ModpackMeta, projectDir: VPath, loader: ModLoader): Boolean {
        val mcVersion = meta.minecraftVersion
        val loaderId = meta.loader
        val loaderVersion = meta.loaderVersion

        val baseJson = projectDir.resolve(".tr").resolve("versions").resolve(mcVersion).resolve("$mcVersion.json")
        if (!baseJson.exists()) {
            logger.info("Missing base version json; downloading Minecraft {}", mcVersion)
            if (!MicrosoftAuth.setupMinecraftInstance(mcVersion, projectDir)) {
                logger.warn("Failed to setup Minecraft {}", mcVersion)
                return false
            }
        }

        logger.info("Ensuring loader installed: {} {}", loaderId, loaderVersion)
        if (!loader.installClient(loaderVersion, mcVersion, projectDir)) {
            logger.warn("Loader install failed for {} {}", loaderId, loaderVersion)
            return false
        }

        cleanupMinecraftIntermediates(projectDir, mcVersion, loaderId)

        val merged = MicrosoftAuth.writeMergedVersionJson(mcVersion, loaderId, loaderVersion, projectDir)
        if (merged == null || !merged.exists()) {
            logger.warn("Merged version json missing for {} {}", loaderId, loaderVersion)
            return false
        }

        return true
    }
}

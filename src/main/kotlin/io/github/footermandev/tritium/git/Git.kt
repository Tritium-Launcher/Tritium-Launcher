package io.github.footermandev.tritium.git

import io.github.footermandev.tritium.extension.core.CoreSettingKeys
import io.github.footermandev.tritium.git.Git.findGitExecutable
import io.github.footermandev.tritium.io.VPath
import io.github.footermandev.tritium.logger
import io.github.footermandev.tritium.platform.Platform
import io.github.footermandev.tritium.settings.SettingsMngr

/**
 * General entrypoint for Git-related actions.
 */
object Git {
    internal val logger = logger()
    private val gitPathSettingKey = CoreSettingKeys.GitPath
    var gitExecExists: Boolean = false
    private var gitPath: VPath? = null

    fun findGitExecutable(): VPath? {
        val cmd = if(Platform.isWindows) {
            arrayOf("where", "git")
        } else {
            arrayOf("which", "git")
        }

        return try {
            val process = ProcessBuilder(*cmd)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()

            if(output.isNotEmpty()) {
                logger.info("Found Git executable: $output")
                VPath.parse(output)
            } else {
                logger.warn("Git is not installed")
                null
            }
        } catch (e: Exception) {
            logger.warn("Error finding Git Executable", e)
            null
        }
    }

    internal fun init() {
        resolveGitExecutable()
    }

    /**
     * Initialize a git repository in the given directory.
     * @return true on success, false otherwise.
     */
    fun initRepo(dir: VPath): Boolean {
        val git = resolveGitExecutable()
        if(git == null) {
            logger.warn("Cannot init repo; git executable not found")
            return false
        }
        val folder = dir.toJFile()
        val folderPath = dir.toAbsolute().toString()
        return try {
            val pb = ProcessBuilder(git.toAbsolute().toString(), "init")
                .directory(folder)
                .redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            val exit = proc.waitFor()
            if(exit != 0) {
                logger.warn("git init failed (exit $exit): $output")
                false
            } else {
                logger.info("Initialized git repo at {}", folderPath)
                true
            }
        } catch (t: Throwable) {
            logger.warn("Failed to init git repo at {}", folderPath, t)
            false
        }
    }

    /**
     * Resolves the best available git executable path and refreshes cached availability state.
     *
     * @return Absolute executable path, or `null` when unavailable.
     * @see findGitExecutable
     */
    private fun resolveGitExecutable(): VPath? {
        val configured = configuredGitExecutable()
        if (configured != null) {
            gitExecExists = true
            gitPath = configured
            return configured
        }

        val cached = gitPath?.toAbsolute()?.takeIf { it.exists() }
        if (cached != null) {
            gitExecExists = true
            return cached
        }

        val detected = findGitExecutable()?.toAbsolute()?.takeIf { it.exists() }
        if (detected == null) {
            gitExecExists = false
            gitPath = null
            return null
        }

        val previous = gitPath?.toAbsolute()
        gitExecExists = true
        gitPath = detected
        if (previous != detected) {
            maybeSuggestGitPathSetting(detected.toString())
        }
        return detected
    }

    /**
     * Resolves a user-configured git executable path from settings.
     *
     * @return Absolute configured executable path, or `null` when unset/invalid.
     */
    private fun configuredGitExecutable(): VPath? {
        val configuredValue = SettingsMngr.currentValueOrNull(gitPathSettingKey) as? String ?: return null
        val trimmed = configuredValue.trim()
        if (trimmed.isEmpty()) return null

        val configuredPath = try {
            VPath.parse(trimmed).toAbsolute()
        } catch (t: Throwable) {
            logger.warn("Configured Git path is invalid: {}", trimmed, t)
            return null
        }

        if (!configuredPath.exists() || !configuredPath.isFile()) {
            logger.warn("Configured Git path does not exist or is not a file: {}", configuredPath)
            return null
        }
        return configuredPath
    }

    /**
     * Publishes a value suggestion for the git-path setting when it is present but blank.
     *
     * @param detectedPath Auto-detected git executable path.
     * @see SettingsMngr.suggestValue
     */
    private fun maybeSuggestGitPathSetting(detectedPath: String) {
        val setting = SettingsMngr.findSetting(gitPathSettingKey) ?: return
        val current = SettingsMngr.currentValueOrNull(gitPathSettingKey)
        if (current != null && current !is String) return
        if (current is String && current.isNotBlank()) return

        SettingsMngr.suggestValue(
            key = gitPathSettingKey,
            suggestedValue = detectedPath,
            reason = "Git executable was auto-detected.",
            source = "git:auto-detect"
        )
        logger.debug("Published git-path suggestion for {}", setting.key)
    }
}

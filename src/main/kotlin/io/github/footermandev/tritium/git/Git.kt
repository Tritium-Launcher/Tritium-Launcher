package io.github.footermandev.tritium.git

import io.github.footermandev.tritium.io.VPath
import io.github.footermandev.tritium.logger
import io.github.footermandev.tritium.platform.Platform

/**
 * General entrypoint for Git-related actions.
 */
object Git {
    internal val logger = logger()
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
        val gitExec = findGitExecutable()
        if(gitExec == null || !gitExec.exists()) {
            gitExecExists = false
            return
        }
        gitExecExists = true
        gitPath = gitExec.toAbsolute()
    }

    /**
     * Initialize a git repository in the given directory.
     * @return true on success, false otherwise.
     */
    fun initRepo(dir: VPath): Boolean {
        if(!gitExecExists) {
            logger.warn("Cannot init repo; git executable not found")
            return false
        }
        val git = gitPath ?: findGitExecutable() ?: return false
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
}

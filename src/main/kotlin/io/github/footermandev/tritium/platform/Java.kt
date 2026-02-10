package io.github.footermandev.tritium.platform

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * General entrypoint for Java-related actions.
 */
object Java {
    fun test(path: String): Boolean {
        val exec = if(Platform.isWindows) {
            File(path, "bin\\java.exe")
        } else {
            File(path, "bin/java")
        }

        if(!exec.exists() || !exec.canExecute()) return false

        return try {
            val process = ProcessBuilder(exec.absolutePath, "-version")
                .redirectErrorStream(true)
                .start()

            if(!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroy()
                return false
            }

            val output = process.inputStream.bufferedReader().readText()
            process.exitValue() == 0 && output.contains("java version", ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    fun testForVersion(executable: File): String? {
        return try {
            val process = ProcessBuilder(executable.absolutePath, "-version")
                .redirectErrorStream(true)
                .start()

            if(!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroy()
                null
            } else {
                val output = process.inputStream.bufferedReader().readText() ?: return null
                val regex = Regex("version \"([^\"]+)\"")
                regex.find(output)?.groupValues?.get(1)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun locateJavaExecutablesWithVersion(): List<Pair<String, String>> {
        val foundExecutables = mutableSetOf<File>()

        val pathEnv = System.getenv("PATH") ?: ""
        val pathSeparator = File.pathSeparator
        val dirs = pathEnv.split(pathSeparator)
        for(dir in dirs) {
            val candidate = File(dir, "java")
            if(candidate.exists() && candidate.canExecute()) {
                foundExecutables.add(candidate.absoluteFile)
            }
        }

        System.getenv("JAVA_HOME")?.let { javaHome ->
            val candidate = File(javaHome, "bin/java")
            if(candidate.exists() && candidate.canExecute()) {
                foundExecutables.add(candidate.absoluteFile)
            }
        }

        // Common Java install locations (SDKMAN, system JVM dirs, MacOSX, Windows)
        val home = System.getProperty("user.home") ?: ""
        val commonHomes = listOfNotNull(
            if(home.isNotBlank()) File(home, ".sdkman/candidates/java") else null,
            File("/usr/lib/jvm"),
            File("/usr/java"),
            File("/opt/java"),
            File("/Library/Java/JavaVirtualMachines"),
            File("C:\\Program Files\\Java"),
            File("C:\\Program Files (x86)\\Java")
        )

        for(root in commonHomes) {
            if(!root.exists() || !root.isDirectory) continue
            val children = root.listFiles() ?: continue
            for(dir in children) {
                if(!dir.isDirectory) continue
                val candidate = if(Platform.isWindows) {
                    File(dir, "bin\\java.exe")
                } else {
                    // MacOSX .jdk bundles keep binaries under Contents/Home/bin
                    val macCandidate = File(dir, "Contents/Home/bin/java")
                    if(macCandidate.exists() && macCandidate.canExecute()) macCandidate else File(dir, "bin/java")
                }
                if(candidate.exists() && candidate.canExecute()) {
                    foundExecutables.add(candidate.absoluteFile)
                }
            }
        }

        val results = mutableListOf<Pair<String, String>>()
        for(exe in foundExecutables) {
            val ver = testForVersion(exe)
            if(ver != null) results.add(Pair(ver, exe.absolutePath))
        }

        return results
    }
}

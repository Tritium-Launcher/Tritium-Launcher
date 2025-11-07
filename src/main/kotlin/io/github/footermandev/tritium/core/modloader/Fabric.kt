package io.github.footermandev.tritium.core.modloader

import com.google.auto.service.AutoService
import io.github.footermandev.tritium.core.modloader.fabric.FabricLoaderVersion
import io.github.footermandev.tritium.logger
import io.github.footermandev.tritium.toURI
import io.github.footermandev.tritium.ui.theme.TIcons
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import io.qt.gui.QPixmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Fabric Mod Loader
 */

class Fabric : ModLoader() {
    override val id: String = "fabric"
    override val displayName: String = "Fabric"
    override val repository: URI = "https://maven.fabricmc.net/net/fabricmc/fabric-loader".toURI()
    override val oldestVersion: String = "1.14"
    override val icon: QPixmap
        get() = TIcons.Fabric

    val dir = File(INSTALL_DIR, id)
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
    private val logger = logger()

    init {
        if(!dir.exists()) {
            if(dir.mkdirs()) {
                logger.info("Created Fabric install directory: ${dir.absolutePath}")
            } else {
                logger.error("Failed to create Fabric install directory: ${dir.absolutePath}")
            }
        }
    }

    override suspend fun download(version: String): Boolean {
        return try {
            val downloadUri = getDownloadUrl(version)
            if(downloadUri == null) {
                logger.error("Download URI for version {} not found.", version)
                return false
            }
            val urlStr = downloadUri.toString()
            logger.info("Downloading Fabric Loader...")
            val data: ByteArray = client.get(urlStr).bodyAsBytes()
            val file = File(dir, "fabric-loader-$version.jar")
            file.writeBytes(data)
            logger.info("Downloaded Fabric Loader version {}", version)
            true
        } catch (e: Exception) {
            logger.error("Error downloading Fabric Loader {}: {}", version, e.message, e)
            false
        }
    }

    override suspend fun uninstall(version: String): Boolean {
        return try {
            val file = File(dir, "fabric-loader-$version.jar")
            if(file.exists()) {
                if(file.delete()) {
                    logger.info("Deleted Fabric Loader {}", version)
                    true
                } else {
                    logger.error("Failed to delete Fabric Loader {}", version)
                    false
                }
            } else {
                logger.warn("Fabric Loader {} not found for removal.", version)
                false
            }
        } catch (e: Exception) {
            logger.error("Error uninstalling Fabric Loader {}: {}", version, e.message, e)
            false
        }
    }

    override suspend fun getVersions(): List<String> {
        return try {
            val metadataUrl = repository.toString() + "net/fabricmc/fabric-loader/maven-metadata.xml"
            val xmlContent: String = client.get(metadataUrl).bodyAsText()
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val inputStream = xmlContent.byteInputStream()
            val doc = withContext(Dispatchers.IO) {
                builder.parse(inputStream)
            }
            doc.documentElement.normalize()
            val nodes = doc.getElementsByTagName("version")
            val versions = mutableListOf<String>()
            for (i in 0 until nodes.length) {
                versions.add(nodes.item(i).textContent)
            }
            logger.info("Retrieved {} versions from metadata.", versions.size)
            versions
        } catch (e: Exception) {
            logger.error("Error fetching versions: {}", e.message, e)
            emptyList()
        }
    }

    override suspend fun getCompatibleVersions(version: String): List<String> {
        return try {
            val response: HttpResponse = client.get(
                "https://meta.fabricmc.net/v2/versions/loader"
            ) {
                parameter("game", version)
            }
            val versions: List<FabricLoaderVersion> = response.body()
            logger.info("Retrieved {} Fabric Loader versions for MC $version.", versions.size)

            if(versions.isEmpty()) {
                return listOf("No available versions.")
            }
            versions.map { it.version }
        } catch (e: Exception) {
            logger.error("Error fetching Fabric Loader versions for MC $version: ${e.message}")
            emptyList()
        }
    }

    override fun isInstalled(version: String): Boolean {
        val file = File(INSTALL_DIR, "fabric-loader-$version.jar")
        val exists = file.exists()
        logger.debug("Checking if Fabric Loader {} is installed: {}", version, exists)
        return exists
    }

    override fun getInstalled(): List<String> {
        val files = INSTALL_DIR.listFiles { f ->
            f.name.startsWith("fabric-loader-") && f.name.endsWith(".jar")
        }
        val versions = files?.map { it.name.removePrefix("fabric-loader-").removeSuffix(".jar") } ?: emptyList()
        logger.debug("Installed versions: {}", versions)
        return versions
    }

    override suspend fun getLatest(): String? {
        val versions = getVersions()
        val latest = versions.maxByOrNull { parseVersion(it) }
        logger.info("Latest Fabric Loader version: {}", latest)
        return latest
    }

    override suspend fun getDownloadUrl(version: String): URI? {
        val urlStr = "${repository}net/fabricmc/fabric-loader/$version/fabric-loader-$version.jar"
        return try {
            urlStr.toURI()
        } catch (e: Exception) {
            logger.error("Error constructing download URI for version {}: {}", version, e.message, e)
            null
        }
    }

    override suspend fun update(version: String): Boolean {
        logger.info("Updating Fabric Loader {}...", version)
        return try {
            if(!uninstall(version)) {
                logger.warn("Uninstalling Fabric Loader {} failed.", version)
            }
            download(version)
        } catch (e: Exception) {
            logger.error("Error updating Fabric Loader {}: {}", version, e.message, e)
            false
        }
    }

    private fun parseVersion(version: String): String {
        return version.split(".")
            .mapNotNull { it.toIntOrNull() }
            .joinToString(".")
    }

    @AutoService(ModLoader.Provider::class)
    internal class Provider: ModLoader.Provider {
        override fun create(): ModLoader = Fabric()
    }
}


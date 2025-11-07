package io.github.footermandev.tritium.auth

import com.microsoft.aad.msal4j.IAccount
import com.microsoft.aad.msal4j.InteractiveRequestParameters
import com.microsoft.aad.msal4j.SilentParameters
import io.github.footermandev.tritium.debugLogging
import io.github.footermandev.tritium.logger
import io.github.footermandev.tritium.toURI
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.network.sockets.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val authLogger = logger("Auth")

/**
 * Handles Microsoft and Xbox Live authentication for Minecraft.
 * Implements OAuth2 flow for Microsoft accounts and manages token lifecycle.
 */
class MicrosoftAuth {
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private var msalAcc: IAccount? = null

    companion object {
        private const val XBL_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate"
        private const val XSTS_AUTH_URL = "https://xsts.auth.xboxlive.com/xsts/authorize"
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1000L
    }

    /**
     * Checks if a user is currently signed in.
     * @return true if there is an active account session
     */
    suspend fun isSignedIn(): Boolean = MSAL.app.accounts.await().isNotEmpty()

    private suspend fun <T> CompletableFuture<T>.await(): T =
        suspendCancellableCoroutine { cont ->
            this.whenComplete { res, err ->
                if (err != null) cont.resumeWithException(err) else cont.resume(res)
            }
        }

    /**
     * Initiates a new sign-in flow with a retry mechanism.
     * @param onSignedIn Callback function called with the Minecraft profile after successful sign-in
     * @throws AuthenticationException if sign-in fails after all retry attempts
     */
    suspend fun newSignIn(onSignedIn: (MCProfile?) -> Unit)  {
        var lastException: Exception? = null
        
        for (attempt in 1..MAX_RETRY_ATTEMPTS) {
            try {
                authLogger.info("Starting sign-in flow (attempt $attempt)...")
                val scopes = setOf("XboxLive.signin", "offline_access")
                val parameters = InteractiveRequestParameters.builder("http://localhost".toURI())
                    .scopes(scopes)
                    .build()

                val result = MSAL.app.acquireToken(parameters).await()
                msalAcc = result.account()
                authLogger.info("MS token acquired; expires at ${result.expiresOnDate()}")

                val (xblToken, hash) = authWithLive(result.accessToken())
                val xstsToken = authWithXSTS(xblToken)
                val mcToken = getMCToken(xstsToken, hash)

                authLogger.info("Sign-in successful. MC token obtained.")

                ProfileMngr.Cache.init(mcToken)
                val profile = ProfileMngr.Cache.get()
                onSignedIn(profile)
                return
            } catch (e: Exception) {
                lastException = e
                authLogger.error("Sign-in attempt $attempt failed: ${e.message}", e)
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    delay(RETRY_DELAY_MS)
                }
            }
        }

        signOut()
        throw AuthenticationException("Sign-in failed after $MAX_RETRY_ATTEMPTS attempts", lastException)
    }

    /**
     * Ensures a valid access token is available.
     * @return the valid access token
     * @throws TokenException if token refresh fails
     */
    suspend fun ensureValidAccessToken(): String {
        val account = msalAcc ?: throw IllegalStateException("No signed-in account; sign in required.")
        return try {
            authLogger.info("Refreshing token silently...")
            val scopes = setOf("XboxLive.signin", "offline_access")
            val silentParams = SilentParameters.builder(scopes, account).build()
            val result = MSAL.app.acquireTokenSilently(silentParams).await()
            authLogger.info("Token refreshed silently; new expiry: ${result.expiresOnDate()}")
            result.accessToken()
        } catch (e: Exception) {
            authLogger.error("Silent token refresh failed: ${e.message}", e)
            signOut()
            throw TokenException("Silent token refresh failed", e)
        }
    }

    /**
     * Obtains a valid Minecraft token.
     * @return the valid Minecraft token
     * @throws TokenException if token refresh fails
     */
    suspend fun getValidMCToken(): String {
        val msToken = ensureValidAccessToken()
        val (xblToken, hash) = authWithLive(msToken)
        val xstsToken = authWithXSTS(xblToken)
        return getMCToken(xstsToken, hash)
    }

    /**
     * Gets a Minecraft token from a given Microsoft token.
     * @param msToken the Microsoft token
     * @return the Minecraft token
     * @throws TokenException if token refresh fails
     */
    suspend fun getMCToken(msToken: String): String {
        val (xblToken, hash) = authWithLive(msToken)
        val xstsToken = authWithXSTS(xblToken)
        return getMCToken(xstsToken, hash)
    }

    /**
     * Authenticates with Xbox Live using a Microsoft token.
     * @param token the Microsoft token
     * @return the Xbox Live token and user hash
     * @throws AuthenticationException if authentication fails
     */
    private suspend fun authWithLive(token: String): Pair<String, String> {
        val responseBody = XblAuthRequest(
            Properties = Properties(
                AuthMethod = "RPS",
                SiteName = "user.auth.xboxlive.com",
                RpsTicket = "d=$token"
            ),
            RelyingParty = "http://auth.xboxlive.com",
            TokenType = "JWT"
        )

        return try {
            val response: HttpResponse = httpClient.post(XBL_AUTH_URL) {
                contentType(ContentType.Application.Json)
                setBody(responseBody)
            }

            if(response.status != HttpStatusCode.OK) {
                val errBody = response.bodyAsText()
                authLogger.error("XBL authentication failed with HTTP status ${response.status.value}: $errBody")
                throw AuthenticationException("XBL authentication failed with HTTP status ${response.status.value}")
            }

            debug("XBL auth response received: ${response.bodyAsText()}")
            val xblResponse: XblTokenResponse = try {
                json.decodeFromString(XblTokenResponse.serializer(), response.bodyAsText())
            } catch (e: Exception) {
                authLogger.error("Failed to parse XBL auth response: ${e.message}", e)
                throw AuthenticationException("XBL auth response parsing failed", e)
            }

            val xblToken = xblResponse.token
            val hash = xblResponse.displayClaims.xui.first().userHash

            Pair(xblToken, hash)
        } catch (e: Exception) {
            authLogger.error("Error in authWithLive: ${e.message}", e)
            throw AuthenticationException("XBL authentication failed", e)
        }
    }

    /**
     * Authenticates with XSTS using an Xbox Live token.
     * @param token the Xbox Live token
     * @return the XSTS token
     * @throws AuthenticationException if authentication fails
     */
    private suspend fun authWithXSTS(token: String): String {
        val responseBody = XstsAuthRequest(
            Properties = XstsProperties(
                SandboxId = "RETAIL",
                UserTokens = listOf(token)
            ),
            RelyingParty = "rp://api.minecraftservices.com/",
            TokenType = "JWT"
        )

        return try {
            val response: HttpResponse = httpClient.post(XSTS_AUTH_URL) {
                contentType(ContentType.Application.Json)
                setBody(responseBody)
            }

            if(response.status != HttpStatusCode.OK) {
                val errBody = response.bodyAsText()
                authLogger.error("XSTS authentication failed with HTTP status ${response.status.value}: $errBody")
                throw AuthenticationException("XSTS authentication failed with HTTP status ${response.status.value}")
            }

            debug("XSTS auth response received: ${response.bodyAsText()}")
            val xstsResponse: XstsTokenResponse = try {
                json.decodeFromString(XstsTokenResponse.serializer(), response.bodyAsText())
            } catch (e: Exception) {
                authLogger.error("Failed to parse XSTS auth response: ${e.message}", e)
                throw AuthenticationException("XSTS auth response parsing failed", e)
            }

            xstsResponse.token
        } catch (e: Exception) {
            authLogger.error("Error in authWithXSTS: ${e.message}", e)
            throw AuthenticationException("XSTS authentication failed", e)
        }
    }

    /**
     * Gets a Minecraft token from an XSTS token and user hashes.
     * @param token the XSTS token
     * @param hash the user hash
     * @return the Minecraft token
     * @throws TokenException if token refresh fails
     */
    private suspend fun getMCToken(token: String, hash: String): String {
        val identity = "XBL3.0 x=$hash;$token"
        val body = mapOf("identityToken" to identity)

        return try {
            val response: HttpResponse =
                httpClient.post("https://api.minecraftservices.com/authentication/login_with_xbox") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }

            if (response.status != HttpStatusCode.OK) {
                val errBody = response.bodyAsText()
                authLogger.error("MC authentication failed with HTTP status ${response.status.value}: $errBody")
                throw TokenException("MC authentication failed with HTTP status ${response.status.value}")
            }

            val responseBody = response.bodyAsText()
            debug("MC auth response received: $responseBody", debugLogging)
            val authResponse: MCAuthResponse = try {
                json.decodeFromString(MCAuthResponse.serializer(), responseBody)
            } catch (e: Exception) {
                authLogger.error("Failed to parse MC auth response: ${e.message}", e)
                throw TokenException("MC auth response parsing failed", e)
            }

            authResponse.accessToken
        } catch (e: ConnectTimeoutException) {
            throw TokenException("Could not get MC Token due to time-out from Microsoft", e)
        } catch (e: Exception) {
            throw TokenException("MC token refresh failed", e)
        }
    }

    /**
     * Signs out the current user.
     */
    fun signOut() {
        runBlocking {
            MSAL.app.accounts.await().forEach { MSAL.app.removeAccount(it) }
        }
        msalAcc = null
        ProfileMngr.Cache.clear()
        authLogger.info("User signed out.")
    }

    /**
     * Retrieves a list of Minecraft versions.
     * @return the list of Minecraft versions
     */
    suspend fun getMinecraftVersions(): List<MCVersion?>? {
        return try {
            val response = httpClient.get("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
            val body = response.bodyAsText()
            authLogger.info("Successfully fetched MC version manifest.")

            val manifest: VersionManifest = json.decodeFromString(body)

            val releases = manifest.versions.filter { it.type == "release" }
            authLogger.info("Filtered ${releases.size} release versions from the MC version manifest.")
            releases
        } catch (e: Exception) {
            authLogger.error("Error fetching Minecraft versions", e)
            null
        }
    }

    /**
     * Downloads a Minecraft version.
     * @param version the Minecraft version to download
     * @param destination the destination directory
     * @return true if the download was successful, false otherwise
     */
    suspend fun downloadMinecraftVersion(version: MCVersion, destination: String): Boolean? {
        return try {
            authLogger.info("Downloading Minecraft '${version.id}' from ${version.url}")

            val content: ByteArray = httpClient.get(version.url).body()

            val file = File("$destination/${version.id}.jar")
            file.parentFile.mkdirs()
            file.writeBytes(content)
            authLogger.info("Downloaded Minecraft '${version.id}' to ${file.absolutePath}")
            true
        } catch (e: Exception) {
            authLogger.error("Failed to download Minecraft ${version.id}", e)
            false
        }

    }
}

private fun debug(msg: String, condition: Boolean = false) { if(condition) authLogger.debug(msg) }

/**
 * Custom exception for authentication-related errors
 */
class AuthenticationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Custom exception for token-related errors
 */
class TokenException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Serializable
data class XblTokenResponse(
    @SerialName("Token") val token: String,
    @SerialName("DisplayClaims") val displayClaims: DisplayClaims
)

@Serializable
data class XblAuthRequest(
    val Properties: Properties,
    val RelyingParty: String,
    val TokenType: String
)

@Serializable
data class XstsAuthRequest(
    val Properties: XstsProperties,
    val RelyingParty: String,
    val TokenType: String
)

@Serializable
data class Properties(
    val AuthMethod: String,
    val SiteName: String,
    val RpsTicket: String
)

@Serializable
data class XstsProperties(
    val SandboxId: String,
    val UserTokens: List<String>
)

@Serializable
data class XstsTokenResponse(
    @SerialName("Token") val token: String,
    @SerialName("DisplayClaims") val displayClaims: DisplayClaims
)

@Serializable
data class DisplayClaims(
    @SerialName("xui") val xui: List<Xui>
)

@Serializable
data class Xui(
    @SerialName("uhs") val userHash: String
)

@Serializable
data class MCAuthResponse(
    @SerialName("username") val uuid: String, // this is not the player UUID.
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Int
)

@Serializable
data class VersionManifest(
    val versions: List<MCVersion>
)

@Serializable
data class MCVersion(
    val id: String,
    val type: String,
    val url: String
)
package io.github.footermandev.tritium.accounts

import com.microsoft.aad.msal4j.DeviceCodeFlowParameters
import com.microsoft.aad.msal4j.IAccount
import com.microsoft.aad.msal4j.InteractiveRequestParameters
import com.microsoft.aad.msal4j.SilentParameters
import io.github.footermandev.tritium.*
import io.github.footermandev.tritium.io.VPath
import io.github.footermandev.tritium.io.linkOrCopyFromCache
import io.github.footermandev.tritium.platform.ClientIdentity
import io.github.footermandev.tritium.platform.Platform
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.network.sockets.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val authLogger = logger("Auth")

/**
 * Handles Microsoft and Xbox Live authentication for Minecraft.
 * Implements OAuth2 flow for Microsoft accounts and manages token lifecycle.
 *
 * TODO: Set up DeviceCode authentication alongside OAuth.
 */
object MicrosoftAuth {
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            this.connectTimeoutMillis = 10000L
            this.requestTimeoutMillis = 30000L
            this.socketTimeoutMillis = 30000L
        }

        install(HttpRequestRetry) {
            maxRetries = 3
            retryOnExceptionOrServerErrors(maxRetries = 3)
            delayMillis { retry ->
                (500L * (1L shl (retry - 1))).coerceAtMost(5000L)
            }
        }

        defaultRequest {
            header("User-Agent", ClientIdentity.userAgent)
            header("X-Client-Info", ClientIdentity.clientInfoHeader)
        }
    }

    private var account: IAccount? = null
    @Volatile
    internal var currentAccountHomeId: String? = null

    private const val XBL_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate"
    private const val XSTS_AUTH_URL = "https://xsts.auth.xboxlive.com/xsts/authorize"
    private val authScopes = setOf("XboxLive.signin", "offline_access")

    private val AppAuthScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
    suspend fun newSignIn(): ProfileMngr.MCProfile? {
        try {
            authLogger.info("Starting Microsoft sign-in")
            val parameters = InteractiveRequestParameters.builder("http://localhost".toURI())
                .scopes(authScopes)
                .systemBrowserOptions(MSAL.systemBrowserOptions())
                .build()

            val result = MSAL.app.acquireToken(parameters).await()
            account = result.account()
            currentAccountHomeId = account?.homeAccountId()
            authLogger.info("MS token acquired; expires at ${result.expiresOnDate()}")

            val (xblToken, hash) = authWithLive(result.accessToken())
            val xstsToken = authWithXSTS(xblToken)
            val mcToken = getMCToken(xstsToken, hash)

            authLogger.info("Microsoft sign-in successful. MC token obtained.")

            ProfileMngr.Cache.init(mcToken)
            return ProfileMngr.Cache.get()
        } catch (e: Exception) {
            authLogger.error("Microsoft interactive sign-in failed")
            throw AuthenticationException("Microsoft interactive sign-in failed", e)
        }
    }

    /**
     * Initiates device-code sign-in and returns the Minecraft profile on success.
     */
    suspend fun deviceCodeSignIn(deviceCodeConsumer: (String) -> Unit): ProfileMngr.MCProfile? {
        val deviceParams = DeviceCodeFlowParameters.builder(
            authScopes
        ) { deviceCode -> deviceCodeConsumer(deviceCode.message()) }
            .build()

        val result = try {
            MSAL.app.acquireToken(deviceParams).await()
        } catch (e: Exception) {
            authLogger.error("Microsoft Device-code sign-in failed")
            throw AuthenticationException("Microsoft Device-code sign-in failed", e)
        }

        account = result.account()
        currentAccountHomeId = account?.homeAccountId()
        val token = result.accessToken()
        authLogger.info("Microsoft Device-code token acquired; expires at ${result.expiresOnDate()}")

        val (xblToken, hash) = authWithLive(token)
        val xstsToken = authWithXSTS(xblToken)
        val mcToken = getMCToken(xstsToken, hash)

        ProfileMngr.Cache.init(mcToken)
        return ProfileMngr.Cache.get()
    }

    /**
     * Ensures a valid access token is available.
     * @return the valid access token
     * @throws TokenException if token refresh fails
     */
    suspend fun ensureValidAccessToken(): String {
        val homeId = currentAccountHomeId ?: throw IllegalStateException("No active account selected.")
        val account = MSAL.findAccount(homeAccountId = homeId)
            ?: throw IllegalStateException("Account $homeId not found in MSAL cache.")

        return try {
            val silentParams = SilentParameters.builder(authScopes, account).build()
            val result = MSAL.app.acquireTokenSilently(silentParams).await()
            authLogger.info("Token refreshed silently for ${account.username()}; expires ${result.expiresOnDate()}")
            result.accessToken()
        } catch (e: Exception) {
            authLogger.error("Silent token refresh failed for $homeId: ${e.message}", e)
            throw TokenException("Silent token refresh failed for $homeId", e)
        }
    }

    /**
     * Fetches a Minecraft profile for the provided Microsoft account id.
     */
    suspend fun getMcProfileForAccount(homeAccountId: String): ProfileMngr.MCProfile? = withContext(Dispatchers.IO) {
        val account = MSAL.findAccount(homeAccountId = homeAccountId)
            ?: return@withContext null

        try {
            val silentParams = SilentParameters.builder(authScopes, account).build()
            val result = MSAL.app.acquireTokenSilently(silentParams).await()
            val msToken = result.accessToken()
            val (xblToken, hash) = authWithLive(msToken)
            val xstsToken = authWithXSTS(xblToken)
            val mcToken = getMCToken(xstsToken, hash)
            ProfileMngr.Cache.initForAccount(homeAccountId, mcToken)
            return@withContext ProfileMngr.Cache.getForAccount(homeAccountId)
        } catch (e: Exception) {
            authLogger.warn("Could not get MC profile for account $homeAccountId", e)
            return@withContext null
        }
    }

    /**
     * Switches the active account to the provided home id.
     */
    suspend fun switchToAccount(homeAccountId: String): Boolean {
        val account = MSAL.findAccount(homeAccountId = homeAccountId) ?: return false
        try {
            val silentParams = SilentParameters.builder(authScopes, account).build()
            val result = MSAL.app.acquireTokenSilently(silentParams).await()
            currentAccountHomeId = homeAccountId
            authLogger.info("Switched to account ${account.username()}")
            return true
        } catch (e: Exception) {
            authLogger.warn("Failed to switch silently to $homeAccountId", e)
            currentAccountHomeId = homeAccountId
            return false
        }
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
     * Attempt to refresh the active account's Minecraft access token silently.
     * Returns null if no active account is available or refresh fails.
     */
    suspend fun getActiveMcTokenOrNull(): String? {
        val homeId = currentAccountHomeId ?: return null
        val account = MSAL.findAccount(homeAccountId = homeId) ?: return null
        return try {
            val silentParams = SilentParameters.builder(authScopes, account).build()
            val result = MSAL.app.acquireTokenSilently(silentParams).await()
            val msToken = result.accessToken()
            val mcToken = getMCToken(msToken)
            ProfileMngr.Cache.initForAccount(homeId, mcToken)
            mcToken
        } catch (e: Exception) {
            authLogger.warn("Failed to refresh MC token silently for {}", homeId, e)
            null
        }
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

            val body = response.bodyAsText()

            if(response.status != HttpStatusCode.OK) {
                authLogger.error("XBL authentication failed with HTTP status ${response.status.value}: $body")
                throw AuthenticationException("XBL authentication failed with HTTP status ${response.status.value}")
            }

            debug("XBL auth response received: $body")
            val xblResponse: XblTokenResponse = try {
                json.decodeFromString(XblTokenResponse.serializer(), body)
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

            val body = response.bodyAsText()

            if(response.status != HttpStatusCode.OK) {
                authLogger.error("XSTS authentication failed with HTTP status ${response.status.value}: $body")
                throw AuthenticationException("XSTS authentication failed with HTTP status ${response.status.value}")
            }

            debug("XSTS auth response received: $body")
            val xstsResponse: XstsTokenResponse = try {
                json.decodeFromString(XstsTokenResponse.serializer(), body)
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

            val body = response.bodyAsText()

            if (response.status != HttpStatusCode.OK) {
                authLogger.error("MC authentication failed with HTTP status ${response.status.value}: $body")
                throw TokenException("MC authentication failed with HTTP status ${response.status.value}")
            }

            debug("MC auth response received: $body", debugLogging)
            val authResponse: MCAuthResponse = try {
                json.decodeFromString(MCAuthResponse.serializer(), body)
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

    internal fun attemptAutoSignIn() {
        AppAuthScope.launch {
            var tryCount = 0
            while(isActive && tryCount < 5) {
                try {
                    val accounts = try {
                        MSAL.app.accounts.await().toList()
                    } catch (t: Throwable) {
                        authLogger.warn("Failed to read MSAL accounts from cache", t)
                        emptyList()
                    }

                    if (accounts.isEmpty()) {
                        authLogger.info("No Microsoft accounts available for auto sign-in")
                        break
                    }

                    var restored = false
                    for (acct in accounts) {
                        if (!isActive) break

                        val homeId = acct.homeAccountId()
                        val username = acct.username()
                        authLogger.info("Attempting silent token acquire for account username='${username}' homeId='${homeId ?: "null"}'")

                        try {
                            val silentParams = SilentParameters.builder(authScopes, acct).build()
                            val result = try { MSAL.app.acquireTokenSilently(silentParams).await() } catch (e: Exception) {
                                authLogger.info("Silent acquire failed for $username (homeId=$homeId): ${e.message}")
                                null
                            }

                            if (result != null) {
                                account = result.account()
                                val resolvedHomeId = result.account()?.homeAccountId() ?: homeId
                                if (!resolvedHomeId.isNullOrBlank()) {
                                    currentAccountHomeId = resolvedHomeId
                                }

                                val msToken = result.accessToken()
                                try {
                                    if (!resolvedHomeId.isNullOrBlank()) {
                                        val mcToken = getMCToken(msToken)
                                        ProfileMngr.Cache.initForAccount(resolvedHomeId, mcToken)
                                    } else {
                                        val mcToken = getMCToken(msToken)
                                        ProfileMngr.Cache.init(mcToken)
                                    }
                                    authLogger.info("Auto sign-in succeeded for account username='${username}' homeId='${resolvedHomeId}'")
                                } catch (pErr: Throwable) {
                                    authLogger.warn("Auto sign-in succeeded but profile init failed for homeId='${resolvedHomeId}': ${pErr.message}", pErr)
                                }

                                restored = true
                                break
                            }
                        } catch (t: Throwable) {
                            authLogger.warn("Error attempting silent restore for account $username", t)
                        }
                    }

                    if (!restored) {
                        authLogger.info("Auto sign-in: no account could be restored silently. Interactive sign-in will be required.")
                    }

                    break
                } catch (e: Exception) {
                    tryCount++
                    mainLogger.error("Auto sign-in attempt #$tryCount failed", e)
                    val delayMs = (1000L * (1 shl (tryCount - 1))).coerceAtMost(60000L)
                    delay(delayMs)
                }
            }
        }
    }

    /**
     * Lists accounts from the MSAL cache.
     */
    suspend fun listAccounts(): List<MSAccountSummary> = withContext(Dispatchers.IO) {
        try {
            val accs = MSAL.app.accounts.await()
            accs.map { a ->
                MSAccountSummary(
                    a.homeAccountId(),
                    a.username()
                )
            }
        } catch (e: Exception) {
            authLogger.error("Failed to list accounts", e)
            emptyList()
        }
    }

    /**
     * Signs out a specific account by home id.
     */
    suspend fun signOutAccount(homeAccountId: String) {
        try {
            val account = MSAL.findAccount(homeAccountId = homeAccountId) ?: return
            MSAL.app.removeAccount(account).await()
        } catch (e: Exception) {
            authLogger.warn("signOutAccount error for $homeAccountId", e)
        } finally {
            if (currentAccountHomeId == homeAccountId) currentAccountHomeId = null
        }
    }

    /**
     * Signs out every cached account.
     */
    suspend fun signOutAll() {
        try {
            val accounts = MSAL.app.accounts.await()
            for (a in accounts) {
                MSAL.app.removeAccount(a).await()
            }
        } catch (e: Exception) {
            authLogger.warn("signOutAll error", e)
        } finally {
            currentAccountHomeId = null
        }
    }

    /**
     * Signs out the current user.
     */
    suspend fun signOutSuspending() {
        try {
            val accounts = MSAL.app.accounts.await()
            for (a in accounts) MSAL.app.removeAccount(a).await()
        } catch (e: Exception) {
            authLogger.error("Error removing Microsoft accounts", e)
        } finally {
            account = null
            authLogger.info("User signed out.")
        }
    }

    fun signOut() = runBlocking { signOutSuspending() }

    /**
     * Retrieves a list of Minecraft versions.
     */
    suspend fun getMinecraftVersions(releaseTypes: List<MCVersionType> = listOf(MCVersionType.Release)): List<MCVersion> {
        return try {
            val response = httpClient.get("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
            val body = response.bodyAsText()

            val manifest: VersionManifest = json.decodeFromString(body)

            val typesSet = releaseTypes.toSet()
            val releases = manifest.versions.filter { it.type in typesSet }
            authLogger.info("Successfully fetched MC version manifest.")
            authLogger.info("Filtered ${releases.size} release versions from the MC version manifest.")
            releases
        } catch (e: Exception) {
            authLogger.error("Error fetching Minecraft versions", e)
            emptyList()
        }
    }

    /**
     * Download and prepare a vanilla Minecraft instance into the target directory.
     */
    suspend fun setupMinecraftInstance(versionId: String, targetDir: VPath): Boolean = withContext(Dispatchers.IO) {
        try {
            authLogger.info("MC setup start version={} dir={}", versionId, targetDir)
            val manifest = fetchVersionManifest()
            val entry = manifest.versions.firstOrNull { it.id == versionId }
                ?: run {
                    authLogger.error("Minecraft version not found: {}", versionId)
                    return@withContext false
                }

            val versionJsonText = httpClient.get(entry.url).bodyAsText()
            val versionInfo = json.decodeFromString(VersionInfo.serializer(), versionJsonText)
            authLogger.info("MC setup fetched version json for {}", versionId)

            val versionsDir = targetDir.resolve(".tr").resolve("versions").resolve(versionId)
            versionsDir.mkdirs()
            versionsDir.resolve("$versionId.json").writeBytes(versionJsonText.toByteArray())

            val client = versionInfo.downloads.client
            val clientJar = versionsDir.resolve("$versionId.jar")
            if(!clientJar.exists()) {
                authLogger.info("Downloading client jar {}", client.url)
                val bytes = httpClient.get(client.url).bodyAsBytes()
                clientJar.writeBytes(bytes)
            }

            val libsDir = targetDir.resolve(".tr").resolve("libraries")
            val nativesDir = targetDir.resolve(".tr").resolve("natives").resolve(versionId)
            nativesDir.mkdirs()

            supervisorScope {
                val libsJob = async {
                    authLogger.info("Downloading libraries + natives ({} libs)", versionInfo.libraries.size)
                    downloadLibraries(versionInfo.libraries, libsDir, nativesDir)
                    authLogger.info("Libraries complete")
                }

                val assetsJob = async {
                    authLogger.info("Downloading assets {}", versionInfo.assetIndex.id)
                    downloadAssets(versionInfo.assetIndex, targetDir)
                    authLogger.info("Assets complete")
                }

                val logJob = async {
                    versionInfo.logging?.client?.let { logCfg ->
                        val logDir = targetDir.resolve(".tr").resolve("log_configs")
                        logDir.mkdirs()
                        val dest = logDir.resolve(logCfg.file.id)
                        if(!dest.exists()) {
                            val bytes = httpClient.get(logCfg.file.url).bodyAsBytes()
                            dest.writeBytes(bytes)
                        }
                    }
                }

                libsJob.await()
                assetsJob.await()
                logJob.await()
            }

            authLogger.info("MC setup finished for {}", versionId)
            true
        } catch (e: Exception) {
            authLogger.error("Failed to setup Minecraft instance {}", versionId, e)
            false
        }
    }

    /**
     * Write a merged version JSON for a loader instance.
     *
     * This merges the base Minecraft version JSON with a loader-provided patch
     * located at `.tr/loader/<loaderId>/version_patch.json` in the instance.
     */
    suspend fun writeMergedVersionJson(
        mcVersion: String,
        loaderId: String,
        loaderVersion: String,
        targetDir: VPath
    ): VPath? = withContext(Dispatchers.IO) {
        val baseDir = targetDir.resolve(".tr").resolve("versions").resolve(mcVersion)
        val baseJsonFile = baseDir.resolve("$mcVersion.json")
        val baseJar = baseDir.resolve("$mcVersion.jar")
        if(!baseJsonFile.exists()) return@withContext null

        val baseText = baseJsonFile.readTextOrNull() ?: return@withContext null
        val baseObj = json.parseToJsonElement(baseText).jsonObject

        val patchFile = targetDir.resolve(".tr").resolve("loader").resolve(loaderId).resolve("version_patch.json")
        val patchText = patchFile.readTextOrNull() ?: return@withContext null
        val patchObj = json.parseToJsonElement(patchText).jsonObject

        val merged = mergeVersionJson(baseObj, patchObj)
        val mergedId = "$mcVersion-$loaderId-$loaderVersion"
        val mergedObj = merged.toMutableMap().apply { put("id", JsonPrimitive(mergedId)) }
        val mergedJson = json.encodeToString(JsonObject(mergedObj))

        val outDir = targetDir.resolve(".tr").resolve("versions").resolve(mergedId)
        outDir.mkdirs()
        val outJson = outDir.resolve("$mergedId.json")
        outJson.writeBytes(mergedJson.toByteArray())

        if(baseJar.exists()) {
            val outJar = outDir.resolve("$mergedId.jar")
            if(!outJar.exists()) {
                outJar.writeBytes(baseJar.bytesOrNothing())
            }
        }

        outJson
    }

    private fun mergeVersionJson(base: JsonObject, patch: JsonObject): JsonObject {
        val merged = base.toMutableMap()

        patch["mainClass"]?.let { merged["mainClass"] = it }
        mergeArguments(base, patch)?.let { merged["arguments"] = it }
        patch["minecraftArguments"]?.let { merged["minecraftArguments"] = it }

        val baseLibs = base["libraries"]?.jsonArray ?: JsonArray(emptyList())
        val patchLibs = patch["libraries"]?.jsonArray ?: JsonArray(emptyList())
        if(patchLibs.isNotEmpty()) {
            merged["libraries"] = mergeLibrariesPreferPatch(baseLibs, patchLibs)
        }

        return JsonObject(merged)
    }

    private fun mergeArguments(base: JsonObject, patch: JsonObject): JsonObject? {
        val patchArgs = patch["arguments"] as? JsonObject ?: return base["arguments"] as? JsonObject
        val baseArgs = base["arguments"] as? JsonObject

        val baseGame = baseArgs?.get("game")?.jsonArray
            ?: base["minecraftArguments"]?.jsonPrimitive?.contentOrNull?.split(" ")?.map { JsonPrimitive(it) }?.let { JsonArray(it) }
            ?: JsonArray(emptyList())
        val baseJvm = baseArgs?.get("jvm")?.jsonArray ?: JsonArray(emptyList())

        val patchGame = patchArgs["game"]?.jsonArray ?: JsonArray(emptyList())
        val patchJvm = patchArgs["jvm"]?.jsonArray ?: JsonArray(emptyList())

        val mergedGame = JsonArray(baseGame + patchGame)
        val mergedJvm = JsonArray(baseJvm + patchJvm)

        return buildJsonObject {
            put("game", mergedGame)
            put("jvm", mergedJvm)
        }
    }

    /**
     * Merge libraries giving precedence to loader-supplied entries. This removes older duplicates
     * that can clash on the classpath (e.g., ASM or loader modules from vanilla vs. NeoForge).
     */
    private fun mergeLibrariesPreferPatch(baseLibs: JsonArray, patchLibs: JsonArray): JsonArray {
        if (patchLibs.isEmpty()) return baseLibs
        val merged = LinkedHashMap<String, JsonElement>()

        fun addAll(libs: JsonArray, prefer: Boolean) {
            for (el in libs) {
                val key = libraryIdentityKey(el) ?: libraryKey(el)
                if (key.isBlank()) continue
                if (!merged.containsKey(key) || prefer) {
                    merged[key] = el
                }
            }
        }

        addAll(baseLibs, false)
        addAll(patchLibs, true)
        return JsonArray(merged.values.toList())
    }

    private fun libraryKey(el: JsonElement): String {
        val obj = el as? JsonObject ?: return el.toString()
        val name = obj["name"]?.jsonPrimitive?.contentOrNull
        val path = obj["downloads"]?.jsonObject
            ?.get("artifact")?.jsonObject
            ?.get("path")?.jsonPrimitive?.contentOrNull
        return path ?: name ?: el.toString()
    }

    private fun libraryIdentityKey(el: JsonElement): String? {
        val obj = el as? JsonObject ?: return null
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val parts = name.split(":")
        if (parts.size < 3) return name
        val group = parts[0]
        val artifact = parts[1]
        val classifier = parts.getOrNull(3)
        return if (classifier != null) "$group:$artifact:$classifier" else "$group:$artifact"
    }

    private suspend fun fetchVersionManifest(): VersionManifest {
        val response = httpClient.get("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
        val body = response.bodyAsText()
        return json.decodeFromString(VersionManifest.serializer(), body)
    }

    private fun isLibraryAllowed(rules: List<LibraryRule>?): Boolean {
        if(rules.isNullOrEmpty()) return true
        var allowed = true
        for(rule in rules) {
            if(rule.os != null && !osMatches(rule.os)) continue
            allowed = rule.action == "allow"
        }
        return allowed
    }

    private fun osMatches(os: RuleOS): Boolean {
        val nameOk = when(os.name) {
            null -> true
            "linux" -> !Platform.isWindows && !Platform.isMacOS
            "windows" -> Platform.isWindows
            "osx" -> Platform.isMacOS
            else -> false
        }

        if(!nameOk) return false
        if(os.arch == null) return true

        val arch = System.getProperty("os.arch") ?: ""
        return if(os.arch == "x86") arch.contains("86") && !arch.contains("64") else arch.contains("64")
    }

    private suspend fun downloadLibraries(libraries: List<Library>, libsDir: VPath, nativesDir: VPath) = withContext(Dispatchers.IO) {
        val total = libraries.size
        authLogger.info("Libraries: {} to ensure", total)
        val sharedLibsDir = fromTR(TConstants.Dirs.CACHE).resolve("libraries")
        val semaphore = Semaphore(8)
        val startedAt = System.currentTimeMillis()
        var completed = 0

        coroutineScope {
            libraries.withIndex().map { (idx, lib) ->
                launch {
                    if(!isLibraryAllowed(lib.rules)) return@launch
                    semaphore.withPermit {
                        try {
                            val artifact = lib.downloads?.artifact
                            if(artifact != null) {
                                val dest = libsDir.resolve(artifact.path)
                                val cache = sharedLibsDir.resolve(artifact.path)
                                if (linkOrCopyFromCache(cache, dest)) {
                                    return@launch
                                }
                                if(!dest.exists()) {
                                    authLogger.debug("lib[{}/{}] artifact {}", idx+1, total, artifact.path)
                                    dest.parent().mkdirs()
                                    val bytes = withTimeout(20_000) { httpClient.get(artifact.url).bodyAsBytes() }
                                    cache.parent().mkdirs()
                                    cache.writeBytes(bytes)
                                    if (!linkOrCopyFromCache(cache, dest)) {
                                        dest.writeBytes(bytes)
                                    }
                                }
                            } else if(lib.name != null) {
                                val url = (lib.url ?: "https://libraries.minecraft.net/").trimEnd('/')
                                val path = mavenPath(lib.name)
                                val dest = libsDir.resolve(path)
                                val cache = sharedLibsDir.resolve(path)
                                if (linkOrCopyFromCache(cache, dest)) {
                                    return@launch
                                }
                                if(!dest.exists()) {
                                    authLogger.debug("lib[{}/{}] maven {}", idx+1, total, path)
                                    dest.parent().mkdirs()
                                    val bytes = withTimeout(20_000) { httpClient.get("$url/$path").bodyAsBytes() }
                                    cache.parent().mkdirs()
                                    cache.writeBytes(bytes)
                                    if (!linkOrCopyFromCache(cache, dest)) {
                                        dest.writeBytes(bytes)
                                    }
                                }
                            }

                            val nativesKey = lib.natives?.let { pickNativeKey(it) }
                            val classifier = nativesKey?.let { key -> lib.downloads?.classifiers?.get(key) }
                            if(classifier != null) {
                                val dest = libsDir.resolve(classifier.path)
                                val cache = sharedLibsDir.resolve(classifier.path)
                                if (linkOrCopyFromCache(cache, dest)) {
                                    extractNatives(dest, nativesDir, lib.extract)
                                    return@launch
                                }
                                if(!dest.exists()) {
                                    dest.parent().mkdirs()
                                    val bytes = withTimeout(20_000) { httpClient.get(classifier.url).bodyAsBytes() }
                                    cache.parent().mkdirs()
                                    cache.writeBytes(bytes)
                                    if (!linkOrCopyFromCache(cache, dest)) {
                                        dest.writeBytes(bytes)
                                    }
                                }
                                extractNatives(dest, nativesDir, lib.extract)
                            }
                        } catch (t: Exception) {
                            authLogger.warn("Library {} failed: {}", lib.name ?: lib.downloads?.artifact?.path ?: "unknown", t.message)
                        } finally {
                            val done = synchronized(this@MicrosoftAuth) { ++completed }
                            if(done % 50 == 0 || done == total) {
                                val elapsed = System.currentTimeMillis() - startedAt
                                authLogger.info("Libraries progress {}/{} ({})", done, total, formatDurationMs(elapsed))
                            }
                        }
                    }
                }
            }.joinAll()
        }

        authLogger.info("Libraries download complete in {}", formatDurationMs(System.currentTimeMillis() - startedAt))
    }

    private fun pickNativeKey(natives: Map<String, String>): String? {
        val osKey = when {
            Platform.isWindows -> "windows"
            Platform.isMacOS -> "osx"
            else -> "linux"
        }
        val raw = natives[osKey] ?: return null
        if(!raw.contains("\${arch}")) return raw
        val arch = System.getProperty("os.arch") ?: ""
        val token = if(arch.contains("64")) "64" else "32"
        return raw.replace("\${arch}", token)
    }

    private fun extractNatives(jar: VPath, nativesDir: VPath, extract: LibraryExtract?) {
        ZipInputStream(jar.toJFile().inputStream()).use { zis ->
            var entry = zis.nextEntry
            while(entry != null) {
                val name = entry.name
                if(entry.isDirectory) { entry = zis.nextEntry; continue }
                if(extract?.exclude?.any { name.startsWith(it) } == true) {
                    entry = zis.nextEntry; continue
                }
                val out = nativesDir.resolve(name)
                out.parent().mkdirs()
                out.writeBytes(zis.readBytes())
                entry = zis.nextEntry
            }
        }
    }

    private suspend fun downloadAssets(assetIndex: AssetIndex, targetDir: VPath) = withContext(Dispatchers.IO) {
        val sharedAssetsDir = fromTR(TConstants.Dirs.ASSETS)
        sharedAssetsDir.mkdirs()

        val instanceAssetsDir = targetDir.resolve(".tr").resolve("assets")
        val assetsDir = tryCreateSharedAssetsLink(instanceAssetsDir, sharedAssetsDir)

        val indexesDir = assetsDir.resolve("indexes")
        val objectsDir = assetsDir.resolve("objects")
        indexesDir.mkdirs()
        objectsDir.mkdirs()

        val indexFile = indexesDir.resolve("${assetIndex.id}.json")
        if(!indexFile.exists()) {
            authLogger.info("Assets: downloading index {}", assetIndex.id)
            val bytes = httpClient.get(assetIndex.url).bodyAsBytes()
            indexFile.writeBytes(bytes)
        }

        val indexText = indexFile.readTextOrNull() ?: return@withContext
        val index = json.decodeFromString(AssetIndexFile.serializer(), indexText)
        val total = index.objects.size
        val concurrency = chooseAssetConcurrency(total)
        authLogger.info("Assets: {} objects to ensure (id={}), concurrency={}", total, assetIndex.id, concurrency)

        val startedAt = System.currentTimeMillis()
        val semaphore = Semaphore(concurrency) // limit concurrent asset downloads
        var completed = 0

        coroutineScope {
            index.objects.entries.map { entry ->
                launch {
                    val obj = entry.value
                    val hash = obj.hash
                    val prefix = hash.substring(0, 2)
                    val objPath = objectsDir.resolve(prefix).resolve(hash)
                    if(objPath.exists()) {
                        return@launch
                    }
                    semaphore.withPermit {
                        objPath.parent().mkdirs()
                        val url = "https://resources.download.minecraft.net/$prefix/$hash"
                        try {
                            val bytes = withTimeout(20_000) { httpClient.get(url).bodyAsBytes() }
                            objPath.writeBytes(bytes)
                        } catch (t: Exception) {
                            authLogger.warn("Asset {} failed ({}/{}): {}", hash, completed + 1, total, t.message)
                            return@withPermit
                        } finally {
                            val done = synchronized(this@MicrosoftAuth) { ++completed }
                            if(done % 200 == 0 || done == total) {
                                val elapsed = System.currentTimeMillis() - startedAt
                                authLogger.info("Assets progress {}/{} ({})", done, total, formatDurationMs(elapsed))
                            }
                        }
                    }
                }
            }.joinAll()
        }

        authLogger.info("Assets download complete in {}", formatDurationMs(System.currentTimeMillis() - startedAt))
    }

    private fun chooseAssetConcurrency(total: Int): Int {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        val base = if (total >= 3000) 12 else 8
        val max = 16
        return (cores * 2).coerceIn(base, max)
    }

    private fun tryCreateSharedAssetsLink(instanceDir: VPath, sharedDir: VPath): VPath {
        return try {
            if(!instanceDir.exists()) {
                instanceDir.parent().mkdirs()
                Files.createSymbolicLink(instanceDir.toJPath(), sharedDir.toJPath())
                authLogger.info("Assets: linked instance cache to shared {}", sharedDir.toAbsolute())
            } else {
                val isSymlink = Files.isSymbolicLink(instanceDir.toJPath())
                if(isSymlink) {
                    authLogger.info("Assets: instance cache already linked to shared {}", sharedDir.toAbsolute())
                } else {
                    authLogger.info("Assets: instance cache exists; using per-instance assets at {}", instanceDir.toAbsolute())
                    return instanceDir
                }
            }
            sharedDir
        } catch (t: Throwable) {
            authLogger.warn("Assets: failed to link shared cache; using instance assets", t)
            try { instanceDir.mkdirs() } catch (_: Throwable) {}
            instanceDir
        }
    }

    private fun mavenPath(name: String): String {
        val parts = name.split(":")
        if(parts.size < 3) return name
        val group = parts[0].replace('.', '/')
        val artifact = parts[1]
        val version = parts[2]
        val classifier = parts.getOrNull(3)
        val file = if(classifier != null) "$artifact-$version-$classifier.jar" else "$artifact-$version.jar"
        return "$group/$artifact/$version/$file"
    }
}

private fun debug(msg: String, condition: Boolean = false) { if(condition) authLogger.debug(msg) }

/**
 * Summary of a Microsoft account returned by MSAL.
 */
data class MSAccountSummary(
    val homeAccountId: String,
    val username: String?
)

/**
 * Custom exception for authentication-related errors
 */
class AuthenticationException(message: String?, cause: Throwable? = null) : Exception(message, cause)

/**
 * Custom exception for token-related errors
 */
class TokenException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Response from XBL token endpoint. */
@Serializable
data class XblTokenResponse(
    @SerialName("Token") val token: String,
    @SerialName("DisplayClaims") val displayClaims: DisplayClaims
)

/** Request payload for XBL authentication. */
@Serializable
data class XblAuthRequest(
    val Properties: Properties,
    val RelyingParty: String,
    val TokenType: String
)

/** Request payload for XSTS authentication. */
@Serializable
data class XstsAuthRequest(
    val Properties: XstsProperties,
    val RelyingParty: String,
    val TokenType: String
)

/** XBL auth properties. */
@Serializable
data class Properties(
    val AuthMethod: String,
    val SiteName: String,
    val RpsTicket: String
)

/** XSTS auth properties. */
@Serializable
data class XstsProperties(
    val SandboxId: String,
    val UserTokens: List<String>
)

/** Response from XSTS token endpoint. */
@Serializable
data class XstsTokenResponse(
    @SerialName("Token") val token: String,
    @SerialName("DisplayClaims") val displayClaims: DisplayClaims
)

/** Display claims container for XBL/XSTS responses. */
@Serializable
data class DisplayClaims(
    @SerialName("xui") val xui: List<Xui>
)

/** User hash entry in XBL/XSTS responses. */
@Serializable
data class Xui(
    @SerialName("uhs") val userHash: String
)

/** Response from Minecraft auth endpoint. */
@Serializable
data class MCAuthResponse(
    @SerialName("username") val uuid: String, // this is not the player UUID.
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Int
)

/** Version manifest root. */
@Serializable
data class VersionManifest(
    val versions: List<MCVersion>
)

/** Minecraft version entry from the manifest. */
@Serializable
data class MCVersion(
    val id: String,
    val type: MCVersionType,
    val url: String
)

/** Detailed version JSON for a specific Minecraft version. */
@Serializable
data class VersionInfo(
    val id: String,
    val downloads: VersionDownloads,
    val libraries: List<Library> = emptyList(),
    val assetIndex: AssetIndex,
    val mainClass: String? = null,
    val arguments: JsonObject? = null,
    val minecraftArguments: String? = null,
    val javaVersion: JavaVersion? = null,
    val logging: Logging? = null
)

/** Version downloads root. */
@Serializable
data class VersionDownloads(
    val client: DownloadEntry
)

/** Download entry for a file in the version JSON. */
@Serializable
data class DownloadEntry(
    val url: String,
    val sha1: String? = null,
    val size: Long? = null
)

/** Library definition in a version JSON. */
@Serializable
data class Library(
    val name: String? = null,
    val url: String? = null,
    val rules: List<LibraryRule>? = null,
    val natives: Map<String, String>? = null,
    val downloads: LibraryDownloads? = null,
    val extract: LibraryExtract? = null
)

/** Library downloads container. */
@Serializable
data class LibraryDownloads(
    val artifact: LibraryArtifact? = null,
    val classifiers: Map<String, LibraryArtifact>? = null
)

/** Library artifact entry. */
@Serializable
data class LibraryArtifact(
    val path: String,
    val url: String,
    val sha1: String? = null,
    val size: Long? = null
)

/** Library extract rules for natives. */
@Serializable
data class LibraryExtract(
    val exclude: List<String> = emptyList()
)

/** Rule that filters a library by OS. */
@Serializable
data class LibraryRule(
    val action: String,
    val os: RuleOS? = null
)

/** OS rule descriptor. */
@Serializable
data class RuleOS(
    val name: String? = null,
    val arch: String? = null
)

/** Asset index descriptor. */
@Serializable
data class AssetIndex(
    val id: String,
    val url: String,
    val sha1: String? = null,
    val size: Long? = null
)

/** Asset index file contents. */
@Serializable
data class AssetIndexFile(
    val objects: Map<String, AssetObject>
)

/** Asset object entry. */
@Serializable
data class AssetObject(
    val hash: String,
    val size: Long
)

/** Argument entry with optional rules. */
@Serializable
data class Argument(
    val rules: List<LibraryRule>? = null,
    val value: JsonElement
)

/** Java version requirement in version JSON. */
@Serializable
data class JavaVersion(
    val majorVersion: Int
)

/** Logging descriptor in version JSON. */
@Serializable
data class Logging(
    val client: LoggingConfig? = null
)

/** Logging config for client. */
@Serializable
data class LoggingConfig(
    val file: LoggingFile
)

/** Logging file descriptor. */
@Serializable
data class LoggingFile(
    val id: String,
    val url: String
)

/** Minecraft version type. */
@Serializable
enum class MCVersionType {
    @SerialName("release") Release,
    @SerialName("snapshot") Snapshot,
    @SerialName("old_beta") OldBeta,
    @SerialName("old_alpha") OldAlpha
    ;
}

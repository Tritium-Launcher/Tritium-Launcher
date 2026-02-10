package io.github.footermandev.tritium.accounts

import com.microsoft.aad.msal4j.*
import io.github.footermandev.tritium.TConstants
import io.github.footermandev.tritium.fromTR
import io.github.footermandev.tritium.io.VPath
import io.github.footermandev.tritium.logger
import io.github.footermandev.tritium.platform.Platform
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

/**
 * Microsoft Authentication Library impl.
 */
internal object MSAL {
    private val dir = fromTR(TConstants.Dirs.MSAL)
    private const val CLIENT_ID = "6d6b484d-842d-47c9-abe1-e4f0c5f07c77"

    val app: PublicClientApplication

    private val logger = logger()

    init {
        try {
            dir.mkdirs()
        } catch (e: Exception) {
            logger.warn("Could not create MSAL directory at '$dir'", e)
        }

        val cacheFile = dir.resolve("msal_cache.dat")

        val tokenCache = FileTokenCache(cacheFile)

        val builder = PublicClientApplication.builder(CLIENT_ID)
            .authority("https://login.microsoftonline.com/consumers")
            .setTokenCacheAccessAspect(tokenCache)

        app = builder.build()

        try {
            logger.info("MSAL initialized, cache file: ${cacheFile.toAbsolute()}")
            val keyCheck = Keyring.getSecret() != null
            logger.info("Keyring available=${keyCheck}")
        } catch (t: Throwable) {
            logger.warn("MSAL init diagnostics failed", t)
        }
    }

    fun findAccount(homeAccountId: String? = null, username: String? = null): IAccount? {
        val accounts = app.accounts
        val accs = try { accounts.get() } catch (t: Throwable) {
            logger.warn("Could not get Microsoft accounts", t)
            emptySet<IAccount>()
        }
        logger.info("Microsoft Accounts found: ${accs.size}")
        return accs.firstOrNull { a ->
            (homeAccountId != null && a.homeAccountId() == homeAccountId)
                    || (username != null && a.username() == username)
        }
    }

    /* This is set due to having potential conflicts with Qt.
     *
     * When the system's version of Qt on Linux is newer than what Tritium uses, some URL commands will fail silently.
     * Platform.openBrowser tries more than one URL command to ensure the browser is opened.
     */
    internal val openBrowserAction = OpenBrowserAction { url ->
        try {
            Platform.openBrowser(url.toString())
        } catch (t: Throwable) {
            throw t
        }
    }

    internal fun systemBrowserOptions() = SystemBrowserOptions.builder()
        .openBrowserAction(openBrowserAction)
        .build()

    private object Keyring {
        private const val SERVICE = "TritiumMSAL"
        private const val ACCOUNT = "TritiumLauncherTokenKey"

        private val logger = logger()

        fun getSecret(): ByteArray? = when(Platform.current) {
            Platform.Linux -> getLinuxSecret()
//            Platform.MacOSX -> getMacSecret() TODO: Do MacOS and Windows security
            else -> null
        }

        fun putSecret(secret: ByteArray): Boolean = when(Platform.current) {
            Platform.Linux -> putLinuxSecret(secret)
//            Platform.MacOSX -> putMacSecret() TODO: Do MacOS and Windows security
            else -> false
        }

        private fun getLinuxSecret(): ByteArray? {
            try {
                val p = ProcessBuilder("secret-tool", "lookup", "service", SERVICE, "account", ACCOUNT)
                    .redirectErrorStream(true)
                    .start()
                val out = p.inputStream.readAllBytes()
                val rc = p.waitFor()
                if(rc != 0) {
                    logger.warn("secret-tool lookup returned $rc, no secret present")
                    return null
                }
                val b64 = String(out).trim()
                if(b64.isBlank()) return null
                return Base64.decode(b64)
            } catch (t: Throwable) {
                logger.warn("Failed to get linux secret", t)
                return null
            }
        }

        private fun putLinuxSecret(secret: ByteArray): Boolean {
            try {
                val b64 = Base64.encode(secret)
                val p = ProcessBuilder("secret-tool", "store", "--label", "Tritium MSAL key", "service", SERVICE, "account", ACCOUNT)
                    .start()
                p.outputStream.use { it.write(b64.toByteArray()) }
                val rc = p.waitFor()
                if(rc != 0) {
                    logger.warn("secret-tool store returned $rc")
                    return false
                }
                return true
            } catch (t: Throwable) {
                logger.warn("Failed to put linux secret", t)
                return false
            }
        }

        fun generateSecret(): ByteArray {
            val r = SecureRandom()
            val key = ByteArray(32)
            r.nextBytes(key)
            return key
        }
    }

    private class FileTokenCache(private val cacheFile: VPath): ITokenCacheAccessAspect {
        private val lock = ReentrantLock()
        private val aesKey = 32
        private val gcmIv = 12
        private val tagBits = 128
        private val magic = "TRMSAL1".toByteArray(Charsets.US_ASCII)
        private val modePlain: Byte = 0
        private val modeEncrypted: Byte = 1

        private fun getExistingKey(): ByteArray? {
            val key = Keyring.getSecret() ?: return null
            if (key.size != aesKey) {
                logger.warn("Ignoring keyring entry with unexpected length (expected $aesKey bytes)")
                return null
            }
            return key
        }

        private fun getOrCreateKey(): ByteArray? {
            getExistingKey()?.let { return it }

            val key = Keyring.generateSecret()
            val stored = Keyring.putSecret(key)
            if(!stored) {
                logger.warn("Failed to store key in OS keyring, using plaintext")
                return null
            }
            return key
        }

        private fun encrypt(plain: ByteArray, key: ByteArray): ByteArray {
            val iv = ByteArray(gcmIv)
            SecureRandom().nextBytes(iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(tagBits, iv)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), spec)
            val cipherText = cipher.doFinal(plain)
            val out = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, out, 0, iv.size)
            System.arraycopy(cipherText, 0, out, iv.size, cipherText.size)
            return out
        }

        private fun decrypt(data: ByteArray, key: ByteArray): ByteArray {
            val iv = data.copyOfRange(0, gcmIv)
            val ct = data.copyOfRange(gcmIv, data.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(tagBits, iv)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
            return cipher.doFinal(ct)
        }

        private fun wrapPlain(data: ByteArray): ByteArray {
            val out = ByteArray(magic.size + 1 + data.size)
            System.arraycopy(magic, 0, out, 0, magic.size)
            out[magic.size] = modePlain
            System.arraycopy(data, 0, out, magic.size + 1, data.size)
            return out
        }

        private fun wrapEncrypted(data: ByteArray): ByteArray {
            val out = ByteArray(magic.size + 1 + data.size)
            System.arraycopy(magic, 0, out, 0, magic.size)
            out[magic.size] = modeEncrypted
            System.arraycopy(data, 0, out, magic.size + 1, data.size)
            return out
        }

        private fun readEnvelope(data: ByteArray): Pair<Byte, ByteArray>? {
            if(data.size <= magic.size) return null
            if(!data.copyOfRange(0, magic.size).contentEquals(magic)) return null
            val mode = data[magic.size]
            val payload = data.copyOfRange(magic.size + 1, data.size)
            return mode to payload
        }

        private fun decryptPayload(payload: ByteArray, key: ByteArray): ByteArray? {
            if (payload.size <= gcmIv) {
                logger.warn("Encrypted payload too small to decrypt")
                return null
            }
            return try {
                decrypt(payload, key)
            } catch (t: Throwable) {
                logger.warn("Failed to decrypt MSAL cache", t)
                null
            }
        }

        private fun deserializeUtf8(ctx: ITokenCacheAccessContext, data: ByteArray) {
            ctx.tokenCache().deserialize(String(data, Charsets.UTF_8))
        }

        private fun atomicWrite(bytes: ByteArray) {
            val tmp = VPath.get(cacheFile.parent(), "${cacheFile.fileName()}.tmp")
            tmp.writeBytes(bytes)
            try {
                Files.move(
                    tmp.toJPath(),
                    cacheFile.toJPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Throwable) {
                tmp.toJFile().renameTo(cacheFile.toJFile())
            }
        }

        override fun beforeCacheAccess(ctx: ITokenCacheAccessContext) {
            lock.lock()
            try {
                if (!cacheFile.exists()) return
                val enc = cacheFile.bytesOrNull() ?: return
                val key = getExistingKey()
                val env = readEnvelope(enc)
                if (env != null) {
                    val (mode, payload) = env
                    when (mode) {
                        modePlain -> deserializeUtf8(ctx, payload)
                        modeEncrypted -> {
                            if (key == null) {
                                logger.warn("MSAL cache is encrypted but encryption key is unavailable; skipping cache load")
                                return
                            }
                            val plain = decryptPayload(payload, key)
                            if (plain == null) {
                                logger.warn("MSAL cache encrypted payload could not be decrypted; clearing cache file")
                                cacheFile.delete()
                                return
                            }
                            deserializeUtf8(ctx, plain)
                        }

                        else -> {
                            logger.warn("MSAL cache envelope mode '$mode' unrecognized; clearing cache file")
                            cacheFile.delete()
                        }
                    }
                    return
                }

                if (key == null) {
                    deserializeUtf8(ctx, enc)
                    return
                }

                val plain = decryptPayload(enc, key)
                if (plain != null) {
                    deserializeUtf8(ctx, plain)
                    return
                }

                try {
                    deserializeUtf8(ctx, enc)
                    logger.warn("MSAL cache was unencrypted; using plaintext fallback")
                } catch (t: Throwable) {
                    logger.warn("MSAL cache unreadable; clearing", t)
                    cacheFile.delete()
                }
            } catch (t: Throwable) {
                logger.warn("'beforeCacheAccess' failed", t)
            } finally {
                lock.unlock()
            }
        }

        override fun afterCacheAccess(ctx: ITokenCacheAccessContext) {
            if(!ctx.hasCacheChanged()) return
            lock.lock()
            try {
                val data = ctx.tokenCache().serialize() ?: return
                val key = getOrCreateKey()
                if(key == null) {
                    atomicWrite(wrapPlain(data.toByteArray(Charsets.UTF_8)))
                } else {
                    val enc = encrypt(data.toByteArray(Charsets.UTF_8), key)
                    atomicWrite(wrapEncrypted(enc))
                }
            } catch (t: Throwable) {
                logger.warn("'afterCacheAccess' failed", t)
            } finally {
                lock.unlock()
            }
        }
    }
}

package io.github.footermandev.tritium.auth

import com.microsoft.aad.msal4j.ITokenCacheAccessAspect
import com.microsoft.aad.msal4j.ITokenCacheAccessContext
import com.microsoft.aad.msal4j.PublicClientApplication
import io.github.footermandev.tritium.TConstants.CLIENT_ID
import java.io.File

internal object MSAL {
    private class FileTokenCache(private val cacheFile: File) : ITokenCacheAccessAspect {
        override fun beforeCacheAccess(ctx: ITokenCacheAccessContext) {
            if(cacheFile.exists()) {
                val data = cacheFile.readText(Charsets.UTF_8)
                ctx.tokenCache()?.deserialize(data)
            }
        }

        override fun afterCacheAccess(ctx: ITokenCacheAccessContext) {
            if(ctx.hasCacheChanged()) {
                val data = ctx.tokenCache()?.serialize()
                if (data != null) {
                    cacheFile.writeText(data, Charsets.UTF_8)
                }
            }
        }
    }

    val cacheFile = File("msal.json")
    val app: PublicClientApplication =
        PublicClientApplication.builder(CLIENT_ID)
            .authority("https://login.microsoftonline.com/consumers")
            .setTokenCacheAccessAspect(FileTokenCache(cacheFile))
            .build()
}
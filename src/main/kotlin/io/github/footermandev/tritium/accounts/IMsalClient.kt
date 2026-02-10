package io.github.footermandev.tritium.accounts

import com.microsoft.aad.msal4j.IAccount
import java.util.concurrent.CompletableFuture

/**
 * Abstraction over MSAL account listing for testability.
 */
interface IMsalClient {
    /** Returns the MSAL account set. */
    fun accounts(): CompletableFuture<MutableSet<IAccount>>
}

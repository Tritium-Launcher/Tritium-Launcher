package io.github.footermandev.tritium.bootstrap

import io.github.footermandev.tritium.accounts.MicrosoftAuth
import io.github.footermandev.tritium.ktor.BackgroundTaskQueue
import io.github.footermandev.tritium.mainLogger
import io.github.footermandev.tritium.platform.ClientIdentity
import kotlin.time.Duration.Companion.milliseconds

internal suspend fun runLowPriorityTasks() {
    initUserAgent()
    scheduleRuntimeCacheMaintenance()
}

internal suspend fun initUserAgent() = enqueueGlobalBackgroundTask {
    mainLogger.info("User-Agent: ${ClientIdentity.userAgent}")
}

internal suspend fun scheduleRuntimeCacheMaintenance() = enqueueGlobalBackgroundTask {
    runCatching { MicrosoftAuth.runSharedCacheMaintenanceIfDue() }
        .onFailure { mainLogger.debug("Runtime cache maintenance task failed", it) }
}

internal val globalBackgroundTaskQueue: BackgroundTaskQueue = BackgroundTaskQueue("tritium-low", 1024, 1, 250L.milliseconds)

internal suspend fun enqueueGlobalBackgroundTask(task: suspend () -> Unit) {
    globalBackgroundTaskQueue.enqueue(task)
}

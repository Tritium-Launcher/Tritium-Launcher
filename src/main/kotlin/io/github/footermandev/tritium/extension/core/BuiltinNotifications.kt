package io.github.footermandev.tritium.extension.core

import io.github.footermandev.tritium.ui.notifications.NotificationDefinition
import io.github.footermandev.tritium.ui.theme.TIcons
import io.github.footermandev.tritium.ui.theme.qt.icon

/**
 * Built-in notification definitions provided by the core extension.
 */
internal object BuiltinNotifications {
    val Generic = NotificationDefinition(
        id = "generic",
        header = "Notification",
        description = "",
        icon = TIcons.QuestionMark.icon,
        sendToOsByDefault = false
    )

    /** Posted when a modpack bootstrap completes successfully. */
    val ModpackBootstrapSuccess = NotificationDefinition(
        id = "bootstrap_success",
        header = "Bootstrap Finished",
        description = "Modpack bootstrap finished successfully.",
        icon = TIcons.Run.icon,
        sendToOsByDefault = false
    )

    /** Posted when a modpack bootstrap fails. */
    val ModpackBootstrapFailed = NotificationDefinition(
        id = "bootstrap_failure",
        header = "Bootstrap Failed",
        description = "Modpack bootstrap failed.",
        icon = TIcons.Cross.icon,
        sendToOsByDefault = false
    )

    val All = listOf(
        Generic,
        ModpackBootstrapSuccess,
        ModpackBootstrapFailed
    )
}

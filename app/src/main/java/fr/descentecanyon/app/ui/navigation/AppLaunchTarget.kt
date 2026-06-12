package fr.descentecanyon.app.ui.navigation

import android.content.Intent

sealed interface AppLaunchTarget {
    data object None : AppLaunchTarget
    data object Notifications : AppLaunchTarget
    data class CanyonDetail(
        val canyonId: Int,
        val openDebitsTab: Boolean,
    ) : AppLaunchTarget
}

fun Intent.putLaunchTarget(target: AppLaunchTarget): Intent {
    return when (target) {
        AppLaunchTarget.None -> this
        AppLaunchTarget.Notifications -> putExtra(EXTRA_LAUNCH_TARGET_KIND, TARGET_NOTIFICATIONS)
        is AppLaunchTarget.CanyonDetail -> putExtra(EXTRA_LAUNCH_TARGET_KIND, TARGET_CANYON_DETAIL)
            .putExtra(EXTRA_CANYON_ID, target.canyonId)
            .putExtra(EXTRA_OPEN_DEBITS_TAB, target.openDebitsTab)
    }
}

fun consumeLaunchTarget(intent: Intent?): AppLaunchTarget {
    if (intent == null) return AppLaunchTarget.None
    val target = when (intent.getStringExtra(EXTRA_LAUNCH_TARGET_KIND)) {
        TARGET_NOTIFICATIONS -> AppLaunchTarget.Notifications
        TARGET_CANYON_DETAIL -> {
            val canyonId = intent.getIntExtra(EXTRA_CANYON_ID, 0)
            if (canyonId > 0) {
                AppLaunchTarget.CanyonDetail(
                    canyonId = canyonId,
                    openDebitsTab = intent.getBooleanExtra(EXTRA_OPEN_DEBITS_TAB, false),
                )
            } else {
                AppLaunchTarget.None
            }
        }

        else -> AppLaunchTarget.None
    }
    intent.removeExtra(EXTRA_LAUNCH_TARGET_KIND)
    intent.removeExtra(EXTRA_CANYON_ID)
    intent.removeExtra(EXTRA_OPEN_DEBITS_TAB)
    return target
}

private const val EXTRA_LAUNCH_TARGET_KIND = "launch_target_kind"
private const val EXTRA_CANYON_ID = "launch_target_canyon_id"
private const val EXTRA_OPEN_DEBITS_TAB = "launch_target_open_debits_tab"
private const val TARGET_NOTIFICATIONS = "notifications"
private const val TARGET_CANYON_DETAIL = "canyon_detail"

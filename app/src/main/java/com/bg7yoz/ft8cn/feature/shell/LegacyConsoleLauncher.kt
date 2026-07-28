package com.bg7yoz.ft8cn.feature.shell

import android.content.Context
import android.content.Intent
import com.bg7yoz.ft8cn.MainActivity
import com.bg7yoz.ft8cn.R

enum class LegacyConsoleDestination(val navigationId: Int) {
    DECODE(R.id.menu_nav_calling_list),
    CALL(R.id.menu_nav_mycalling),
    SPECTRUM(R.id.menu_nav_spectrum),
    HISTORY(R.id.menu_nav_history),
    SETTINGS(R.id.menu_nav_config),
}

/** 迁移期间只允许跳转到白名单目的地，避免任意资源 ID 进入 NavController。 */
object LegacyConsoleLauncher {
    const val EXTRA_DESTINATION = "com.bg7yoz.ft8cn.extra.LEGACY_DESTINATION"

    @JvmStatic
    fun open(context: Context, destination: LegacyConsoleDestination) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_DESTINATION, destination.navigationId)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }

    @JvmStatic
    fun resolveDestination(intent: Intent?): Int {
        val requested = intent?.getIntExtra(EXTRA_DESTINATION, 0) ?: 0
        return LegacyConsoleDestination.values()
            .firstOrNull { it.navigationId == requested }
            ?.navigationId
            ?: 0
    }
}

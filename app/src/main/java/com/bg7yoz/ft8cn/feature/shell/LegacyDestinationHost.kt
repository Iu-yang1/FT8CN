package com.bg7yoz.ft8cn.feature.shell

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.bg7yoz.ft8cn.R

/**
 * 在 Compose 页面内承载尚未迁移完的生产 Fragment。
 * 这条兼容边界确保日志、统计和 WebUI 导出在迁移期间不丢功能或用户数据。
 */
@Composable
fun LegacyDestinationHost(
    targetDestinationId: Int,
    instanceKey: Int = 0,
    modifier: Modifier = Modifier,
) {
    val activity = LocalContext.current as Ft8cnActivity
    val fragmentManager = activity.supportFragmentManager
    val containerId = remember(targetDestinationId, instanceKey) { View.generateViewId() }
    val hostTag = remember(targetDestinationId, instanceKey) {
        "ft8cn-embedded-nav-$targetDestinationId-$instanceKey"
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            FragmentContainerView(context).apply {
                id = containerId
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                post { attachEmbeddedHost(fragmentManager, this, hostTag, targetDestinationId) }
            }
        },
        update = { container ->
            container.post { attachEmbeddedHost(fragmentManager, container, hostTag, targetDestinationId) }
        },
    )

    DisposableEffect(fragmentManager, hostTag) {
        onDispose {
            val host = fragmentManager.findFragmentByTag(hostTag)
            if (host != null && !fragmentManager.isStateSaved) {
                fragmentManager.commitNow { remove(host) }
            }
        }
    }
}

private fun attachEmbeddedHost(
    fragmentManager: androidx.fragment.app.FragmentManager,
    container: FragmentContainerView,
    hostTag: String,
    targetDestinationId: Int,
) {
    if (!container.isAttachedToWindow || fragmentManager.isStateSaved) return
    var host = fragmentManager.findFragmentByTag(hostTag) as? NavHostFragment
    val detachedHost = host?.takeIf { it.view?.parent !== container }
    if (detachedHost != null) {
        fragmentManager.commitNow { remove(detachedHost) }
        host = null
    }
    val needsInitialNavigation = host == null
    val activeHost = host ?: NavHostFragment.create(R.navigation.main_navigation).also {
        fragmentManager.commitNow { replace(container.id, it, hostTag) }
    }
    // 仅在创建嵌入 NavHost 时选择入口。后续 Compose 重组不能覆盖日志页内部的统计、QRZ 等导航。
    if (needsInitialNavigation && activeHost.navController.currentDestination?.id != targetDestinationId) {
        activeHost.navController.navigate(
            targetDestinationId,
            null,
            NavOptions.Builder().setLaunchSingleTop(true).build(),
        )
    }
}

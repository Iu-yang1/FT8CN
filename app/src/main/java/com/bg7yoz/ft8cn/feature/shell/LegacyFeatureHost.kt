package com.bg7yoz.ft8cn.feature.shell

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.bg7yoz.ft8cn.R
import com.bg7yoz.ft8cn.core.model.FeatureDestination

/**
 * 在单一 Compose 界面中承载已验证的解码、发射和频谱 Fragment。
 * 宿主始终保留在组合树中，避免切页后 Fragment 仍绑定到已经脱离窗口的旧容器。
 */
@Composable
fun LegacyFeatureHost(
    destination: FeatureDestination?,
    modifier: Modifier = Modifier,
) {
    val activity = LocalContext.current as Ft8cnActivity
    val fragmentManager = activity.supportFragmentManager
    val targetId = when (destination) {
        FeatureDestination.DECODE -> R.id.menu_nav_calling_list
        FeatureDestination.CALL -> R.id.menu_nav_mycalling
        FeatureDestination.SPECTRUM -> R.id.menu_nav_spectrum
        else -> null
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            FragmentContainerView(context).apply {
                id = R.id.fragmentContainerView
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                visibility = if (targetId == null) View.GONE else View.VISIBLE
                post { updateLegacyNavHost(fragmentManager, this, targetId) }
            }
        },
        update = { container ->
            container.visibility = if (targetId == null) View.GONE else View.VISIBLE
            container.post { updateLegacyNavHost(fragmentManager, container, targetId) }
        },
    )

    DisposableEffect(fragmentManager) {
        onDispose {
            val navHost = fragmentManager.findFragmentByTag(LEGACY_NAV_HOST_TAG)
            if (navHost != null && !fragmentManager.isStateSaved) {
                fragmentManager.commitNow { remove(navHost) }
            }
        }
    }
}

private fun updateLegacyNavHost(
    fragmentManager: androidx.fragment.app.FragmentManager,
    container: FragmentContainerView,
    targetId: Int?,
) {
    if (!container.isAttachedToWindow || fragmentManager.isStateSaved) return

    fragmentManager.fragments
        .filter { it.tag?.startsWith(LEGACY_NAV_HOST_TAG_PREFIX) == true && it.tag != LEGACY_NAV_HOST_TAG }
        .forEach { stale -> fragmentManager.commitNow { remove(stale) } }

    var navHost = fragmentManager.findFragmentByTag(LEGACY_NAV_HOST_TAG) as? NavHostFragment
    if (navHost != null && navHost.view?.parent !== container) {
        val detachedHost = navHost
        fragmentManager.commitNow { remove(detachedHost) }
        navHost = null
    }
    val activeNavHost = navHost ?: NavHostFragment.create(R.navigation.main_navigation).also {
        fragmentManager.commitNow {
            replace(container.id, it, LEGACY_NAV_HOST_TAG)
            setPrimaryNavigationFragment(it)
        }
    }

    val desiredLifecycle = if (targetId == null) Lifecycle.State.STARTED else Lifecycle.State.RESUMED
    if (activeNavHost.lifecycle.currentState != desiredLifecycle) {
        fragmentManager.commitNow { setMaxLifecycle(activeNavHost, desiredLifecycle) }
    }
    if (targetId != null && activeNavHost.navController.currentDestination?.id != targetId) {
        activeNavHost.navController.navigate(
            targetId,
            null,
            NavOptions.Builder().setLaunchSingleTop(true).build(),
        )
    }
}

private const val LEGACY_NAV_HOST_TAG_PREFIX = "ft8cn-operation-nav-host"
private const val LEGACY_NAV_HOST_TAG = "$LEGACY_NAV_HOST_TAG_PREFIX:persistent"

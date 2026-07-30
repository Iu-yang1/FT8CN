package com.bg7yoz.ft8cn.feature.shell

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.bg7yoz.ft8cn.GeneralVariables
import com.bg7yoz.ft8cn.MainViewModel
import com.bg7yoz.ft8cn.R
import com.bg7yoz.ft8cn.core.FeatureAppGraph
import com.bg7yoz.ft8cn.core.model.FeatureDestination
import com.bg7yoz.ft8cn.feature.eme.EmeScreen
import com.bg7yoz.ft8cn.feature.logbook.LogbookScreen
import com.bg7yoz.ft8cn.feature.radio.RadioScreen
import com.bg7yoz.ft8cn.feature.satellite.SatelliteScreen
import com.bg7yoz.ft8cn.feature.settings.SettingsScreen
import com.bg7yoz.ft8cn.grid_tracker.GridTrackerMainActivity
import com.bg7yoz.ft8cn.timer.UtcTimer
import com.bg7yoz.ft8cn.ui.FreqDialog
import com.bg7yoz.ft8cn.ui.SetVolumeDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** FT8CN 单一 Material 3 导航；所有功能入口统一位于底部图标栏。 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun Ft8cnFeatureShell(
    mainViewModel: MainViewModel,
    initialDestinationRoute: String? = null,
) {
    val context = LocalContext.current
    val settingsStore = FeatureAppGraph.from(context).settings
    val settings by settingsStore.state.collectAsStateWithLifecycle(initialValue = null)
    val navController = rememberNavController()
    val explicitInitialRoute = remember(initialDestinationRoute) {
        FeatureDestination.values().firstOrNull { it.route == initialDestinationRoute }?.route
    }
    val scope = rememberCoroutineScope()
    var restoredSavedRoute by rememberSaveable { mutableStateOf(false) }
    var bottomNavigationVisible by rememberSaveable { mutableStateOf(true) }
    var quickActionsExpanded by rememberSaveable { mutableStateOf(false) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val selectedRoute = backStackEntry?.destination?.route ?: FeatureDestination.DECODE.route
    val currentDestination = FeatureDestination.values()
        .firstOrNull { it.route == selectedRoute }
        ?: FeatureDestination.DECODE
    val navigate: (FeatureDestination) -> Unit = { destination ->
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        scope.launch { settingsStore.setSelectedDestination(destination.route) }
    }

    LaunchedEffect(initialDestinationRoute, settings?.selectedDestination) {
        if (explicitInitialRoute != null) {
            restoredSavedRoute = true
            return@LaunchedEffect
        }
        val savedRoute = settings?.selectedDestination ?: return@LaunchedEffect
        if (restoredSavedRoute) return@LaunchedEffect
        restoredSavedRoute = true
        val saved = FeatureDestination.values().firstOrNull { it.route == savedRoute }
        if (saved != null && saved.route != selectedRoute) {
            navController.navigate(saved.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        bottomBar = {
            if (bottomNavigationVisible) FeatureBottomNavigation(selectedRoute, navigate)
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val recording = rememberLiveDataValue(mainViewModel.mutableIsRecording, false)
            SlotCaptureProgress(mainViewModel, recording)
            RuntimeStatusOverlay(mainViewModel, recording)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                FeatureNavHost(
                    navController = navController,
                    mainViewModel = mainViewModel,
                    startDestination = explicitInitialRoute ?: FeatureDestination.DECODE.route,
                    modifier = Modifier.fillMaxSize(),
                )
                if (currentDestination.isLegacyOperationPage()) {
                    LegacyFeatureHost(
                        destination = currentDestination,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                QuickActionRail(
                    mainViewModel = mainViewModel,
                    bottomNavigationVisible = bottomNavigationVisible,
                    expanded = quickActionsExpanded,
                    onToggleExpanded = { quickActionsExpanded = !quickActionsExpanded },
                    onToggleBottomNavigation = {
                        bottomNavigationVisible = !bottomNavigationVisible
                    },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

/** 恢复旧版右侧浮动栏能力，同时使用 Material 3 触控尺寸和统一青绿色。 */
@Composable
private fun QuickActionRail(
    mainViewModel: MainViewModel,
    bottomNavigationVisible: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleBottomNavigation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier.padding(end = 7.dp).zIndex(20f),
        verticalArrangement = Arrangement.spacedBy(7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        QuickActionButton(
            icon = if (expanded) R.drawable.ic_baseline_chevron_right_24
            else R.drawable.ic_baseline_chevron_left_24,
            description = if (expanded) "收起浮动工具栏" else "展开浮动工具栏",
            onClick = onToggleExpanded,
        )
        if (expanded) {
            QuickActionButton(
                icon = if (bottomNavigationVisible) R.drawable.ic_baseline_fullscreen_24
                else R.drawable.ic_baseline_fullscreen_exit_24,
                description = if (bottomNavigationVisible) "隐藏底部栏" else "显示底部栏",
                onClick = onToggleBottomNavigation,
            )
            QuickActionButton(R.drawable.ic_baseline_freq_24, "选择工作频率") {
                FreqDialog(context, mainViewModel).show()
            }
            QuickActionButton(R.drawable.ic_baseline_volume_up_24, "调整发射音量") {
                SetVolumeDialog(context, mainViewModel).show()
            }
            QuickActionButton(R.drawable.ic_baseline_grid_tracker_24, "网格追踪") {
                context.startActivity(Intent(context, GridTrackerMainActivity::class.java))
            }
        }
    }
}

@Composable
private fun QuickActionButton(icon: Int, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.88f))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(25.dp),
        )
    }
}

/** 与旧界面一致：显示当前模式槽位的录音进度，发射槽使用警示色。 */
@Composable
private fun SlotCaptureProgress(mainViewModel: MainViewModel, recording: Boolean) {
    var progress by rememberSaveable { mutableStateOf(0f) }
    var transmitSlot by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(mainViewModel, recording) {
        while (true) {
            val slotMillis = GeneralVariables.getCurrentSlotTimeMillisecond()
            val slotTenths = GeneralVariables.getCurrentSlotTimeM()
            if (!recording || GeneralVariables.isExperimentalCodecEnabled() || slotMillis <= 0) {
                progress = 0f
                transmitSlot = false
            } else {
                val nowUtc = UtcTimer.getSystemTime()
                progress = ((nowUtc % slotMillis).toFloat() / slotMillis.toFloat()).coerceIn(0f, 1f)
                transmitSlot = mainViewModel.ft8TransmitSignal.isActivated &&
                    mainViewModel.ft8TransmitSignal.sequential == UtcTimer.getNowSequential(slotTenths)
            }
            delay(50L)
        }
    }
    LinearProgressIndicator(
        progress = progress,
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .semantics { contentDescription = "当前模式录音槽位进度" },
        color = when {
            !recording -> MaterialTheme.colorScheme.error
            transmitSlot -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        },
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

@Composable
private fun RuntimeStatusOverlay(mainViewModel: MainViewModel, recording: Boolean) {
    val debug = rememberLiveDataValue(GeneralVariables.mutableDebugMessage, "")
    val transmitting = rememberLiveDataValue(mainViewModel.ft8TransmitSignal.mutableIsTransmitting, false)
    val transmittingMessage = rememberLiveDataValue(
        mainViewModel.ft8TransmitSignal.mutableTransmittingMessage,
        "",
    )
    val importing = rememberLiveDataValue(mainViewModel.mutableImportShareRunning, false)
    val importInfo = rememberLiveDataValue(mainViewModel.mutableShareInfo, "")
    val importPosition = rememberLiveDataValue(mainViewModel.mutableSharePosition, 0)
    val importCount = rememberLiveDataValue(mainViewModel.mutableShareCount, 0)

    Column(Modifier.fillMaxWidth()) {
        if (!recording) {
            RuntimeBanner("音频输入未启动，频谱与解码暂不可用", MaterialTheme.colorScheme.errorContainer)
        }
        if (transmitting) {
            RuntimeBanner(
                "发射中${transmittingMessage.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}",
                MaterialTheme.colorScheme.errorContainer,
            )
        }
        if (importing) {
            RuntimeBanner(
                "$importInfo  $importPosition/$importCount",
                MaterialTheme.colorScheme.primaryContainer,
            )
        }
        if (debug.isNotBlank()) {
            RuntimeBanner(debug, MaterialTheme.colorScheme.primaryContainer)
        }
    }
}

@Composable
private fun RuntimeBanner(message: String, color: androidx.compose.ui.graphics.Color) {
    Surface(color = color, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun <T> rememberLiveDataValue(source: LiveData<T>, fallback: T): T {
    val owner = LocalLifecycleOwner.current
    var value by remember(source) { mutableStateOf(source.value ?: fallback) }
    DisposableEffect(source, owner) {
        val observer = Observer<T> { next -> if (next != null) value = next }
        source.observe(owner, observer)
        onDispose { source.removeObserver(observer) }
    }
    return value
}

@Composable
private fun FeatureBottomNavigation(
    selectedRoute: String,
    navigate: (FeatureDestination) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 3.dp) {
        Row(modifier = Modifier.fillMaxWidth().height(64.dp)) {
            FeatureDestination.values().forEach { destination ->
                val selected = selectedRoute == destination.route
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .padding(vertical = 8.dp, horizontal = 2.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .clickable { navigate(destination) }
                        .testTag("bottom-nav-${destination.route}")
                        .semantics { contentDescription = destination.label },
                ) {
                    Icon(
                        painter = painterResource(destination.iconResource()),
                        contentDescription = null,
                        tint = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

private fun FeatureDestination.iconResource(): Int = when (this) {
    FeatureDestination.DECODE -> R.drawable.nav_calling_list_image
    FeatureDestination.CALL -> R.drawable.nav_my_calling_image
    FeatureDestination.SPECTRUM -> R.drawable.ic_nav_spectrum_24
    FeatureDestination.EME -> R.drawable.ic_nav_eme_24
    FeatureDestination.SATELLITE -> R.drawable.ic_nav_satellite_24
    FeatureDestination.LOGBOOK -> R.drawable.ic_baseline_history_24
    FeatureDestination.RADIO -> R.drawable.ic_baseline_settings_bluetooth_24
    FeatureDestination.SETTINGS -> R.drawable.ic_baseline_settings_24
}

private fun FeatureDestination.isLegacyOperationPage(): Boolean =
    this == FeatureDestination.DECODE || this == FeatureDestination.CALL || this == FeatureDestination.SPECTRUM

@Composable
private fun FeatureNavHost(
    navController: androidx.navigation.NavHostController,
    mainViewModel: MainViewModel,
    startDestination: String,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable(FeatureDestination.DECODE.route) { Box(Modifier.fillMaxSize()) }
        composable(FeatureDestination.CALL.route) { Box(Modifier.fillMaxSize()) }
        composable(FeatureDestination.SPECTRUM.route) { Box(Modifier.fillMaxSize()) }
        composable(FeatureDestination.EME.route) { EmeScreen(mainViewModel) }
        composable(FeatureDestination.SATELLITE.route) { SatelliteScreen(mainViewModel) }
        composable(FeatureDestination.LOGBOOK.route) { LogbookScreen() }
        composable(FeatureDestination.RADIO.route) { RadioScreen(mainViewModel) }
        composable(FeatureDestination.SETTINGS.route) { SettingsScreen(mainViewModel) }
    }
}

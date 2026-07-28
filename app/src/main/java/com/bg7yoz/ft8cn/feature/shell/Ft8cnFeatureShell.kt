package com.bg7yoz.ft8cn.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.bg7yoz.ft8cn.core.model.FeatureDestination
import com.bg7yoz.ft8cn.feature.call.CallScreen
import com.bg7yoz.ft8cn.feature.eme.EmeScreen
import com.bg7yoz.ft8cn.feature.logbook.LogbookScreen
import com.bg7yoz.ft8cn.feature.radio.RadioScreen
import com.bg7yoz.ft8cn.feature.satellite.SatelliteScreen
import com.bg7yoz.ft8cn.feature.settings.SettingsScreen
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun Ft8cnFeatureShell() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val selectedRoute = backStackEntry?.destination?.route ?: FeatureDestination.CALL.route
    val currentDestination = FeatureDestination.values()
        .firstOrNull { it.route == selectedRoute }
        ?: FeatureDestination.CALL
    val navigate: (FeatureDestination) -> Unit = { destination ->
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (maxWidth >= 720.dp) {
            Row(modifier = Modifier.fillMaxSize()) {
                FeatureNavigationRail(selectedRoute, navigate)
                FeatureScaffold(
                    destination = currentDestination,
                    modifier = Modifier.weight(1f),
                    navHost = { modifier -> FeatureNavHost(navController, modifier) },
                )
            }
        } else {
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(modifier = Modifier.fillMaxHeight()) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 24.dp)) {
                            Text(
                                text = "FT8CN",
                                style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                            Text(
                                text = "数字通信工作台",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                            FeatureDestination.values().forEach { destination ->
                                NavigationDrawerItem(
                                    label = { Text(destination.label) },
                                    icon = { DestinationMark(destination) },
                                    selected = selectedRoute == destination.route,
                                    onClick = {
                                        navigate(destination)
                                        scope.launch { drawerState.close() }
                                    },
                                    modifier = Modifier.testTag("nav-${destination.route}"),
                                )
                            }
                        }
                    }
                },
            ) {
                FeatureScaffold(
                    destination = currentDestination,
                    navigationIcon = {
                        IconButton(
                            onClick = { scope.launch { drawerState.open() } },
                            modifier = Modifier.semantics { contentDescription = "打开功能导航" },
                        ) {
                            Text("菜单", style = MaterialTheme.typography.labelLarge)
                        }
                    },
                    bottomBar = { FeatureBottomNavigation(selectedRoute, navigate) },
                    navHost = { modifier -> FeatureNavHost(navController, modifier) },
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun FeatureScaffold(
    destination: FeatureDestination,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    navHost: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(destination.label) },
                navigationIcon = navigationIcon,
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        navHost(Modifier.padding(padding))
    }
}

@Composable
private fun FeatureBottomNavigation(
    selectedRoute: String,
    navigate: (FeatureDestination) -> Unit,
) {
    NavigationBar {
        FeatureDestination.values().forEach { destination ->
            NavigationBarItem(
                selected = selectedRoute == destination.route,
                onClick = { navigate(destination) },
                icon = { DestinationMark(destination) },
                label = { Text(destination.navigationLabel, maxLines = 1) },
                modifier = Modifier.testTag("bottom-nav-${destination.route}"),
            )
        }
    }
}

@Composable
private fun FeatureNavigationRail(
    selectedRoute: String,
    navigate: (FeatureDestination) -> Unit,
) {
    NavigationRail {
        FeatureDestination.values().forEach { destination ->
            NavigationRailItem(
                selected = selectedRoute == destination.route,
                onClick = { navigate(destination) },
                icon = { DestinationMark(destination) },
                label = { Text(destination.label) },
                modifier = Modifier.testTag("nav-${destination.route}"),
            )
        }
    }
}

@Composable
private fun DestinationMark(destination: FeatureDestination) {
    Text(
        text = destination.shortLabel,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { contentDescription = destination.label },
    )
}

@Composable
private fun FeatureNavHost(
    navController: androidx.navigation.NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = FeatureDestination.CALL.route,
        modifier = modifier,
    ) {
        composable(FeatureDestination.CALL.route) { CallScreen() }
        composable(FeatureDestination.EME.route) { EmeScreen() }
        composable(FeatureDestination.SATELLITE.route) { SatelliteScreen() }
        composable(FeatureDestination.LOGBOOK.route) { LogbookScreen() }
        composable(FeatureDestination.RADIO.route) { RadioScreen() }
        composable(FeatureDestination.SETTINGS.route) { SettingsScreen() }
    }
}

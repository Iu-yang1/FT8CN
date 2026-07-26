package com.bg7yoz.ft8cn.feature.shell

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun Ft8cnFeatureShell() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val selectedRoute = backStackEntry?.destination?.route ?: FeatureDestination.CALL.route
    val navigate: (FeatureDestination) -> Unit = { destination ->
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp
        if (useRail) {
            Row(modifier = Modifier.fillMaxSize()) {
                FeatureNavigationRail(selectedRoute, navigate)
                FeatureNavHost(navController = navController, modifier = Modifier.weight(1f))
            }
        } else {
            Scaffold(
                bottomBar = { FeatureNavigationBar(selectedRoute, navigate) },
            ) { padding ->
                FeatureNavHost(navController = navController, modifier = Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun FeatureNavigationBar(
    selectedRoute: String,
    navigate: (FeatureDestination) -> Unit,
) {
    NavigationBar {
        FeatureDestination.values().forEach { destination ->
            NavigationBarItem(
                selected = selectedRoute == destination.route,
                onClick = { navigate(destination) },
                icon = { Text(destination.shortLabel) },
                label = { Text(destination.label) },
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
                icon = { Text(destination.shortLabel) },
                label = { Text(destination.label) },
            )
        }
    }
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

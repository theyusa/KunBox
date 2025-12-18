package com.kunk.singbox.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.kunk.singbox.ui.screens.DashboardScreen
import com.kunk.singbox.ui.screens.NodesScreen
import com.kunk.singbox.ui.screens.ProfilesScreen
import com.kunk.singbox.ui.screens.SettingsScreen
// New Screens
import com.kunk.singbox.ui.screens.ProfileEditorScreen
import com.kunk.singbox.ui.screens.NodeDetailScreen
import com.kunk.singbox.ui.screens.RoutingSettingsScreen
import com.kunk.singbox.ui.screens.DnsSettingsScreen
import com.kunk.singbox.ui.screens.TunSettingsScreen
import com.kunk.singbox.ui.screens.DiagnosticsScreen
import com.kunk.singbox.ui.screens.ConnectionSettingsScreen
import com.kunk.singbox.ui.screens.LogsScreen
import com.kunk.singbox.ui.screens.RuleSetsScreen
import com.kunk.singbox.ui.screens.CustomRulesScreen
import com.kunk.singbox.ui.screens.AppRulesScreen
import com.kunk.singbox.ui.screens.AppGroupsScreen
import com.kunk.singbox.ui.screens.SplashScreen
import com.kunk.singbox.ui.screens.RuleSetHubScreen
import com.kunk.singbox.ui.screens.RuleSetRoutingScreen

sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Dashboard : Screen("dashboard")
    object Nodes : Screen("nodes")
    object Profiles : Screen("profiles")
    object Settings : Screen("settings")
    
    // Details & Wizards
    object ProfileEditor : Screen("profile_editor")
    object NodeDetail : Screen("node_detail/{nodeId}") {
        fun createRoute(nodeId: String) = "node_detail/$nodeId"
    }
    object RoutingSettings : Screen("routing_settings")
    object DnsSettings : Screen("dns_settings")
    object TunSettings : Screen("tun_settings")
    object Diagnostics : Screen("diagnostics")
    object Logs : Screen("logs")
    object ConnectionSettings : Screen("connection_settings")
    object RuleSets : Screen("rule_sets")
    object CustomRules : Screen("custom_rules")
    object AppRules : Screen("app_rules")
    object RuleSetHub : Screen("rule_set_hub")
    object RuleSetRouting : Screen("rule_set_routing")
}

const val NAV_ANIMATION_DURATION = 450

private fun tabIndex(route: String?): Int {
    return when {
        route == Screen.Dashboard.route -> 0
        route == Screen.Nodes.route -> 1
        route == Screen.Profiles.route -> 2
        route == Screen.Settings.route -> 3
        else -> 0
    }
}

// Map sub-routes to their parent tab
fun getTabForRoute(route: String?): String {
    if (route == null) return Screen.Dashboard.route
    return when {
        route == Screen.Dashboard.route -> Screen.Dashboard.route
        route == Screen.Diagnostics.route -> Screen.Dashboard.route
        route == Screen.Logs.route -> Screen.Dashboard.route
        
        route == Screen.Nodes.route -> Screen.Nodes.route
        route.startsWith("node_detail") -> Screen.Nodes.route
        
        route == Screen.Profiles.route -> Screen.Profiles.route
        route == Screen.ProfileEditor.route -> Screen.Profiles.route
        
        route == Screen.Settings.route -> Screen.Settings.route
        route == Screen.RoutingSettings.route -> Screen.Settings.route
        route == Screen.DnsSettings.route -> Screen.Settings.route
        route == Screen.TunSettings.route -> Screen.Settings.route
        route == Screen.ConnectionSettings.route -> Screen.Settings.route
        route == Screen.RuleSets.route -> Screen.Settings.route
        route == Screen.CustomRules.route -> Screen.Settings.route
        route == Screen.AppRules.route -> Screen.Settings.route
        route == Screen.RuleSetHub.route -> Screen.Settings.route
        route == Screen.RuleSetRouting.route -> Screen.Settings.route
        
        else -> Screen.Dashboard.route
    }
}

@Composable
fun AppNavigation(navController: NavHostController) {
    val slideSpec = tween<IntOffset>(
        durationMillis = NAV_ANIMATION_DURATION,
        easing = FastOutSlowInEasing
    )

    val enterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        slideInHorizontally(initialOffsetX = { it }, animationSpec = slideSpec)
    }
    val exitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutHorizontally(targetOffsetX = { -it / 3 }, animationSpec = slideSpec)
    }
    val popEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        EnterTransition.None
    }
    val popExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutHorizontally(targetOffsetX = { it }, animationSpec = slideSpec)
    }

    val tabEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        val from = tabIndex(initialState.destination.route)
        val to = tabIndex(targetState.destination.route)
        val direction = if (to >= from) 1 else -1
        slideInHorizontally(initialOffsetX = { direction * it }, animationSpec = slideSpec)
    }

    val tabExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        val from = tabIndex(initialState.destination.route)
        val to = tabIndex(targetState.destination.route)
        val direction = if (to >= from) 1 else -1
        slideOutHorizontally(targetOffsetX = { -direction * it }, animationSpec = slideSpec)
    }

    NavHost(
        navController = navController,
        startDestination = Screen.Dashboard.route
    ) {
        // Main Tabs
        composable(
            route = Screen.Dashboard.route,
            enterTransition = tabEnterTransition,
            exitTransition = tabExitTransition,
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None }
        ) { DashboardScreen(navController) }
        composable(
            route = Screen.Nodes.route,
            enterTransition = tabEnterTransition,
            exitTransition = tabExitTransition,
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None }
        ) { NodesScreen(navController) }
        composable(
            route = Screen.Profiles.route,
            enterTransition = tabEnterTransition,
            exitTransition = tabExitTransition,
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None }
        ) { ProfilesScreen(navController) }
        composable(
            route = Screen.Settings.route,
            enterTransition = tabEnterTransition,
            exitTransition = tabExitTransition,
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None }
        ) { SettingsScreen(navController) }
        
        // Sub Screens
        composable(
            route = Screen.ProfileEditor.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { ProfileEditorScreen(navController) }

        composable(
            route = Screen.NodeDetail.route,
            arguments = listOf(
                androidx.navigation.navArgument("nodeId") { type = androidx.navigation.NavType.StringType }
            ),
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { backStackEntry ->
            val nodeId = backStackEntry.arguments?.getString("nodeId") ?: ""
            NodeDetailScreen(navController = navController, nodeId = nodeId)
        }
        composable(
            route = Screen.RoutingSettings.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { RoutingSettingsScreen(navController) }
        composable(
            route = Screen.DnsSettings.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { DnsSettingsScreen(navController) }
        composable(
            route = Screen.TunSettings.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { TunSettingsScreen(navController) }
        composable(
            route = Screen.Diagnostics.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { DiagnosticsScreen(navController) }
        composable(
            route = Screen.Logs.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { LogsScreen(navController) }
        composable(
            route = Screen.ConnectionSettings.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { ConnectionSettingsScreen(navController) }
        composable(
            route = Screen.RuleSets.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { RuleSetsScreen(navController) }
        composable(
            route = Screen.CustomRules.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { CustomRulesScreen(navController) }
        composable(
            route = Screen.AppRules.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { AppGroupsScreen(navController) }
        composable(
            route = Screen.RuleSetHub.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { RuleSetHubScreen(navController) }
        composable(
            route = Screen.RuleSetRouting.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { RuleSetRoutingScreen(navController) }
    }
}
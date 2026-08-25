package com.aegismed.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aegismed.app.ui.screens.AddMedicationScreen
import com.aegismed.app.ui.screens.DashboardScreen
import com.aegismed.app.ui.screens.InteractionsScreen
import com.aegismed.app.ui.screens.MedDetailScreen
import com.aegismed.app.ui.screens.ReportsScreen
import com.aegismed.app.ui.screens.SettingsScreen

object Routes {
    const val DASHBOARD = "dashboard"
    const val ADD = "add"
    const val MED_DETAIL = "med/{id}"
    const val REPORTS = "reports"
    const val SETTINGS = "settings"
    const val INTERACTIONS = "interactions"

    fun medDetail(id: Long) = "med/$id"
}

@Composable
fun AppNav(activity: androidx.activity.ComponentActivity, viewModel: AppViewModel) {
    val nav: NavHostController = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.DASHBOARD) {
        composable(Routes.DASHBOARD) { DashboardScreen(viewModel, nav) }
        composable(Routes.ADD) { AddMedicationScreen(viewModel, nav) }
        composable(
            Routes.MED_DETAIL,
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: -1L
            MedDetailScreen(viewModel, nav, id)
        }
        composable(Routes.REPORTS) { ReportsScreen(viewModel, nav) }
        composable(Routes.SETTINGS) { SettingsScreen(viewModel, activity, nav) }
        composable(Routes.INTERACTIONS) { InteractionsScreen(viewModel, nav) }
    }
}

package com.studytrack.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.studytrack.app.di.AppContainer
import com.studytrack.app.di.StudyTrackViewModelFactory
import com.studytrack.app.ui.analytics.AnalyticsScreen
import com.studytrack.app.ui.analytics.AnalyticsViewModel
import com.studytrack.app.ui.command.CommandCenterScreen
import com.studytrack.app.ui.command.CommandCenterViewModel
import com.studytrack.app.ui.courses.CourseDetailScreen
import com.studytrack.app.ui.courses.CourseDetailViewModel
import com.studytrack.app.ui.courses.CoursesScreen
import com.studytrack.app.ui.courses.CoursesViewModel
import com.studytrack.app.ui.dashboard.DashboardScreen
import com.studytrack.app.ui.dashboard.DashboardViewModel
import com.studytrack.app.ui.goals.GoalsScreen
import com.studytrack.app.ui.goals.GoalsViewModel
import com.studytrack.app.ui.history.HistoryScreen
import com.studytrack.app.ui.history.HistoryViewModel
import com.studytrack.app.ui.settings.SettingsScreen
import com.studytrack.app.ui.settings.SettingsViewModel
import com.studytrack.app.ui.timer.FocusScreen
import com.studytrack.app.ui.timer.TimerViewModel

/** One-shot text handoff from the Dashboard's quick command bar to the
 *  full Command Center, read once in [CommandCenterViewModel.init] and
 *  cleared immediately \u2014 simpler and more robust than URL-encoding
 *  free text through a nav argument. */
object PendingCommandBridge {
    var pendingText: String? = null
}

sealed class Screen(val route: String) {
    object Dashboard : Screen("dashboard")
    object CommandCenter : Screen("command_center")
    object Courses : Screen("courses")
    object CourseDetail : Screen("course_detail/{courseId}") {
        fun path(courseId: Long) = "course_detail/$courseId"
    }
    object Focus : Screen("focus")
    object History : Screen("history")
    object Goals : Screen("goals")
    object Analytics : Screen("analytics")
    object Settings : Screen("settings")
}

data class BottomNavItem(val screen: Screen, val label: String)

val bottomNavItems = listOf(
    BottomNavItem(Screen.Dashboard, "Home"),
    BottomNavItem(Screen.Goals, "Plan"),
    BottomNavItem(Screen.Courses, "Courses"),
    BottomNavItem(Screen.Analytics, "Analytics"),
    BottomNavItem(Screen.Settings, "More")
)

@Composable
fun StudyTrackNavGraph(navController: NavHostController, container: AppContainer) {
    val factory = StudyTrackViewModelFactory(container)

    NavHost(navController = navController, startDestination = Screen.Dashboard.route) {
        composable(Screen.Dashboard.route) {
            val vm: DashboardViewModel = viewModel(factory = factory)
            DashboardScreen(viewModel = vm, navController = navController)
        }
        composable(Screen.CommandCenter.route) {
            val vm: CommandCenterViewModel = viewModel(factory = factory)
            CommandCenterScreen(viewModel = vm)
        }
        composable(Screen.Courses.route) {
            val vm: CoursesViewModel = viewModel(factory = factory)
            CoursesScreen(viewModel = vm, navController = navController)
        }
        composable(
            Screen.CourseDetail.route,
            arguments = listOf(navArgument("courseId") { type = NavType.LongType })
        ) { backStackEntry ->
            val courseId = backStackEntry.arguments?.getLong("courseId") ?: 0L
            val vm: CourseDetailViewModel = viewModel(factory = factory)
            CourseDetailScreen(courseId = courseId, viewModel = vm)
        }
        composable(Screen.Focus.route) {
            val vm: TimerViewModel = viewModel(factory = factory)
            FocusScreen(viewModel = vm, onDone = { navController.popBackStack() })
        }
        composable(Screen.History.route) {
            val vm: HistoryViewModel = viewModel(factory = factory)
            HistoryScreen(viewModel = vm)
        }
        composable(Screen.Goals.route) {
            val vm: GoalsViewModel = viewModel(factory = factory)
            GoalsScreen(viewModel = vm)
        }
        composable(Screen.Analytics.route) {
            val vm: AnalyticsViewModel = viewModel(factory = factory)
            AnalyticsScreen(viewModel = vm, navController = navController)
        }
        composable(Screen.Settings.route) {
            val vm: SettingsViewModel = viewModel(factory = factory)
            SettingsScreen(viewModel = vm)
        }
    }
}

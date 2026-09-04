package com.studytrack.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.studytrack.app.data.local.SessionStatus
import com.studytrack.app.di.AppContainer
import com.studytrack.app.ui.components.MiniActiveSessionBar
import com.studytrack.app.ui.navigation.Screen
import com.studytrack.app.ui.navigation.StudyTrackNavGraph
import com.studytrack.app.ui.navigation.bottomNavItems
import com.studytrack.app.ui.theme.StudyTrackTheme
import com.studytrack.app.util.formatClock
import kotlinx.coroutines.launch

/**
 * Single Activity, all screens are Composables reached through
 * [StudyTrackNavGraph]. Dependencies come from the single [AppContainer]
 * built in [StudyTrackApplication.onCreate] \u2014 no second DI graph, no
 * Hilt, nothing created here beyond plain Compose state.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as StudyTrackApplication).container
        setContent {
            StudyTrackTheme {
                StudyTrackApp(container = container)
            }
        }
    }
}

@Composable
private fun StudyTrackApp(container: AppContainer) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val activeSession by container.sessionRepository.observeActiveSession()
        .collectAsStateWithLifecycle(initialValue = null)

    Scaffold(
        bottomBar = {
            Column {
                activeSession?.let { session ->
                    MiniActiveSessionBar(
                        label = "Studying",
                        elapsedText = formatClock(session.elapsedMillis()),
                        isRunning = session.status == SessionStatus.RUNNING,
                        onTap = { navController.navigate(Screen.Focus.route) },
                        onPauseResume = {
                            scope.launch {
                                if (session.status == SessionStatus.RUNNING) {
                                    container.sessionRepository.pause()
                                } else {
                                    container.sessionRepository.resume()
                                }
                            }
                        }
                    )
                }

                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = backStackEntry?.destination
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        val selected = currentDestination?.hierarchy?.any { it.route == item.screen.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(item.screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(iconFor(item.screen), contentDescription = item.label) },
                            label = { Text(item.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            StudyTrackNavGraph(navController = navController, container = container)
        }
    }
}

private fun iconFor(screen: Screen): ImageVector = when (screen) {
    Screen.Dashboard -> Icons.Filled.Home
    Screen.Goals -> Icons.Filled.Flag
    Screen.Courses -> Icons.Filled.Book
    Screen.Analytics -> Icons.Filled.BarChart
    Screen.Settings -> Icons.Filled.Settings
    else -> Icons.Filled.Home
}

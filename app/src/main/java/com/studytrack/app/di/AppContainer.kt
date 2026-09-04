package com.studytrack.app.di

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.studytrack.app.data.local.StudyTrackDatabase
import com.studytrack.app.data.repository.BackupRepository
import com.studytrack.app.data.repository.HierarchyRepository
import com.studytrack.app.data.repository.PlanningRepository
import com.studytrack.app.data.repository.SessionRepository
import com.studytrack.app.data.repository.SettingsRepository
import com.studytrack.app.domain.CommandExecutor
import com.studytrack.app.domain.CommandParser
import com.studytrack.app.ui.analytics.AnalyticsViewModel
import com.studytrack.app.ui.command.CommandCenterViewModel
import com.studytrack.app.ui.courses.CourseDetailViewModel
import com.studytrack.app.ui.courses.CoursesViewModel
import com.studytrack.app.ui.dashboard.DashboardViewModel
import com.studytrack.app.ui.goals.GoalsViewModel
import com.studytrack.app.ui.history.HistoryViewModel
import com.studytrack.app.ui.settings.SettingsViewModel
import com.studytrack.app.ui.timer.TimerViewModel

/**
 * Manual dependency injection \u2014 no Hilt/Dagger. This is deliberate for
 * Phase 1: annotation-processor DI adds a whole extra category of build
 * failure that's hard to diagnose without actually running Gradle, and a
 * plain constructor-injected container gives the same testability with
 * far less machinery for an app this size.
 */
class AppContainer(context: Context) {
    private val database = StudyTrackDatabase.getInstance(context)

    val hierarchyRepository = HierarchyRepository(
        database.courseDao(), database.moduleDao(), database.topicDao(), database.learningOutcomeDao()
    )
    val sessionRepository = SessionRepository(
        database.activeSessionDao(), database.studySessionDao(), database.learningOutcomeDao()
    )
    val planningRepository = PlanningRepository(
        database.goalDao(), database.revisionDao(), database.learningOutcomeDao()
    )
    val backupRepository = BackupRepository(database)
    val settingsRepository = SettingsRepository(context.applicationContext)
    val commandParser = CommandParser(hierarchyRepository)
    val commandExecutor = CommandExecutor(hierarchyRepository, sessionRepository, planningRepository)

    fun database(): StudyTrackDatabase = database
}

@Suppress("UNCHECKED_CAST")
class StudyTrackViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when (modelClass) {
            DashboardViewModel::class.java -> DashboardViewModel(
                container.hierarchyRepository, container.sessionRepository, container.planningRepository, container.settingsRepository
            ) as T
            CommandCenterViewModel::class.java -> CommandCenterViewModel(
                container.commandParser, container.commandExecutor
            ) as T
            CoursesViewModel::class.java -> CoursesViewModel(
                container.hierarchyRepository, container.planningRepository
            ) as T
            CourseDetailViewModel::class.java -> CourseDetailViewModel(
                container.hierarchyRepository, container.sessionRepository, container.planningRepository
            ) as T
            TimerViewModel::class.java -> TimerViewModel(
                container.sessionRepository, container.hierarchyRepository, container.planningRepository
            ) as T
            HistoryViewModel::class.java -> HistoryViewModel(
                container.sessionRepository, container.hierarchyRepository
            ) as T
            GoalsViewModel::class.java -> GoalsViewModel(
                container.planningRepository, container.hierarchyRepository, container.sessionRepository
            ) as T
            AnalyticsViewModel::class.java -> AnalyticsViewModel(
                container.hierarchyRepository, container.sessionRepository, container.planningRepository
            ) as T
            SettingsViewModel::class.java -> SettingsViewModel(
                container.settingsRepository, container.backupRepository
            ) as T
            else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}

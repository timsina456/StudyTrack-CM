package com.studytrack.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.studytrack.app.data.local.ActiveSessionEntity
import com.studytrack.app.data.local.LosStatus
import com.studytrack.app.data.local.SessionStatus
import com.studytrack.app.data.repository.HierarchyRepository
import com.studytrack.app.data.repository.PlanningRepository
import com.studytrack.app.data.repository.SessionRepository
import com.studytrack.app.data.repository.SettingsRepository
import com.studytrack.app.domain.Calculators
import com.studytrack.app.domain.CourseProjection
import com.studytrack.app.domain.TodayPlanItem
import com.studytrack.app.ui.components.CommandBar
import com.studytrack.app.ui.components.FinishSessionDialog
import com.studytrack.app.ui.components.LedgerProgressBar
import com.studytrack.app.ui.components.PaceStatusChip
import com.studytrack.app.ui.components.SectionHeader
import com.studytrack.app.ui.components.StatCard
import com.studytrack.app.ui.navigation.PendingCommandBridge
import com.studytrack.app.ui.navigation.Screen
import com.studytrack.app.ui.theme.NumericLarge
import com.studytrack.app.ui.theme.NumericMedium
import com.studytrack.app.util.formatClock
import com.studytrack.app.util.formatMinutes
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalTime

data class ActiveSessionSummary(val label: String, val elapsedMillis: Long, val status: SessionStatus)

data class DashboardUiState(
    val isLoading: Boolean = true,
    val studyMinutesToday: Int = 0,
    val dailyTargetMinutes: Int = 120,
    val weekMinutes: Int = 0,
    val weeklyTargetMinutes: Int = 600,
    val sessionsThisWeek: Int = 0,
    val activeSession: ActiveSessionSummary? = null,
    val todayPlan: List<TodayPlanItem> = emptyList(),
    val dueRevisionsCount: Int = 0,
    val primaryCourseName: String? = null,
    val primaryCourseTargetDate: Long? = null,
    val primaryCourseProgressPercent: Double = 0.0,
    val projection: CourseProjection? = null
)

class DashboardViewModel(
    private val hierarchyRepository: HierarchyRepository,
    private val sessionRepository: SessionRepository,
    private val planningRepository: PlanningRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var tickerJob: Job? = null

    init {
        viewModelScope.launch {
            sessionRepository.observeActiveSession().collectLatest { active ->
                tickerJob?.cancel()
                updateActiveSession(active)
                if (active != null && active.status == SessionStatus.RUNNING) {
                    tickerJob = viewModelScope.launch {
                        while (true) {
                            delay(1000)
                            updateActiveSession(active)
                        }
                    }
                }
            }
        }
        refresh()
    }

    private suspend fun updateActiveSession(active: ActiveSessionEntity?) {
        if (active == null) {
            _uiState.value = _uiState.value.copy(activeSession = null)
            return
        }
        val course = hierarchyRepository.getCourseOnce(active.courseId)
        val module = hierarchyRepository.getModuleOnce(active.moduleId)
        val topic = active.topicId?.let { hierarchyRepository.getTopicOnce(it) }
        val los = active.losId?.let { hierarchyRepository.getLosOnce(it) }
        val label = listOfNotNull(course?.name, module?.name, topic?.name, los?.code).joinToString(" \u2192 ")
        _uiState.value = _uiState.value.copy(
            activeSession = ActiveSessionSummary(label, active.elapsedMillis(), active.status)
        )
    }

    fun refresh() {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            val todayMinutes = sessionRepository.totalMinutesToday()
            val weekMinutes = sessionRepository.totalMinutesThisWeek()
            val sessionsWeek = sessionRepository.sessionCountThisWeek()
            val dueRevisions = planningRepository.observeDueRevisions().first()
            val courses = hierarchyRepository.getAllCoursesOnce()
            val primaryCourse = courses.minByOrNull { it.priority }

            var progress = 0.0
            var projection: CourseProjection? = null
            if (primaryCourse != null) {
                val modules = hierarchyRepository.observeModules(primaryCourse.id).first()
                val allLos = modules.flatMap { m ->
                    hierarchyRepository.observeTopics(m.id).first().flatMap { t -> hierarchyRepository.observeLos(t.id).first() }
                }
                progress = Calculators.courseProgressPercent(allLos)
                val incomplete = allLos.filter { it.status != LosStatus.COMPLETED }
                val actualByLos = incomplete.associate { it.id to sessionRepository.totalMinutesForLos(it.id) }
                val recentPace = weekMinutes / 7.0
                projection = Calculators.computeCourseProjection(
                    incomplete, actualByLos, primaryCourse.targetDate, System.currentTimeMillis(), recentPace
                )
            }

            val allIncompleteLos = hierarchyRepository.getAllLosOnce().filter { it.status != LosStatus.COMPLETED }
            val topicsById = hierarchyRepository.getAllTopicsOnce().associateBy { it.id }
            val remainingToday = (settings.dailyTargetMinutes - todayMinutes).coerceAtLeast(0)
            val plan = Calculators.generateTodayPlan(allIncompleteLos, topicsById, remainingToday).take(3)

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                studyMinutesToday = todayMinutes,
                dailyTargetMinutes = settings.dailyTargetMinutes,
                weekMinutes = weekMinutes,
                weeklyTargetMinutes = settings.weeklyTargetMinutes,
                sessionsThisWeek = sessionsWeek,
                todayPlan = plan,
                dueRevisionsCount = dueRevisions.size,
                primaryCourseName = primaryCourse?.name,
                primaryCourseTargetDate = primaryCourse?.targetDate,
                primaryCourseProgressPercent = progress,
                projection = projection
            )
        }
    }

    fun pause() = viewModelScope.launch { sessionRepository.pause() }
    fun resume() = viewModelScope.launch { sessionRepository.resume() }

    fun finishSession(rating: Int, confidence: Int, notes: String?) = viewModelScope.launch {
        sessionRepository.finish(rating, confidence, notes)
        refresh()
    }
}

@Composable
fun DashboardScreen(viewModel: DashboardViewModel, navController: NavHostController) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var commandText by remember { mutableStateOf("") }
    var showFinishDialog by remember { mutableStateOf(false) }

    fun goToCommandCenter(prefill: String?) {
        if (!prefill.isNullOrBlank()) PendingCommandBridge.pendingText = prefill
        navController.navigate(Screen.CommandCenter.route)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            val greeting = remember {
                when (LocalTime.now().hour) {
                    in 5..11 -> "Good morning"
                    in 12..16 -> "Good afternoon"
                    else -> "Good evening"
                }
            }
            Text(greeting, style = MaterialTheme.typography.headlineMedium)
        }

        item {
            CommandBar(
                text = commandText,
                onTextChange = { commandText = it },
                onSubmit = { goToCommandCenter(commandText); commandText = "" },
                onVoiceResult = { goToCommandCenter(it) }
            )
        }

        state.activeSession?.let { active ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            if (active.status == SessionStatus.RUNNING) "CURRENT SESSION" else "PAUSED SESSION",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(active.label, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(formatClock(active.elapsedMillis), style = NumericLarge)
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = {
                                if (active.status == SessionStatus.RUNNING) viewModel.pause() else viewModel.resume()
                            }) {
                                Text(if (active.status == SessionStatus.RUNNING) "Pause" else "Resume")
                            }
                            TextButton(onClick = { showFinishDialog = true }) { Text("Finish") }
                            TextButton(onClick = { navController.navigate(Screen.Focus.route) }) { Text("Expand") }
                        }
                    }
                }
            }
        }

        item {
            Column {
                SectionHeader("Today's progress")
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "${formatMinutes(state.studyMinutesToday)} / ${formatMinutes(state.dailyTargetMinutes)}",
                    style = NumericMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                LedgerProgressBar(progress = state.studyMinutesToday / state.dailyTargetMinutes.toFloat().coerceAtLeast(1f))
            }
        }

        if (state.todayPlan.isNotEmpty()) {
            item { SectionHeader("Today's plan") }
            items(state.todayPlan) { planItem ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("${planItem.los.code}  ${planItem.los.title}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                planItem.reason,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(formatMinutes(planItem.minutesSuggested), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        if (state.dueRevisionsCount > 0) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Revision due", style = MaterialTheme.typography.bodyMedium)
                        Text("${state.dueRevisionsCount} topics", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        item {
            Column {
                SectionHeader("This week")
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(
                        label = "Hours",
                        value = formatMinutes(state.weekMinutes),
                        subValue = "of ${formatMinutes(state.weeklyTargetMinutes)} target",
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        label = "Sessions",
                        value = state.sessionsThisWeek.toString(),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (state.primaryCourseName != null) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(state.primaryCourseName ?: "", style = MaterialTheme.typography.titleMedium)
                            state.projection?.let { PaceStatusChip(it.status) }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("${state.primaryCourseProgressPercent.toInt()}%", style = NumericLarge)
                        Spacer(modifier = Modifier.height(8.dp))
                        LedgerProgressBar(progress = (state.primaryCourseProgressPercent / 100.0).toFloat())
                        state.projection?.let { p ->
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "${p.remainingLosCount} LOS remaining \u00b7 ${formatMinutes(p.estimatedRemainingMinutes)} left \u00b7 ${p.daysRemaining} days",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "Needs about ${formatMinutes(p.requiredMinutesPerDay.toInt())}/day to stay on pace",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (showFinishDialog) {
        FinishSessionDialog(
            onDismiss = { showFinishDialog = false },
            onConfirm = { rating, confidence, notes ->
                viewModel.finishSession(rating, confidence, notes)
                showFinishDialog = false
            }
        )
    }
}

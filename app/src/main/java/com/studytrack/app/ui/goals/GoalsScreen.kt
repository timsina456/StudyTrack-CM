package com.studytrack.app.ui.goals

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
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
import com.studytrack.app.data.local.CourseEntity
import com.studytrack.app.data.local.GoalEntity
import com.studytrack.app.data.local.GoalType
import com.studytrack.app.data.local.LosStatus
import com.studytrack.app.data.local.RevisionEntity
import com.studytrack.app.data.repository.HierarchyRepository
import com.studytrack.app.data.repository.PlanningRepository
import com.studytrack.app.data.repository.SessionRepository
import com.studytrack.app.domain.Calculators
import com.studytrack.app.domain.CourseProjection
import com.studytrack.app.ui.components.LedgerProgressBar
import com.studytrack.app.ui.components.PaceStatusChip
import com.studytrack.app.ui.components.SectionHeader
import com.studytrack.app.util.formatMinutes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class GoalDisplay(
    val goal: GoalEntity,
    val progressLabel: String,
    val projection: CourseProjection?,
    val progressFraction: Double = 0.0
)
data class RevisionDisplay(val revision: RevisionEntity, val label: String)

data class GoalsUiState(
    val goals: List<GoalDisplay> = emptyList(),
    val dueRevisions: List<RevisionDisplay> = emptyList(),
    val isLoading: Boolean = true
)

class GoalsViewModel(
    private val planningRepository: PlanningRepository,
    private val hierarchyRepository: HierarchyRepository,
    private val sessionRepository: SessionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(GoalsUiState())
    val uiState: StateFlow<GoalsUiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val goals = planningRepository.observeGoals().first()
            val courses = hierarchyRepository.getAllCoursesOnce()
            val displays = goals.map { buildDisplay(it, courses) }

            val dueRevisions = planningRepository.observeDueRevisions().first()
            val losList = hierarchyRepository.getAllLosOnce()
            val revDisplays = dueRevisions.map { r ->
                val los = losList.find { it.id == r.losId }
                RevisionDisplay(r, los?.let { "${it.code} ${it.title}" } ?: "Revision")
            }

            _uiState.value = GoalsUiState(displays, revDisplays, false)
        }
    }

    private suspend fun buildDisplay(goal: GoalEntity, courses: List<CourseEntity>): GoalDisplay {
        return when (goal.goalType) {
            GoalType.COURSE_COMPLETION -> {
                val course = courses.find { it.id == goal.courseId }
                if (course == null) {
                    GoalDisplay(goal, "\u2014", null)
                } else {
                    val modules = hierarchyRepository.observeModules(course.id).first()
                    val allLos = modules.flatMap { m ->
                        hierarchyRepository.observeTopics(m.id).first().flatMap { t -> hierarchyRepository.observeLos(t.id).first() }
                    }
                    val incomplete = allLos.filter { it.status != LosStatus.COMPLETED }
                    val actualByLos = incomplete.associate { it.id to sessionRepository.totalMinutesForLos(it.id) }
                    val weekMinutes = sessionRepository.totalMinutesThisWeek()
                    val projection = Calculators.computeCourseProjection(
                        incomplete, actualByLos, goal.targetDate, System.currentTimeMillis(), weekMinutes / 7.0
                    )
                    val progressPercent = Calculators.courseProgressPercent(allLos)
                    GoalDisplay(goal, "${progressPercent.toInt()}% complete", projection, progressPercent / 100.0)
                }
            }
            GoalType.WEEKLY_HOURS -> {
                val weekMinutes = sessionRepository.totalMinutesThisWeek()
                val targetMinutes = ((goal.targetValue ?: 0.0) * 60).toInt()
                val fraction = if (targetMinutes > 0) weekMinutes.toDouble() / targetMinutes else 0.0
                GoalDisplay(goal, "${formatMinutes(weekMinutes)} / ${formatMinutes(targetMinutes)} this week", null, fraction)
            }
            else -> GoalDisplay(goal, "\u2014", null)
        }
    }

    fun completeRevision(revisionId: Long) = viewModelScope.launch {
        planningRepository.completeRevision(revisionId)
        refresh()
    }

    fun skipRevision(revisionId: Long) = viewModelScope.launch {
        planningRepository.skipRevision(revisionId)
        refresh()
    }

    fun addWeeklyHoursGoal(hours: Double) = viewModelScope.launch {
        planningRepository.addGoal(
            GoalEntity(title = "Study ${hours.toInt()} hours per week", goalType = GoalType.WEEKLY_HOURS, targetValue = hours, priority = 2)
        )
        refresh()
    }
}

@Composable
fun GoalsScreen(viewModel: GoalsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add goal")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Text("Plan", style = MaterialTheme.typography.headlineMedium) }

            if (state.dueRevisions.isNotEmpty()) {
                item { SectionHeader("Revision due") }
                items(state.dueRevisions) { rev ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(rev.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { viewModel.completeRevision(rev.revision.id) }) { Text("Done") }
                            TextButton(onClick = { viewModel.skipRevision(rev.revision.id) }) { Text("Skip") }
                        }
                    }
                }
            }

            item { SectionHeader("Goals") }
            items(state.goals) { display ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(display.goal.title, style = MaterialTheme.typography.titleMedium)
                            display.projection?.let { PaceStatusChip(it.status) }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(display.progressLabel, style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        LedgerProgressBar(progress = display.progressFraction.toFloat())
                        display.projection?.let { p ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "${p.remainingLosCount} remaining \u00b7 needs ${formatMinutes(p.requiredMinutesPerDay.toInt())}/day",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (state.goals.isEmpty() && state.dueRevisions.isEmpty() && !state.isLoading) {
                item { Text("No goals yet \u2014 tap + to add a weekly study target.", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }

    if (showAddDialog) {
        AddGoalDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { hours ->
                viewModel.addWeeklyHoursGoal(hours)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun AddGoalDialog(onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    var hoursText by remember { mutableStateOf("10") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Weekly study goal") },
        text = {
            TextField(
                value = hoursText,
                onValueChange = { hoursText = it },
                label = { Text("Hours per week") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { hoursText.toDoubleOrNull()?.let { onConfirm(it) } }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

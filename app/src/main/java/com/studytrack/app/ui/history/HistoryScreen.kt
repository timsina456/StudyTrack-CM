package com.studytrack.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.studytrack.app.data.local.CourseEntity
import com.studytrack.app.data.local.StudySessionEntity
import com.studytrack.app.data.repository.HierarchyRepository
import com.studytrack.app.data.repository.SessionRepository
import com.studytrack.app.util.formatDateShort
import com.studytrack.app.util.formatMinutes
import com.studytrack.app.util.formatTimeShort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HistoryEntryUi(val session: StudySessionEntity, val label: String)

data class HistoryUiState(
    val entries: List<HistoryEntryUi> = emptyList(),
    val courses: List<CourseEntity> = emptyList(),
    val selectedCourseId: Long? = null,
    val isLoading: Boolean = true
)

class HistoryViewModel(
    private val sessionRepository: SessionRepository,
    private val hierarchyRepository: HierarchyRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val courses = hierarchyRepository.getAllCoursesOnce()
            val modules = hierarchyRepository.getAllModulesOnce()
            val topics = hierarchyRepository.getAllTopicsOnce()
            val losList = hierarchyRepository.getAllLosOnce()
            sessionRepository.observeHistory().collect { sessions ->
                val entries = sessions.map { s ->
                    val course = courses.find { it.id == s.courseId }
                    val module = modules.find { it.id == s.moduleId }
                    val topic = topics.find { it.id == s.topicId }
                    val los = losList.find { it.id == s.losId }
                    val label = listOfNotNull(course?.name, module?.name, topic?.name, los?.code).joinToString(" \u2192 ")
                    HistoryEntryUi(s, label)
                }
                _uiState.value = _uiState.value.copy(entries = entries, courses = courses, isLoading = false)
            }
        }
    }

    fun selectCourse(courseId: Long?) {
        _uiState.value = _uiState.value.copy(selectedCourseId = courseId)
    }
}

@Composable
fun HistoryScreen(viewModel: HistoryViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val filtered = if (state.selectedCourseId == null) state.entries
    else state.entries.filter { it.session.courseId == state.selectedCourseId }
    val grouped = filtered.groupBy { formatDateShort(it.session.startTime) }

    Column(modifier = Modifier.fillMaxSize()) {
        Text("History", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp))

        if (state.courses.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = state.selectedCourseId == null, onClick = { viewModel.selectCourse(null) }, label = { Text("All") })
                state.courses.forEach { course ->
                    FilterChip(
                        selected = state.selectedCourseId == course.id,
                        onClick = { viewModel.selectCourse(course.id) },
                        label = { Text(course.name) }
                    )
                }
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            grouped.forEach { (date, entries) ->
                item {
                    Text(date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(entries) { entry ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    "${formatTimeShort(entry.session.startTime)} \u2013 ${formatTimeShort(entry.session.endTime)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(formatMinutes(entry.session.durationMinutes), style = MaterialTheme.typography.bodyMedium)
                            }
                            Text(entry.label, style = MaterialTheme.typography.bodyMedium)
                            if (entry.session.productivityRating != null || entry.session.confidenceAtEnd != null) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    entry.session.productivityRating?.let {
                                        Text("Productivity $it/5", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    entry.session.confidenceAtEnd?.let {
                                        Text("Confidence $it%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (filtered.isEmpty() && !state.isLoading) {
                item { Text("No sessions logged yet.", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

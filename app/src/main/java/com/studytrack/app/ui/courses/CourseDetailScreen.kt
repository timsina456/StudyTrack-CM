package com.studytrack.app.ui.courses

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.studytrack.app.data.local.CourseEntity
import com.studytrack.app.data.local.LearningOutcomeEntity
import com.studytrack.app.data.local.LosStatus
import com.studytrack.app.data.local.ModuleEntity
import com.studytrack.app.data.local.TopicEntity
import com.studytrack.app.data.repository.HierarchyRepository
import com.studytrack.app.data.repository.PlanningRepository
import com.studytrack.app.data.repository.SessionRepository
import com.studytrack.app.ui.theme.Brick
import com.studytrack.app.ui.theme.Teal
import com.studytrack.app.util.formatMinutes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class TopicWithLos(val topic: TopicEntity, val los: List<LearningOutcomeEntity>)
data class ModuleWithTopics(val module: ModuleEntity, val topics: List<TopicWithLos>)

data class CourseDetailUiState(
    val course: CourseEntity? = null,
    val modules: List<ModuleWithTopics> = emptyList(),
    val isLoading: Boolean = true
)

class CourseDetailViewModel(
    private val hierarchyRepository: HierarchyRepository,
    private val sessionRepository: SessionRepository,
    private val planningRepository: PlanningRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CourseDetailUiState())
    val uiState: StateFlow<CourseDetailUiState> = _uiState.asStateFlow()

    private var currentCourseId: Long? = null

    fun load(courseId: Long) {
        if (currentCourseId == courseId) return
        currentCourseId = courseId
        refresh()
    }

    fun refresh() {
        val courseId = currentCourseId ?: return
        viewModelScope.launch {
            val course = hierarchyRepository.getCourseOnce(courseId)
            val modules = hierarchyRepository.observeModules(courseId).first()
            val moduleWithTopics = modules.map { m ->
                val topics = hierarchyRepository.observeTopics(m.id).first()
                val topicsWithLos = topics.map { t -> TopicWithLos(t, hierarchyRepository.observeLos(t.id).first()) }
                ModuleWithTopics(m, topicsWithLos)
            }
            _uiState.value = CourseDetailUiState(course, moduleWithTopics, false)
        }
    }

    fun startLos(courseId: Long, moduleId: Long, topicId: Long, losId: Long) {
        viewModelScope.launch { sessionRepository.start(courseId, moduleId, topicId, losId) }
    }

    fun markComplete(losId: Long) {
        viewModelScope.launch {
            planningRepository.completeLosAndScheduleFirstRevision(losId)
            refresh()
        }
    }

    fun markDifficult(losId: Long) {
        viewModelScope.launch {
            hierarchyRepository.setDifficulty(losId, 4)
            refresh()
        }
    }

    fun updateConfidence(losId: Long, confidence: Int) {
        viewModelScope.launch {
            hierarchyRepository.updateConfidence(losId, confidence)
            refresh()
        }
    }
}

@Composable
fun CourseDetailScreen(courseId: Long, viewModel: CourseDetailViewModel) {
    LaunchedEffect(courseId) { viewModel.load(courseId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var expandedModules by remember { mutableStateOf(setOf<Long>()) }
    var selectedLos by remember { mutableStateOf<LearningOutcomeEntity?>(null) }
    var selectedContext by remember { mutableStateOf<Triple<Long, Long, Long>?>(null) } // courseId, moduleId, topicId

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            state.course?.name ?: "",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(16.dp)
        )

        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.modules) { moduleGroup ->
                val expanded = moduleGroup.module.id in expandedModules
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expandedModules = if (expanded) expandedModules - moduleGroup.module.id else expandedModules + moduleGroup.module.id
                            }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(moduleGroup.module.name, style = MaterialTheme.typography.titleMedium)
                        Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                    }

                    if (expanded) {
                        moduleGroup.topics.forEach { topicGroup ->
                            Text(
                                topicGroup.topic.topicNumber?.let { "Reading $it \u2014 ${topicGroup.topic.name}" } ?: topicGroup.topic.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                            topicGroup.los.forEach { los ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedLos = los
                                            selectedContext = Triple(state.course!!.id, moduleGroup.module.id, topicGroup.topic.id)
                                        }
                                        .padding(vertical = 6.dp, horizontal = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            if (los.status == LosStatus.COMPLETED) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                            contentDescription = null,
                                            tint = if (los.status == LosStatus.COMPLETED) Teal else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.width(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("${los.code}  ${los.title}", style = MaterialTheme.typography.bodyMedium)
                                    }
                                    los.confidence?.let { Text("$it%", style = MaterialTheme.typography.bodyMedium) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val los = selectedLos
    val context = selectedContext
    if (los != null && context != null) {
        LosDetailDialog(
            los = los,
            onDismiss = { selectedLos = null },
            onStart = {
                viewModel.startLos(context.first, context.second, context.third, los.id)
                selectedLos = null
            },
            onMarkComplete = { viewModel.markComplete(los.id); selectedLos = null },
            onMarkDifficult = { viewModel.markDifficult(los.id); selectedLos = null },
            onConfidenceChange = { viewModel.updateConfidence(los.id, it) }
        )
    }
}

@Composable
private fun LosDetailDialog(
    los: LearningOutcomeEntity,
    onDismiss: () -> Unit,
    onStart: () -> Unit,
    onMarkComplete: () -> Unit,
    onMarkDifficult: () -> Unit,
    onConfidenceChange: (Int) -> Unit
) {
    var confidence by remember(los.id) { mutableFloatStateOf((los.confidence ?: 0).toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${los.code}  ${los.title}") },
        text = {
            Column {
                Text("Status: ${los.status.name.replace('_', ' ').lowercase()}", style = MaterialTheme.typography.bodyMedium)
                Text("Estimated: ${formatMinutes(los.estimatedMinutes)}", style = MaterialTheme.typography.bodyMedium)
                los.difficulty?.let {
                    Text("Difficulty: $it/5", style = MaterialTheme.typography.bodyMedium, color = Brick)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text("Confidence", style = MaterialTheme.typography.labelSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = confidence,
                        onValueChange = { confidence = it },
                        onValueChangeFinished = { onConfidenceChange(confidence.toInt()) },
                        valueRange = 0f..100f,
                        modifier = Modifier.weight(1f)
                    )
                    Text("${confidence.toInt()}%", modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onStart) { Text("Start") }
                TextButton(onClick = onMarkComplete) { Text("Complete") }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onMarkDifficult) { Text("Difficult") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

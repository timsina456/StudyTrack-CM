package com.studytrack.app.ui.courses

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.studytrack.app.data.local.CourseEntity
import com.studytrack.app.data.local.GoalEntity
import com.studytrack.app.data.local.GoalType
import com.studytrack.app.data.repository.HierarchyRepository
import com.studytrack.app.data.repository.PlanningRepository
import com.studytrack.app.domain.Calculators
import com.studytrack.app.ui.components.LedgerProgressBar
import com.studytrack.app.ui.navigation.Screen
import com.studytrack.app.ui.theme.NumericMedium
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

data class CourseWithProgress(val course: CourseEntity, val progressPercent: Double, val losCount: Int)

data class CoursesUiState(
    val courses: List<CourseWithProgress> = emptyList(),
    val isLoading: Boolean = true,
    val importResultMessage: String? = null
)

class CoursesViewModel(
    private val hierarchyRepository: HierarchyRepository,
    private val planningRepository: PlanningRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CoursesUiState())
    val uiState: StateFlow<CoursesUiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val courses = hierarchyRepository.getAllCoursesOnce()
            val withProgress = courses.map { course ->
                val modules = hierarchyRepository.observeModules(course.id).first()
                val allLos = modules.flatMap { m ->
                    hierarchyRepository.observeTopics(m.id).first().flatMap { t -> hierarchyRepository.observeLos(t.id).first() }
                }
                CourseWithProgress(course, Calculators.courseProgressPercent(allLos), allLos.size)
            }
            _uiState.value = _uiState.value.copy(courses = withProgress, isLoading = false)
        }
    }

    fun addCourse(name: String, courseType: String, targetDateMillis: Long?) {
        viewModelScope.launch {
            val id = hierarchyRepository.addCourse(
                CourseEntity(name = name, courseType = courseType, targetDate = targetDateMillis, startDate = System.currentTimeMillis())
            )
            if (targetDateMillis != null) {
                planningRepository.addGoal(
                    GoalEntity(courseId = id, title = "$name complete", goalType = GoalType.COURSE_COMPLETION, targetDate = targetDateMillis, priority = 2)
                )
            }
            refresh()
        }
    }

    fun importCsv(content: String) {
        viewModelScope.launch {
            val result = hierarchyRepository.importCsv(content)
            val message = buildString {
                append("Imported: ${result.modulesCreated} subjects, ${result.topicsCreated} readings, ")
                append("${result.losCreated} new LOS, ${result.losUpdated} updated")
                if (result.rowsSkipped > 0) append(", ${result.rowsSkipped} rows skipped")
                append(".")
            }
            _uiState.value = _uiState.value.copy(importResultMessage = message)
            refresh()
        }
    }

    fun clearImportMessage() {
        _uiState.value = _uiState.value.copy(importResultMessage = null)
    }
}

@Composable
fun CoursesScreen(viewModel: CoursesViewModel, navController: NavHostController) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { picked ->
            val content = runCatching {
                context.contentResolver.openInputStream(picked)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (content != null) viewModel.importCsv(content)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Courses", style = MaterialTheme.typography.headlineMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { importLauncher.launch("*/*") }) { Text("Import CSV") }
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add course")
                }
            }
        }

        state.importResultMessage?.let { message ->
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.clearImportMessage() }) { Text("OK") }
                }
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(state.courses) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { navController.navigate(Screen.CourseDetail.path(item.course.id)) }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(item.course.name, style = MaterialTheme.typography.titleMedium)
                            Text("${item.progressPercent.toInt()}%", style = NumericMedium)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LedgerProgressBar(progress = (item.progressPercent / 100.0).toFloat())
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "${item.losCount} LOS \u00b7 ${item.course.courseType}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddCourseDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, type, targetDate ->
                viewModel.addCourse(name, type, targetDate)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun AddCourseDialog(onDismiss: () -> Unit, onConfirm: (String, String, Long?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("Certification") }
    var targetDateText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New course") },
        text = {
            Column {
                TextField(value = name, onValueChange = { name = it }, placeholder = { Text("Course name") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                TextField(value = type, onValueChange = { type = it }, placeholder = { Text("Type, e.g. Certification") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = targetDateText,
                    onValueChange = { targetDateText = it },
                    placeholder = { Text("Target date YYYY-MM-DD (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) {
                    val millis = runCatching {
                        LocalDate.parse(targetDateText.trim()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }.getOrNull()
                    onConfirm(name.trim(), type.ifBlank { "Certification" }, millis)
                }
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

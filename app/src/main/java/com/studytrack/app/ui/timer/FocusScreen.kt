package com.studytrack.app.ui.timer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.studytrack.app.data.local.ActiveSessionEntity
import com.studytrack.app.data.local.SessionStatus
import com.studytrack.app.data.repository.HierarchyRepository
import com.studytrack.app.data.repository.PlanningRepository
import com.studytrack.app.data.repository.SessionRepository
import com.studytrack.app.ui.components.FinishSessionDialog
import com.studytrack.app.ui.theme.NumericLarge
import com.studytrack.app.util.formatClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class TimerUiState(
    val label: String = "",
    val elapsedMillis: Long = 0,
    val status: SessionStatus? = null,
    val hasActiveSession: Boolean = false
)

/**
 * Note: the PlanningRepository dependency is unused today (Phase 1's
 * Focus screen doesn't schedule revisions directly) but is kept so
 * Phase 2 can add "mark difficult" / "schedule revision" here too
 * without changing the ViewModel's construction site in AppContainer.
 */
class TimerViewModel(
    private val sessionRepository: SessionRepository,
    private val hierarchyRepository: HierarchyRepository,
    @Suppress("unused") private val planningRepository: PlanningRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TimerUiState())
    val uiState: StateFlow<TimerUiState> = _uiState.asStateFlow()

    private var tickerJob: Job? = null

    init {
        viewModelScope.launch {
            sessionRepository.observeActiveSession().collectLatest { active ->
                tickerJob?.cancel()
                update(active)
                if (active != null && active.status == SessionStatus.RUNNING) {
                    tickerJob = viewModelScope.launch {
                        while (true) {
                            delay(1000)
                            update(active)
                        }
                    }
                }
            }
        }
    }

    private suspend fun update(active: ActiveSessionEntity?) {
        if (active == null) {
            _uiState.value = TimerUiState(hasActiveSession = false)
            return
        }
        val course = hierarchyRepository.getCourseOnce(active.courseId)
        val module = hierarchyRepository.getModuleOnce(active.moduleId)
        val topic = active.topicId?.let { hierarchyRepository.getTopicOnce(it) }
        val los = active.losId?.let { hierarchyRepository.getLosOnce(it) }
        val label = listOfNotNull(
            course?.name, module?.name, topic?.name, los?.let { "${it.code} \u2013 ${it.title}" }
        ).joinToString("\n")
        _uiState.value = TimerUiState(label, active.elapsedMillis(), active.status, true)
    }

    fun pause() = viewModelScope.launch { sessionRepository.pause() }
    fun resume() = viewModelScope.launch { sessionRepository.resume() }

    fun finish(rating: Int, confidence: Int, notes: String?, onDone: () -> Unit) {
        viewModelScope.launch {
            sessionRepository.finish(rating, confidence, notes)
            onDone()
        }
    }
}

@Composable
fun FocusScreen(viewModel: TimerViewModel, onDone: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showFinishDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (!state.hasActiveSession) {
            Text("Nothing running right now.", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(onClick = onDone) { Text("Back") }
            return@Column
        }

        Text(
            state.label,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        Text(formatClock(state.elapsedMillis), style = NumericLarge.copy(fontSize = 56.sp))
        Spacer(modifier = Modifier.height(40.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = {
                if (state.status == SessionStatus.RUNNING) viewModel.pause() else viewModel.resume()
            }) {
                Text(if (state.status == SessionStatus.RUNNING) "Pause" else "Resume")
            }
            Button(onClick = { showFinishDialog = true }) { Text("Finish") }
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = onDone) { Text("Switch topic") }
    }

    if (showFinishDialog) {
        FinishSessionDialog(
            onDismiss = { showFinishDialog = false },
            onConfirm = { rating, confidence, notes ->
                viewModel.finish(rating, confidence, notes, onDone)
                showFinishDialog = false
            }
        )
    }
}

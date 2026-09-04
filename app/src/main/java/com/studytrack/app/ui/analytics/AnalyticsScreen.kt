package com.studytrack.app.ui.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.studytrack.app.data.repository.HierarchyRepository
import com.studytrack.app.data.repository.PlanningRepository
import com.studytrack.app.data.repository.SessionRepository
import com.studytrack.app.domain.Calculators
import com.studytrack.app.domain.WeakArea
import com.studytrack.app.ui.components.SectionHeader
import com.studytrack.app.ui.components.StatCard
import com.studytrack.app.ui.navigation.Screen
import com.studytrack.app.util.formatMinutes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class ModuleTimeShare(val moduleName: String, val minutes: Int, val percentOfTotal: Double, val completionPercent: Double)

data class AnalyticsUiState(
    val timeAllocation: List<ModuleTimeShare> = emptyList(),
    val weakAreas: List<WeakArea> = emptyList(),
    val totalMinutesAllTime: Int = 0,
    val averageSessionMinutes: Int = 0,
    val isLoading: Boolean = true
)

/**
 * PlanningRepository isn't used by Phase 1's analytics yet (reserved for
 * a Phase 2 "goal achievement rate" panel) but is injected now so the
 * ViewModel's constructor doesn't need to change later.
 */
class AnalyticsViewModel(
    private val hierarchyRepository: HierarchyRepository,
    private val sessionRepository: SessionRepository,
    @Suppress("unused") private val planningRepository: PlanningRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnalyticsUiState())
    val uiState: StateFlow<AnalyticsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val modules = hierarchyRepository.getAllModulesOnce()
            val topics = hierarchyRepository.getAllTopicsOnce()
            val allLos = hierarchyRepository.getAllLosOnce()
            val losByModule = allLos.groupBy { los -> topics.find { it.id == los.topicId }?.moduleId }
                .filterKeys { it != null }.mapKeys { it.key!! }
            val minutesByModule = modules.associate { it.id to sessionRepository.totalMinutesForModule(it.id) }
            val totalMinutes = minutesByModule.values.sum()
            val safeTotal = totalMinutes.coerceAtLeast(1)

            val allocation = modules.map { m ->
                val minutes = minutesByModule[m.id] ?: 0
                val los = losByModule[m.id].orEmpty()
                ModuleTimeShare(m.name, minutes, minutes * 100.0 / safeTotal, Calculators.courseProgressPercent(los))
            }.sortedByDescending { it.minutes }

            val weak = Calculators.findWeakAreas(modules, losByModule, minutesByModule)

            val allSessions = sessionRepository.observeHistory().first()
            val avgSession = if (allSessions.isNotEmpty()) allSessions.sumOf { it.durationMinutes } / allSessions.size else 0

            _uiState.value = AnalyticsUiState(allocation, weak, totalMinutes, avgSession, false)
        }
    }
}

@Composable
fun AnalyticsScreen(viewModel: AnalyticsViewModel, navController: NavHostController) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { Text("Analytics", style = MaterialTheme.typography.headlineMedium) }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(label = "Total studied", value = formatMinutes(state.totalMinutesAllTime), modifier = Modifier.weight(1f))
                StatCard(label = "Avg session", value = formatMinutes(state.averageSessionMinutes), modifier = Modifier.weight(1f))
            }
        }

        if (state.timeAllocation.isNotEmpty()) {
            item { SectionHeader("Time allocation") }
            items(state.timeAllocation) { share ->
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(share.moduleName, style = MaterialTheme.typography.bodyMedium)
                        Text("${share.percentOfTotal.toInt()}%", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth((share.percentOfTotal / 100.0).toFloat().coerceIn(0f, 1f))
                                .height(6.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "${formatMinutes(share.minutes)} \u00b7 ${share.completionPercent.toInt()}% complete",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (state.weakAreas.isNotEmpty()) {
            item { SectionHeader("Weakest areas") }
            items(state.weakAreas) { weak ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(weak.module.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            weak.averageConfidence?.let { "${it.toInt()}% confidence" } ?: "No confidence data",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            OutlinedButton(onClick = { navController.navigate(Screen.History.route) }, modifier = Modifier.fillMaxWidth()) {
                Text("View full history")
            }
        }
    }
}

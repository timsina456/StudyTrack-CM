package com.studytrack.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.studytrack.app.data.repository.AppSettings
import com.studytrack.app.data.repository.AppThemeMode
import com.studytrack.app.data.repository.BackupRepository
import com.studytrack.app.data.repository.SettingsRepository
import com.studytrack.app.ui.components.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val backupRepository: BackupRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    fun setDailyTarget(minutes: Int) = viewModelScope.launch { settingsRepository.setDailyTarget(minutes) }
    fun setWeeklyTarget(minutes: Int) = viewModelScope.launch { settingsRepository.setWeeklyTarget(minutes) }
    fun setThemeMode(mode: AppThemeMode) = viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    fun setGamification(enabled: Boolean) = viewModelScope.launch { settingsRepository.setGamificationEnabled(enabled) }

    suspend fun exportNow(): String = backupRepository.exportAll()

    fun importNow(json: String) {
        viewModelScope.launch {
            runCatching { backupRepository.importAll(json) }
                .onSuccess { _statusMessage.value = "Backup restored." }
                .onFailure { _statusMessage.value = "Import failed \u2014 make sure the file is a StudyTrack backup." }
        }
    }

    fun clearStatus() {
        _statusMessage.value = null
    }
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var dailyTargetText by remember(settings.dailyTargetMinutes) { mutableStateOf((settings.dailyTargetMinutes / 60.0).toString()) }
    var weeklyTargetText by remember(settings.weeklyTargetMinutes) { mutableStateOf((settings.weeklyTargetMinutes / 60.0).toString()) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri?.let { target ->
            scope.launch {
                val json = viewModel.exportNow()
                runCatching {
                    context.contentResolver.openOutputStream(target)?.use { it.write(json.toByteArray()) }
                }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { source ->
            val text = runCatching {
                context.contentResolver.openInputStream(source)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text != null) viewModel.importNow(text)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item { Text("More", style = MaterialTheme.typography.headlineMedium) }

        item { Spacer(modifier = Modifier.height(16.dp)) }
        item { SectionHeader("Study targets") }
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(
                    value = dailyTargetText,
                    onValueChange = { dailyTargetText = it },
                    label = { Text("Daily hours") },
                    modifier = Modifier.weight(1f)
                )
                TextField(
                    value = weeklyTargetText,
                    onValueChange = { weeklyTargetText = it },
                    label = { Text("Weekly hours") },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            TextButton(onClick = {
                dailyTargetText.toDoubleOrNull()?.let { viewModel.setDailyTarget((it * 60).toInt()) }
                weeklyTargetText.toDoubleOrNull()?.let { viewModel.setWeeklyTarget((it * 60).toInt()) }
            }) { Text("Save targets") }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
        item { SectionHeader("Theme") }
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(AppThemeMode.LIGHT, AppThemeMode.DARK, AppThemeMode.SYSTEM).forEach { mode ->
                    val selected = settings.themeMode == mode
                    OutlinedButton(onClick = { viewModel.setThemeMode(mode) }) {
                        Text(mode.name.lowercase().replaceFirstChar { it.uppercase() } + if (selected) " \u2713" else "")
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
        item { SectionHeader("Gamification") }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Streaks & milestone badges", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = settings.gamificationEnabled, onCheckedChange = { viewModel.setGamification(it) })
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
        item { SectionHeader("Backup") }
        item {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    "Everything stays on this device. Export makes a JSON copy you control; import replaces all current data.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { exportLauncher.launch("studytrack_backup_${LocalDate.now()}.json") }) {
                        Text("Export backup")
                    }
                    OutlinedButton(onClick = { importLauncher.launch("application/json") }) {
                        Text("Import backup")
                    }
                }
                statusMessage?.let { message ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { viewModel.clearStatus() }) { Text("OK") }
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
        item {
            Text(
                "StudyTrack \u2014 Phase 1 (offline-first)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

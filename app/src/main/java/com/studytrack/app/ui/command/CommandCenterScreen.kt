package com.studytrack.app.ui.command

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.studytrack.app.domain.ClarificationOption
import com.studytrack.app.domain.CommandExecutor
import com.studytrack.app.domain.CommandIntent
import com.studytrack.app.domain.CommandParser
import com.studytrack.app.domain.ParsedCommand
import com.studytrack.app.ui.components.CommandBar
import com.studytrack.app.ui.navigation.PendingCommandBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CommandHistoryEntry(val id: Long, val youSaid: String, val response: String, val isError: Boolean = false)

private val IMPORTANT_INTENTS = setOf(
    CommandIntent.START, CommandIntent.COMPLETE_LOS, CommandIntent.SCHEDULE_REVISION, CommandIntent.MARK_DIFFICULT
)

class CommandCenterViewModel(
    private val commandParser: CommandParser,
    private val commandExecutor: CommandExecutor
) : ViewModel() {

    private val _history = MutableStateFlow<List<CommandHistoryEntry>>(emptyList())
    val history: StateFlow<List<CommandHistoryEntry>> = _history.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<ParsedCommand.Resolved?>(null)
    val pendingConfirmation: StateFlow<ParsedCommand.Resolved?> = _pendingConfirmation.asStateFlow()

    private val _clarification = MutableStateFlow<ParsedCommand.NeedsClarification?>(null)
    val clarification: StateFlow<ParsedCommand.NeedsClarification?> = _clarification.asStateFlow()

    private var nextId = 0L

    init {
        PendingCommandBridge.pendingText?.let { text ->
            PendingCommandBridge.pendingText = null
            _inputText.value = text
            submit()
        }
    }

    fun onInputChange(text: String) {
        _inputText.value = text
    }

    fun onVoiceResult(text: String) {
        _inputText.value = text
        submit()
    }

    fun submit() {
        val text = _inputText.value.trim()
        if (text.isBlank()) return
        _inputText.value = ""
        viewModelScope.launch {
            when (val parsed = commandParser.parse(text)) {
                is ParsedCommand.Unrecognized ->
                    appendHistory(text, "I didn't catch that. Try \"help\" for examples.", isError = true)
                is ParsedCommand.NeedsClarification -> _clarification.value = parsed
                is ParsedCommand.Resolved -> {
                    if (parsed.intent in IMPORTANT_INTENTS) {
                        _pendingConfirmation.value = parsed
                    } else {
                        runResolved(parsed)
                    }
                }
            }
        }
    }

    private suspend fun runResolved(command: ParsedCommand.Resolved) {
        val result = commandExecutor.execute(command)
        appendHistory(command.rawText, result)
    }

    fun confirmPending() {
        val pending = _pendingConfirmation.value ?: return
        _pendingConfirmation.value = null
        viewModelScope.launch { runResolved(pending) }
    }

    fun cancelPending() {
        val pending = _pendingConfirmation.value
        _pendingConfirmation.value = null
        pending?.let { appendHistory(it.rawText, "Cancelled.") }
    }

    fun selectClarification(option: ClarificationOption) {
        val clar = _clarification.value ?: return
        _clarification.value = null
        val resolved = ParsedCommand.Resolved(clar.intent, option.entity, rawText = clar.rawText)
        if (resolved.intent in IMPORTANT_INTENTS) {
            _pendingConfirmation.value = resolved
        } else {
            viewModelScope.launch { runResolved(resolved) }
        }
    }

    fun dismissClarification() {
        val clar = _clarification.value
        _clarification.value = null
        clar?.let { appendHistory(it.rawText, "Okay, cancelled.") }
    }

    private fun appendHistory(youSaid: String, response: String, isError: Boolean = false) {
        _history.value = _history.value + CommandHistoryEntry(nextId++, youSaid, response, isError)
    }
}

@Composable
fun CommandCenterScreen(viewModel: CommandCenterViewModel) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val inputText by viewModel.inputText.collectAsStateWithLifecycle()
    val pending by viewModel.pendingConfirmation.collectAsStateWithLifecycle()
    val clarification by viewModel.clarification.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Command Center",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(16.dp)
        )

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (history.isEmpty()) {
                item {
                    Text(
                        CommandExecutor.HELP_TEXT,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(history) { entry ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text("\u201c${entry.youSaid}\u201d", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            entry.response,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (entry.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        clarification?.let { clar ->
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(clar.question, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    clar.options.forEach { option ->
                        OutlinedButton(
                            onClick = { viewModel.selectClarification(option) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) { Text(option.label) }
                    }
                    OutlinedButton(onClick = { viewModel.dismissClarification() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Cancel")
                    }
                }
            }
        }

        pending?.let { command ->
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("You said:", style = MaterialTheme.typography.labelSmall)
                    Text("\u201c${command.rawText}\u201d", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Interpreted as:", style = MaterialTheme.typography.labelSmall)
                    Text(
                        command.entity.label().ifBlank { command.intent.name },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { viewModel.confirmPending() }) { Text("Start") }
                        OutlinedButton(onClick = { viewModel.cancelPending() }) { Text("Change") }
                    }
                }
            }
        }

        CommandBar(
            text = inputText,
            onTextChange = viewModel::onInputChange,
            onSubmit = viewModel::submit,
            onVoiceResult = viewModel::onVoiceResult,
            modifier = Modifier.padding(16.dp)
        )
    }
}

package com.conza.outpost.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.conza.outpost.ai.Generation
import com.conza.outpost.ai.Readiness
import com.conza.outpost.ai.Source
import com.conza.outpost.ai.Sources
import com.conza.outpost.data.ChatMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(
    onOpenSource: (Source) -> Unit,
    onGetModels: () -> Unit,
    onSettings: () -> Unit,
    vm: AssistantViewModel = viewModel(),
) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val sid by vm.sessionId.collectAsStateWithLifecycle()
    val gen by vm.generation.collectAsStateWithLifecycle()
    val readiness by vm.readiness.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val useSources by vm.useSources.collectAsStateWithLifecycle()
    val index by vm.indexStatus.collectAsStateWithLifecycle()
    val crashNote by vm.crashNote.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val busy = gen != null && gen?.phase != "Done"

    // Re-check models when returning from Content or Settings.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.refresh() } }
    LaunchedEffect(messages.size, gen?.text?.length) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.size - 1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(Modifier.clickable(enabled = models.size > 1) { modelMenu = true }) {
                        Text(sessions.firstOrNull { it.id == sid }?.title ?: "Assistant", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (val r = readiness) {
                                    is Readiness.Local -> "${r.modelTitle} · on this phone"
                                    is Readiness.Remote -> "${r.model} · ${r.url.removePrefix("http://").removePrefix("https://")}"
                                    else -> "No model"
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                            if (models.size > 1) Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(16.dp))
                        }
                        DropdownMenu(modelMenu, { modelMenu = false }) {
                            models.forEach { m -> DropdownMenuItem(text = { Text(m.title) }, onClick = { vm.pickModel(m.resourceId); modelMenu = false }) }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showHistory = true }) { Icon(Icons.Filled.History, "Chats") }
                    IconButton(onClick = vm::newChat) { Icon(Icons.Filled.Add, "New chat") }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Assistant settings") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            crashNote?.let { note ->
                Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = { vm.crashNote.value = null }) { Text("Dismiss") }
                    }
                }
            }
            if (readiness == null) Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Starting the AI engine…")
            }
            when (val r = readiness) {
                Readiness.NeedsModel -> SetupCard(
                    "Pick a model to get started",
                    "The assistant runs entirely on this phone. Download a model once (0.5–2.5 GB) and it works with no signal. " +
                        "Qwen3 1.7B is a good first choice. Or connect to an Ollama server on your network.",
                    onGetModels, onSettings,
                )
                is Readiness.Unsupported -> SetupCard("On-device AI isn't available", r.reason + " You can still connect to an Ollama or LM Studio server on your network.", null, onSettings)
                else -> Unit
            }

            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (messages.isEmpty() && readiness !is Readiness.NeedsModel) item { Intro(onPick = { input = it }) }
                items(messages, key = { it.id }) { m -> MessageBubble(m, gen?.takeIf { it.messageId == m.id }, onOpenSource) }
            }

            if (index.running && index.pending > 0) {
                Text("Indexing notes for search… ${index.pending} passages left", style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(useSources, { vm.setUseSources(!useSources) }, label = { Text("Use my library & notes") })
                Spacer(Modifier.weight(1f))
                gen?.tokensPerSecond?.let { Text("%.1f tok/s".format(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask anything…") },
                    maxLines = 5,
                    enabled = readiness is Readiness.Local || readiness is Readiness.Remote,
                )
                Spacer(Modifier.width(8.dp))
                if (busy) FilledIconButton(onClick = vm::stop) { Icon(Icons.Filled.Stop, "Stop") }
                else FilledIconButton(onClick = { vm.send(input); input = "" }, enabled = input.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            }
        }
    }

    if (showHistory) AlertDialog(
        onDismissRequest = { showHistory = false },
        title = { Text("Chats") },
        text = {
            LazyColumn(Modifier.fillMaxWidth()) {
                if (sessions.isEmpty()) item { Text("No chats yet.") }
                items(sessions, key = { it.id }) { s ->
                    Row(Modifier.fillMaxWidth().clickable { vm.open(s.id); showHistory = false }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(s.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontWeight = if (s.id == sid) FontWeight.Bold else FontWeight.Normal)
                        IconButton(onClick = { vm.delete(s.id) }) { Icon(Icons.Filled.Delete, "Delete chat") }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Close") } },
    )
}

@Composable
private fun SetupCard(title: String, body: String, onGetModels: (() -> Unit)?, onSettings: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onGetModels?.let { Button(onClick = it) { Text("Get a model") } }
                OutlinedButton(onClick = onSettings) { Text("Use a server") }
            }
        }
    }
}

@Composable
private fun Intro(onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Ask questions offline", style = MaterialTheme.typography.titleMedium)
        Text("Answers draw on your downloaded libraries (Wikipedia, medical guides…), notes, and documents, with numbered sources you can open.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf("How do I treat a second-degree burn?", "How can I purify water without a filter?", "What are the signs of hypothermia?")
            .forEach { AssistChip(onClick = { onPick(it) }, label = { Text(it) }) }
    }
}

@Composable
private fun MessageBubble(m: ChatMessage, live: Generation?, onOpenSource: (Source) -> Unit) {
    val mine = m.role == "user"
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 560.dp)
                .background(
                    if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(16.dp),
                )
                .padding(12.dp),
        ) {
            val text = if (live != null && m.content.isBlank()) live.text else m.content
            if (text.isBlank() && live != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(live.phase, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
            }
            val sources = remember(m.sourcesJson) { Sources.decode(m.sourcesJson) }
            if (sources.isNotEmpty()) {
                Spacer(Modifier.size(6.dp))
                Text("Sources", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                sources.forEachIndexed { i, s ->
                    Text(
                        "[${i + 1}] ${s.title}" + (if (s.subtitle.isNotBlank()) " · ${s.subtitle}" else ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { onOpenSource(s) }.padding(vertical = 3.dp),
                    )
                }
            }
        }
    }
}

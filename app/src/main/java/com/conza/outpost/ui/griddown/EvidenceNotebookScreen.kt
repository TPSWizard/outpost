package com.conza.outpost.ui.griddown

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.conza.outpost.container
import com.conza.outpost.data.Note
import com.conza.outpost.ui.common.BackTopBar
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** A local, source-conscious decision log for civilian safety, saved in ordinary Notes. */
@Composable
fun EvidenceNotebookScreen(onBack: () -> Unit, onOpenNote: (Long) -> Unit) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val notes by c.db.notes().observeAll().collectAsState(initial = emptyList())
    var claim by rememberSaveable { mutableStateOf("") }
    var observed by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf("") }
    var alternatives by rememberSaveable { mutableStateOf("") }
    var confidence by rememberSaveable { mutableStateOf("") }
    var actionThreshold by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { BackTopBar("Decision notebook", onBack) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Record a claim separately from direct observations. Check sources and alternative explanations before acting. Immediate danger comes first.",
                    style = MaterialTheme.typography.bodyMedium)
            }
            item { Text("Saved locally in Notes and included in your backups. Avoid recording sensitive personal information.", style = MaterialTheme.typography.bodySmall) }
            saveError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            item { OutlinedTextField(claim, { claim = it }, label = { Text("Claim or decision to check") }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(observed, { observed = it }, label = { Text("What I directly observed, with time and place") }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(source, { source = it }, label = { Text("Source, date, and how it was checked") }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(alternatives, { alternatives = it }, label = { Text("Other explanations") }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(confidence, { confidence = it }, label = { Text("Confidence and what remains unknown") }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(actionThreshold, { actionThreshold = it }, label = { Text("Protective action and what would change the plan") }, modifier = Modifier.fillMaxWidth()) }
            item {
                Button(enabled = claim.isNotBlank() && !saving, onClick = {
                    saving = true
                    scope.launch {
                        try {
                            saveError = null
                            val now = System.currentTimeMillis()
                            val note = Note(
                                title = "Decision check: ${claim.trim().take(70)}",
                                body = buildString {
                                    append("Recorded: ").append(DateFormat.getDateTimeInstance().format(Date(now))).append("\n\n")
                                    append("Claim: ").append(claim.trim()).append("\n\n")
                                    append("Direct observations: ").append(observed.trim()).append("\n\n")
                                    append("Source and date: ").append(source.trim()).append("\n\n")
                                    append("Alternative explanations: ").append(alternatives.trim()).append("\n\n")
                                    append("Confidence and unknowns: ").append(confidence.trim()).append("\n\n")
                                    append("Protective action and reconsideration trigger: ").append(actionThreshold.trim())
                                },
                                createdAt = now,
                                updatedAt = now,
                            )
                            val id = c.db.notes().upsert(note)
                            c.index.indexNote(note.copy(id = id))
                            claim = ""; observed = ""; source = ""; alternatives = ""; confidence = ""; actionThreshold = ""
                        } catch (e: Exception) {
                            saveError = "Could not save: ${e.message ?: "unknown error"}"
                        } finally { saving = false }
                    }
                }) { Text(if (saving) "Saving…" else "Save decision check") }
            }
            val saved = notes.filter { it.title.startsWith("Decision check: ") }
            if (saved.isNotEmpty()) item { Text("Saved checks", style = MaterialTheme.typography.titleMedium) }
            items(saved, key = { it.id }) { note ->
                Card(Modifier.fillMaxWidth().clickable { onOpenNote(note.id) }) {
                    Column(Modifier.padding(14.dp)) {
                        Text(note.title, style = MaterialTheme.typography.titleSmall)
                        Text(DateFormat.getDateTimeInstance().format(Date(note.updatedAt)), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

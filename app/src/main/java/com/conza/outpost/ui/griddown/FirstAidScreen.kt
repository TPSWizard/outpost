package com.conza.outpost.ui.griddown

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.conza.outpost.container
import com.conza.outpost.griddown.FaSource
import com.conza.outpost.griddown.FaTopic
import com.conza.outpost.ui.common.BackTopBar
import kotlinx.coroutines.launch

/** Opens a cited manual page, or explains that the manual needs downloading first. */
@Composable
fun rememberManualOpener(openPdf: (path: String, page: Int) -> Unit, getManuals: () -> Unit): (FaSource) -> Unit {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var missing by remember { mutableStateOf<String?>(null) }
    missing?.let { title ->
        AlertDialog(
            onDismissRequest = { missing = null },
            title = { Text("Manual not downloaded") },
            text = { Text("\"$title\" isn't on this phone yet. Download it from Content → Field manuals → Casualty care before the outage. The steps here work without it.") },
            confirmButton = { TextButton(onClick = { missing = null; getManuals() }) { Text("Field manuals") } },
            dismissButton = { TextButton(onClick = { missing = null }) { Text("Close") } },
        )
    }
    return { src ->
        scope.launch {
            val f = c.gridDown.manualFile(src.manual)
            if (f != null) openPdf(f.absolutePath, src.page) else missing = c.gridDown.firstAid.manuals[src.manual] ?: src.manual
        }
    }
}

@Composable
fun FirstAidScreen(onBack: () -> Unit, openPdf: (String, Int) -> Unit, getManuals: () -> Unit, initialTopic: String? = null) {
    val c = LocalContext.current.container
    val file = c.gridDown.firstAid
    var topicId by rememberSaveable { mutableStateOf(initialTopic) }
    val topic = file.topics.firstOrNull { it.id == topicId }
    val opener = rememberManualOpener(openPdf, getManuals)

    if (topic != null) TopicFlow(topic, file.manuals, onBack = { if (initialTopic != null) onBack() else topicId = null }, openSource = opener)
    else Scaffold(topBar = { BackTopBar("First aid", onBack, subtitle = "Step-by-step, from Army medical manuals") }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { ClinicianNote() }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text("Life-threatening bleeding comes first. If someone is bleeding heavily, start with Bleeding.",
                        Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold)
                }
            }
            items(file.topics, key = { it.id }) { t ->
                Card(Modifier.fillMaxWidth().clickable { topicId = t.id }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(t.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(t.summary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun TopicFlow(topic: FaTopic, manuals: Map<String, String>, onBack: () -> Unit, openSource: (FaSource) -> Unit) {
    val path = remember(topic.id) { mutableStateListOf(topic.start) }
    val node = topic.node(path.last())
    val back = { if (path.size > 1) path.removeAt(path.lastIndex) else onBack() }
    Scaffold(topBar = { BackTopBar(topic.title, { back() }, subtitle = "Step ${path.size}") }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { ClinicianNote() }
            if (node == null) { item { Text("This checklist is incomplete.") }; return@LazyColumn }
            if (node.question != null) {
                item { Text(node.question, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { path.add(node.yes!!) }, Modifier.weight(1f).height(64.dp)) { Text("Yes", style = MaterialTheme.typography.titleLarge) }
                        OutlinedButton(onClick = { path.add(node.no!!) }, Modifier.weight(1f).height(64.dp)) { Text("No", style = MaterialTheme.typography.titleLarge) }
                    }
                }
            } else {
                if (node.title.isNotBlank()) item { Text(node.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
                itemsIndexed(node.steps) { i, s ->
                    Card(colors = CardDefaults.cardColors(containerColor = if (s.urgent) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${i + 1}. ${s.text}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                color = if (s.urgent) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface)
                            s.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                            s.source?.let { src ->
                                TextButton(onClick = { openSource(src) }, contentPadding = ButtonDefaults.TextButtonContentPadding) {
                                    Icon(Icons.AutoMirrored.Filled.MenuBook, null)
                                    Text("  ${manuals[src.manual]?.substringBefore(" (") ?: src.manual}, p. ${src.page} · open manual at this page",
                                        style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
                item {
                    if (node.next != null) Button(onClick = { path.add(node.next) }, Modifier.fillMaxWidth().height(56.dp)) { Text("Next") }
                    else OutlinedButton(onClick = onBack, Modifier.fillMaxWidth().height(56.dp)) { Text("Done") }
                }
            }
        }
    }
}

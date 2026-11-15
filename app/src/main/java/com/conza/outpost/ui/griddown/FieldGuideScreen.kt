package com.conza.outpost.ui.griddown

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.conza.outpost.griddown.FieldGuide
import com.conza.outpost.ui.common.BackTopBar

@Composable
fun FieldGuideScreen(onBack: () -> Unit, onFirstAid: () -> Unit, onFoodSafety: () -> Unit,
                     onDecisionNotebook: () -> Unit, initialCardId: String? = null) {
    val context = LocalContext.current
    val guide = remember { context.assets.open("griddown/field-guide.json").bufferedReader().use { FieldGuide.parse(it.readText()) } }
    var selected by remember(initialCardId) { mutableStateOf(initialCardId) }
    var query by remember { mutableStateOf("") }
    val card = guide.cards.firstOrNull { it.id == selected }

    Scaffold(topBar = { BackTopBar(card?.title ?: "Offline field guide", { if (card != null) selected = null else onBack() }) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (card == null) {
                item { Text(guide.notice, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search field cards") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                val shown = guide.cards.filter { query.isBlank() ||
                    listOf(it.title, it.summary, it.section, it.steps.joinToString(" ")).any { s -> s.contains(query, ignoreCase = true) } }
                items(shown, key = { it.id }) { item ->
                    Card(Modifier.fillMaxWidth().clickable { selected = item.id }) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(item.section, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(item.summary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                item { Text(card.summary, style = MaterialTheme.typography.titleMedium) }
                item { Text("Use when: ${card.useWhen}", style = MaterialTheme.typography.bodyMedium) }
                item { Text("Have ready: ${card.equipment.joinToString()}", style = MaterialTheme.typography.bodyMedium) }
                items(card.steps) { step ->
                    Text("• $step", style = MaterialTheme.typography.bodyLarge)
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Important", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                            Text(card.caution)
                        }
                    }
                }
                item { Text("Get help: ${card.getHelpWhen}", style = MaterialTheme.typography.bodyMedium) }
                if (card.id == "first-aid") item { TextButton(onClick = onFirstAid) { Text("Open first aid checklists") } }
                if (card.id == "food-storage") item { TextButton(onClick = onFoodSafety) { Text("Open food safety calculator") } }
                if (card.id == "information-triage") item { TextButton(onClick = onDecisionNotebook) { Text("Open decision notebook") } }
                item { Text("Sources · reviewed ${guide.version}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
                items(card.sources) { source ->
                    Column {
                        Text(source.title, style = MaterialTheme.typography.bodyMedium)
                        Text(source.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.url))) } }) { Text("Open source when online") }
                    }
                }
            }
        }
    }
}

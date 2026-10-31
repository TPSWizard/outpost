package com.conza.outpost.ui.griddown

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.conza.outpost.container
import com.conza.outpost.data.RadioLogEntry
import com.conza.outpost.ui.common.BackTopBar
import com.conza.outpost.ui.common.Notice
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.DateFormat
import java.util.Date

@Serializable data class RadioChannel(val ch: String, val mhz: String, val use: String = "")
@Serializable data class RadioGroup(val name: String, val license: String, val channels: List<RadioChannel>)
@Serializable data class ScheduleItem(val time: String, val channel: String, val text: String)
@Serializable data class RadioFile(val version: String, val note: String, val groups: List<RadioGroup>, val schedule: List<ScheduleItem>)

@Composable
fun RadioScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val ref = remember { Json { ignoreUnknownKeys = true }.decodeFromString(RadioFile.serializer(), ctx.assets.open("griddown/radio.json").bufferedReader().use { it.readText() }) }
    val log by c.db.gridDown().observeRadio().collectAsState(initial = emptyList())
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var edit by remember { mutableStateOf<RadioLogEntry?>(null) }

    edit?.let { e ->
        var channel by remember { mutableStateOf(e.channel) }
        var station by remember { mutableStateOf(e.station) }
        var text by remember { mutableStateOf(e.text) }
        var kind by remember { mutableStateOf(e.kind) }
        AlertDialog(
            onDismissRequest = { edit = null },
            title = { Text(if (e.kind == "schedule") "Listening slot" else "Log entry") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (e.kind != "schedule") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(kind == "heard", { kind = "heard" }, label = { Text("Heard") })
                        FilterChip(kind == "sent", { kind = "sent" }, label = { Text("Sent") })
                    }
                    OutlinedTextField(channel, { channel = it }, label = { Text("Channel / frequency") }, singleLine = true)
                    OutlinedTextField(station, { station = it }, label = { Text(if (e.kind == "schedule") "When" else "Station / call sign") }, singleLine = true)
                    OutlinedTextField(text, { text = it }, label = { Text(if (e.kind == "schedule") "What to listen for" else "Message") })
                }
            },
            confirmButton = { TextButton(enabled = channel.isNotBlank(), onClick = {
                scope.launch { c.db.gridDown().upsertRadio(e.copy(channel = channel.trim(), station = station.trim(), text = text.trim(), kind = kind)) }; edit = null
            }) { Text("Save") } },
            dismissButton = {
                Row {
                    if (e.id != 0L) TextButton(onClick = { scope.launch { c.db.gridDown().deleteRadio(e.id) }; edit = null }) { Text("Delete") }
                    TextButton(onClick = { edit = null }) { Text("Cancel") }
                }
            },
        )
    }

    Scaffold(
        topBar = { BackTopBar("Radio", onBack, subtitle = "Frequencies, log, and listening schedule") },
        floatingActionButton = {
            if (tab > 0) ExtendedFloatingActionButton(onClick = { edit = RadioLogEntry(channel = "", kind = if (tab == 1) "heard" else "schedule") }) {
                Icon(Icons.Filled.Add, null); Text(if (tab == 1) "Log" else "Add slot")
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) { listOf("Channels", "Log", "Schedule").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) } }
            LazyColumn(contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (tab) {
                    0 -> {
                        item { Notice(ref.note) }
                        items(ref.groups, key = { it.name }) { g ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(g.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                                    Text(g.license, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    g.channels.forEach { ch ->
                                        Row(Modifier.padding(top = 4.dp)) {
                                            Text(ch.ch, Modifier.weight(0.28f), fontWeight = FontWeight.Medium)
                                            Text(ch.mhz.ifBlank { "—" } + if (ch.mhz.isNotBlank()) " MHz" else "", Modifier.weight(0.32f), fontFamily = FontFamily.Monospace)
                                            Text(ch.use, Modifier.weight(0.4f), style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        val entries = log.filter { it.kind != "schedule" }
                        if (entries.isEmpty()) item { Text("Log what you hear and send: time, channel, who, and what. It helps your group spot patterns and confirm messages.") }
                        items(entries, key = { it.id }) { e ->
                            Column(Modifier.fillMaxWidth().clickable { edit = e }.padding(vertical = 6.dp)) {
                                Text("${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.at))} · ${e.kind} · ${e.channel}" +
                                    if (e.station.isNotBlank()) " · ${e.station}" else "", style = MaterialTheme.typography.labelMedium)
                                if (e.text.isNotBlank()) Text(e.text)
                            }
                            HorizontalDivider()
                        }
                    }
                    else -> {
                        val slots = log.filter { it.kind == "schedule" }
                        if (slots.isEmpty()) item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("A listening schedule saves battery: turn radios on at set times instead of all day.")
                                OutlinedButton(onClick = {
                                    scope.launch { ref.schedule.forEach { s -> c.db.gridDown().upsertRadio(RadioLogEntry(channel = s.channel, kind = "schedule", station = s.time, text = s.text)) } }
                                }) { Text("Start from the template") }
                            }
                        }
                        items(slots, key = { it.id }) { e ->
                            Card(Modifier.fillMaxWidth().clickable { edit = e }) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(e.station, fontWeight = FontWeight.SemiBold)
                                    Text(e.channel, style = MaterialTheme.typography.bodyMedium)
                                    if (e.text.isNotBlank()) Text(e.text, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

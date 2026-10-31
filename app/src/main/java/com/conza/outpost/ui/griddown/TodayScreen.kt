package com.conza.outpost.ui.griddown

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.conza.outpost.container
import com.conza.outpost.data.ChecklistDone
import com.conza.outpost.griddown.Checklists
import com.conza.outpost.griddown.Outage
import com.conza.outpost.griddown.PowerMonitor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.LocalDate

data class GridNav(
    val firstAid: () -> Unit,
    val supplies: () -> Unit,
    val household: () -> Unit,
    val calculators: () -> Unit,
    val weather: () -> Unit,
    val power: () -> Unit,
    val radio: () -> Unit,
    val mesh: () -> Unit,
    val print: () -> Unit,
    val beforeOutage: () -> Unit,
    val back: () -> Unit,
    val drugSearch: (String) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(nav: GridNav) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    var startDay by remember { mutableStateOf(c.settings.outageStartEpochDay) }
    val today = LocalDate.now()
    val day = startDay?.let { Outage.day(it, today.toEpochDay()) }
    val snapshot by remember { c.gridDown.observe() }.collectAsState(initial = null)
    var estimate by remember { mutableStateOf<PowerMonitor.Estimate?>(null) }
    var survival by remember { mutableStateOf(c.settings.survivalMode) }
    var confirmEnd by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) { estimate = PowerMonitor.estimate(ctx); delay(60_000) }
    }

    val phase = day?.let { Outage.phase(it) }
    val doneFlow = remember(day) {
        if (day == null) flowOf(emptySet())
        else {
            val dao = c.db.gridDown()
            combine(dao.observeDone(day), dao.observeDone(Outage.phase(day).days.first)) { a, b -> (a + b).map { it.taskId to it.day }.toSet() }
        }
    }
    val done by doneFlow.collectAsState(initial = emptySet())

    if (confirmEnd) AlertDialog(
        onDismissRequest = { confirmEnd = false },
        title = { Text("End the outage?") },
        text = { Text("The day counter stops. Your checklist history, supplies, and household stay.") },
        confirmButton = { TextButton(onClick = { c.settings.outageStartEpochDay = null; startDay = null; confirmEnd = false }) { Text("End outage") } },
        dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Cancel") } },
    )

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (day != null) "Day $day of the outage" else "Today") },
            actions = {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                    Text("Survival", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(6.dp))
                    Switch(checked = survival, onCheckedChange = { v ->
                        survival = v; c.settings.survivalMode = v
                        scope.launch { if (v) PowerMonitor.enterSurvival(ctx) else PowerMonitor.exitSurvival(ctx) }
                    })
                }
            },
        )
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "battery") {
                Card(Modifier.fillMaxWidth().clickable(onClick = nav.power), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Battery", style = MaterialTheme.typography.labelMedium)
                        Text(estimate?.text ?: "Reading battery…", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(if (survival) "Survival mode on: red screen, 60 Hz, background work paused." else "Tap for the power budget. Survival mode stretches the battery.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (day == null) item(key = "start") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No outage running", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("When the power goes out, start the day counter. You'll get a checklist for each day, tuned to your household.", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { c.settings.outageStartEpochDay = today.toEpochDay(); startDay = today.toEpochDay() }) { Text("Outage started today") }
                            OutlinedButton(onClick = { val d = today.minusDays(2).toEpochDay(); c.settings.outageStartEpochDay = d; startDay = d }) { Text("3 days ago") }
                        }
                        OutlinedButton(onClick = nav.beforeOutage, modifier = Modifier.fillMaxWidth()) { Text("Before the outage: what to download & set up") }
                    }
                }
            } else item(key = "phase") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Phase ${phase!!.number}: ${phase.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Days ${phase.days.first}–${phase.days.last}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { confirmEnd = true }) { Text("End") }
                }
            }
            snapshot?.let { s ->
                val warns = s.warnings
                if (warns.isNotEmpty()) item(key = "warn") { WarningCard(warns, "Running low (under ${com.conza.outpost.griddown.Rationing.WARN_DAYS} days)") }
                if (s.members.isEmpty()) item(key = "setup") {
                    Card(Modifier.fillMaxWidth().clickable(onClick = nav.household)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Set up your household", fontWeight = FontWeight.SemiBold)
                            Text("Add people (ages), pets, medications, and climate. Water, food, and checklists use them.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                val known = s.statuses.filter { it.daysLeft != null }
                if (known.isNotEmpty()) item(key = "supplies") {
                    Card(Modifier.fillMaxWidth().clickable(onClick = nav.supplies)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Supplies", fontWeight = FontWeight.SemiBold)
                            known.forEach { st ->
                                Row { Text(st.category.label, Modifier.weight(1f)); Text("≈ %.0f days".format(st.daysLeft), fontWeight = FontWeight.Medium,
                                    color = if (st.warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
                            }
                        }
                    }
                }
                if (day != null) {
                    val tasks = Checklists.forDay(c.gridDown.checklists, day, s.traits())
                    Checklists.CATEGORIES.forEach { (cat, label) ->
                        val list = tasks.filter { it.category == cat }
                        if (list.isEmpty()) return@forEach
                        item(key = "h-$cat") {
                            val n = list.count { (it.id to Checklists.doneKey(it, day)) in done }
                            SectionTitle("$label · $n/${list.size}")
                        }
                        list.forEach { t ->
                            item(key = "t-${t.id}") {
                                val key = Checklists.doneKey(t, day)
                                val checked = (t.id to key) in done
                                var open by remember { mutableStateOf(false) }
                                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked, onCheckedChange = { v ->
                                        scope.launch { if (v) c.db.gridDown().markDone(ChecklistDone(t.id, key)) else c.db.gridDown().unmark(t.id, key) }
                                    })
                                    Column(Modifier.weight(1f)) {
                                        Text(t.text, style = MaterialTheme.typography.bodyLarge)
                                        if (open && t.detail.isNotBlank()) Text(t.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (t.frequency == "once") Text("Once this phase", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item(key = "tools-h") { SectionTitle("Tools") }
            item(key = "tools") {
                val tiles = listOf(
                    Triple("First aid", Icons.Filled.Healing, nav.firstAid),
                    Triple("Supplies", Icons.Filled.Inventory2, nav.supplies),
                    Triple("Water & food", Icons.Filled.Calculate, nav.calculators),
                    Triple("Household", Icons.Filled.Group, nav.household),
                    Triple("Power", Icons.Filled.BatteryChargingFull, nav.power),
                    Triple("Weather & sky", Icons.Filled.Cloud, nav.weather),
                    Triple("Radio", Icons.Filled.Radio, nav.radio),
                    Triple("Share nearby", Icons.Filled.Share, nav.mesh),
                    Triple("Print cards", Icons.Filled.Print, nav.print),
                    Triple("Before the outage", Icons.AutoMirrored.Filled.FactCheck, nav.beforeOutage),
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tiles.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (t, i, on) -> Tile(t, "", i, Modifier.weight(1f), on) }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

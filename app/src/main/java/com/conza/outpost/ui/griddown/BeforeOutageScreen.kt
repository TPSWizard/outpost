package com.conza.outpost.ui.griddown

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.conza.outpost.catalog.ResourceKind
import com.conza.outpost.container
import com.conza.outpost.griddown.Backup
import com.conza.outpost.ui.common.BackTopBar
import kotlinx.coroutines.launch
import java.time.LocalDate

data class PrepStep(val title: String, val body: String, val needsInternet: Boolean, val done: Boolean?, val action: String, val onClick: () -> Unit)

data class BeforeNav(
    val maps: () -> Unit, val reference: () -> Unit, val models: () -> Unit, val manuals: () -> Unit,
    val medical: () -> Unit, val household: () -> Unit, val supplies: () -> Unit, val print: () -> Unit,
    val backup: () -> Unit, val mesh: () -> Unit, val radio: () -> Unit, val benchmark: () -> Unit, val settings: () -> Unit,
)

/** The things that only work while there's still internet, plus setup that makes the offline tools useful. */
@Composable
fun BeforeOutageScreen(onBack: () -> Unit, nav: BeforeNav) {
    val c = LocalContext.current.container
    val steps by produceState<List<PrepStep>?>(null) {
        val inst = c.db.installed()
        val maps = inst.byKind(ResourceKind.MAP.name).size
        val zims = inst.byKind(ResourceKind.ZIM.name)
        val models = inst.byKind(ResourceKind.MODEL.name)
        val docs = inst.byKind(ResourceKind.DOC.name).map { it.resourceId }.toSet()
        val snap = c.gridDown.snapshot()
        val plan = c.db.household().plan()
        val casualty = listOf("atp-4-02-11", "fm-4-25-11", "fm-21-10")
        val essentials = listOf("usda-home-canning", "ncvec-technician-pool")
        value = listOf(
            PrepStep("Offline maps", "Your region plus about 100 miles around it. Pins, rally points, and the printed map card use it." +
                if (maps > 0) " ($maps installed)" else "", true, maps > 0, "Maps", nav.maps),
            PrepStep("Offline libraries (Kiwix)", "Recommended: a medical library (WikiMed or MedlinePlus), Wikipedia (no pictures to save space), " +
                "and in Reference: Water Treatment (zimgit-water), Post Disaster (zimgit-post-disaster), Food Preparation, Knots, Medicine (zimgit-medicine), " +
                "iFixit repair guides, Gardening and Home Improvement (DIY) Stack Exchange, and the CD3WD appropriate-technology library." + if (zims.isNotEmpty()) " (${zims.size} installed)" else "",
                true, zims.isNotEmpty(), "Reference", nav.reference),
            PrepStep("Casualty care manuals", "ATP 4-02.11, FM 4-25.11, and FM 21-10. First-aid checklists open these at the cited page.",
                true, casualty.all { it in docs }, "Manuals", nav.manuals),
            PrepStep("Grid-down essentials", "USDA home canning guide, ham radio license study pools, and the other public-domain guides in Field manuals → Grid-down essentials.",
                true, essentials.any { it in docs }, "Manuals", nav.manuals),
            PrepStep("Drug label database", "FDA labels for every medication your household uses (Medical → Drugs → Download).", true, c.drugs.installed, "Medical", nav.medical),
            PrepStep("AI model", "Qwen3 1.7B (or Llama 3.2 3B on a Pixel 8) plus the Nomic search model. Then run the Benchmark unplugged to measure battery per answer.",
                true, models.isNotEmpty(), "AI models", nav.models),
            PrepStep("Measure AI battery cost", "Benchmark, unplugged, with a model installed.", false, c.settings.aiCostMeasured, "Benchmark", nav.benchmark),
            PrepStep("Household", "People with birth years, pets, medications (with doses on hand), climate, altitude, home location.",
                false, snap.members.isNotEmpty() && c.settings.homeLat != null, "Household", nav.household),
            PrepStep("Contacts and rally points", "An out-of-area contact and two meeting places.", false, plan.any { it.type == "rally" }, "Household", nav.household),
            PrepStep("Count your supplies", "Water, food (with calories), fuel, batteries, medicines. Days remaining appear on Today.", false, snap.items.isNotEmpty(), "Supplies", nav.supplies),
            PrepStep("Print the cards", "Family plan, medication schedule, water & first-aid quick reference, neighborhood map.", false, null, "Print", nav.print),
            PrepStep("Back up to an SD card or USB drive", "Keep a copy off the phone.", false, null, "Backup", nav.backup),
            PrepStep("Test phone-to-phone sharing", "Open Share nearby on two family phones and send a pin.", false, null, "Share", nav.mesh),
            PrepStep("Program your radios", "Agree on a channel plan and check-in times with family and neighbors.", false, null, "Radio", nav.radio),
            PrepStep("Store content on the SD card", "Settings → Storage. Keeps phone memory free for the AI model.", false, null, "Settings", nav.settings),
        )
    }

    Scaffold(topBar = { BackTopBar("Before the outage", onBack, subtitle = "Do the cloud items while you still have internet") }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Text("After setup, nothing in Outpost needs the internet. Items marked \"needs internet\" download content now so it's on the phone later.",
                        Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            val list = steps
            if (list == null) item { CircularProgressIndicator() }
            else items(list, key = { it.title }) { s ->
                Card(Modifier.fillMaxWidth().clickable(onClick = s.onClick)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (s.done == true) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                            tint = if (s.done == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(s.title, fontWeight = FontWeight.SemiBold)
                                if (s.needsInternet) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(Icons.Filled.CloudDownload, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
                                    Text(" needs internet", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                                }
                            }
                            Text(s.body, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(s.action, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/** Backup & restore to SD/USB through the system file picker, and a check of every downloaded file. */
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var checks by remember { mutableStateOf<List<Backup.Check>?>(null) }
    var checking by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch { message = runCatching { Backup.export(ctx, uri) }.fold({ "Backup saved ($it files)." }, { "Backup failed: ${it.message}" }) }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> confirmRestore = uri }
    LaunchedEffect(Unit) { checking = true; checks = Backup.verify(ctx); checking = false }

    confirmRestore?.let { uri ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Restore this backup?") },
            text = { Text("Your household, supplies, notes, chats, pins, checklists, radio log, and settings will be replaced by the backup's. Outpost restarts afterwards.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = {
                confirmRestore = null
                scope.launch { runCatching { Backup.restore(ctx, uri) }.onFailure { message = "Restore failed: ${it.message}" } }
            }) { Text("Restore") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmRestore = null }) { Text("Cancel") } },
        )
    }

    Scaffold(topBar = { BackTopBar("Backup & verify", onBack) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Everything you typed goes into one .zip: household, supplies, notes, chats, pins, checklists, radio log, settings. Pick an SD card or USB drive in the file picker. Downloads (maps, libraries, models) aren't included; copy those folders as files.",
                style = MaterialTheme.typography.bodySmall) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { exporter.launch("outpost-backup-${LocalDate.now()}.zip") }) { Text("Back up") }
                    OutlinedButton(onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Restore…") }
                }
            }
            message?.let { m -> item { Text(m, fontWeight = FontWeight.Medium) } }
            item { HorizontalDivider() }
            item { SectionTitle("Downloaded content check") }
            if (checking) item { CircularProgressIndicator() }
            checks?.let { list ->
                item { Text(if (list.all { it.ok }) "All ${list.size} downloads look good." else "${list.count { !it.ok }} of ${list.size} need attention.",
                    fontWeight = FontWeight.SemiBold) }
                items(list.sortedBy { it.ok }, key = { it.path }) { ch ->
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(if (ch.ok) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                            tint = if (ch.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(ch.title, style = MaterialTheme.typography.bodyMedium)
                            ch.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
}

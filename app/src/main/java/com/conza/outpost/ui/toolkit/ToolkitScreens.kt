package com.conza.outpost.ui.toolkit

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.conza.outpost.bench.BenchResult
import com.conza.outpost.bench.Benchmark
import com.conza.outpost.container
import com.conza.outpost.ui.common.BackTopBar
import kotlinx.coroutines.launch

private data class Tile(val title: String, val body: String, val icon: ImageVector, val onClick: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolkitScreen(
    onMedical: () -> Unit,
    onManuals: () -> Unit,
    onContent: () -> Unit,
    onBeforeOutage: () -> Unit,
    onBackup: () -> Unit,
    onNotes: () -> Unit,
    onTools: () -> Unit,
    onLearn: () -> Unit,
    onBenchmark: () -> Unit,
    onSettings: () -> Unit,
) {
    val tiles = listOf(
        Tile("Content & downloads", "Maps, offline libraries, Wikipedia, AI models, tools, and field manuals. Download before the outage.", Icons.Filled.CloudDownload, onContent),
        Tile("Before the outage", "Checklist of what to download and set up while you still have internet.", Icons.AutoMirrored.Filled.FactCheck, onBeforeOutage),
        Tile("Medical reference", "FDA drug labels, interaction check, conditions, and home & herbal remedies.", Icons.Filled.MedicalServices, onMedical),
        Tile("Field manuals", "Survival, navigation, casualty care, preparedness, home security, and firearm maintenance.", Icons.AutoMirrored.Filled.MenuBook, onManuals),
        Tile("Notes & documents", "Markdown notes and imported files. The assistant can search them.", Icons.AutoMirrored.Filled.Notes, onNotes),
        Tile("Data tools", "CyberChef and IT-Tools: encoding, encryption, hashing, converters.", Icons.Filled.Build, onTools),
        Tile("Education", "Khan Academy courses through the Kolibri app.", Icons.Filled.School, onLearn),
        Tile("Benchmark", "How fast is this phone, and which AI models suit it?", Icons.Filled.Speed, onBenchmark),
        Tile("Backup & verify", "Back up to an SD card or USB drive; check downloads aren't damaged.", Icons.Filled.Backup, onBackup),
        Tile("Settings & about", "Storage location, downloads, assistant, map, credits.", Icons.Filled.Settings, onSettings),
    )
    Scaffold(topBar = { TopAppBar(title = { Text("Toolkit") }) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(tiles, key = { it.title }) { t ->
                Card(Modifier.fillMaxWidth().clickable(onClick = t.onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(t.icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(t.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(t.body, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

/** NOMAD runs Kolibri as a server; on a phone the official Kolibri app does the job. */
@Composable
fun LearnScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val pkg = "org.learningequality.Kolibri"
    val launch = remember { ctx.packageManager.getLaunchIntentForPackage(pkg) }
    Scaffold(topBar = { BackTopBar("Education", onBack) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Kolibri", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Project NOMAD's education platform is Kolibri by Learning Equality: Khan Academy videos and exercises, " +
                    "CK-12, and more, with progress tracking, all offline. Kolibri has its own Android app that downloads " +
                    "and plays channels without a connection.",
            )
            if (launch != null) Button(onClick = { ctx.startActivity(launch) }) { Text("Open Kolibri") }
            else {
                Button(onClick = { open(ctx, "market://details?id=$pkg", "https://play.google.com/store/apps/details?id=$pkg") }) { Text("Get Kolibri from Google Play") }
                OutlinedButton(onClick = { open(ctx, "https://learningequality.org/download/", null) }) { Text("Other downloads (APK)") }
            }
            HorizontalDivider()
            Text("Also in your Library", style = MaterialTheme.typography.titleMedium)
            Text("Content → Reference has offline textbooks, how-to guides, and educational ZIM collections that open in the Library tab.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun open(ctx: android.content.Context, uri: String, fallback: String?) {
    val ok = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    if (!ok && fallback != null) runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallback)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
fun BenchmarkScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var result by remember { mutableStateOf(BenchResult.fromJson(c.settings.lastBenchmark)) }
    var running by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { BackTopBar("Benchmark", onBack) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Takes about 20 seconds, plus a short AI test if a model is installed. Keep the screen on.", style = MaterialTheme.typography.bodySmall)
            if (running != null) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text(running!!)
            } else Button(onClick = {
                scope.launch {
                    running = "Starting…"
                    result = runCatching { Benchmark.run(ctx) { running = it } }.getOrNull() ?: result
                    running = null
                }
            }) { Text(if (result == null) "Run benchmark" else "Run again") }

            result?.let { r ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Score ${r.score}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(r.verdict)
                    }
                }
                val rows = listOf(
                    "Device" to r.device, "Android" to r.android, "CPU cores" to r.cores.toString(), "RAM" to "${r.ramGb} GB",
                    "CPU single-core" to "%.0f MB/s SHA-256".format(r.cpuSingleMBs), "CPU all cores" to "%.0f MB/s SHA-256".format(r.cpuMultiMBs),
                    "Memory copy" to "%.1f GB/s".format(r.memoryGBs), "Storage write" to "%.0f MB/s".format(r.storageWriteMBs),
                    "Storage read (cached)" to "%.0f MB/s".format(r.storageReadMBs),
                    "AI generation" to (r.llmTokensPerSec?.let { "%.1f tokens/s · ${r.llmModel}".format(it) } ?: "No model installed"),
                    "AI battery per answer" to (r.aiPercentPerAnswer?.let { "%.2f%% %s".format(it, if (r.aiCostMeasured) "(measured)" else "(estimated; unplug to measure)") } ?: "—"),
                )
                rows.forEach { (k, v) ->
                    Row { Text(k, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Text(v, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) }
                    HorizontalDivider()
                }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(rows.joinToString("\n") { "${it.first}: ${it.second}" } + "\nScore: ${r.score}")) }) {
                    Text("Copy results")
                }
            }
        }
    }
}

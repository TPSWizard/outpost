package com.conza.outpost.ui.griddown

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.conza.outpost.container
import com.conza.outpost.griddown.NearbyMesh
import com.conza.outpost.ui.common.BackTopBar
import com.conza.outpost.ui.common.Notice
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun MeshScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val prefs = remember { ctx.getSharedPreferences("settings", 0) }
    var myName by remember { mutableStateOf(prefs.getString("mesh_name", null) ?: Build.MODEL) }
    val perms = remember { NearbyMesh.permissions() }
    fun granted() = perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    var ok by remember { mutableStateOf(granted()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { ok = granted() }
    val messages by c.db.gridDown().observeMesh().collectAsState(initial = emptyList())

    Scaffold(topBar = { BackTopBar("Share nearby", onBack, subtitle = "Phone to phone, no internet or cell service") }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) { listOf("Phones", "Meshtastic", "Messages").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) } }
            if (!ok) Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sharing uses Bluetooth and Wi-Fi Direct to find phones and radios near you. Android asks for permission first (it calls this 'nearby devices' or location).")
                Button(onClick = { ask.launch(perms) }) { Text("Allow") }
            } else when (tab) {
                0 -> NearbyTab(myName) { myName = it; prefs.edit().putString("mesh_name", it).apply() }
                1 -> MeshtasticTab()
                else -> MessagesTab(messages, onSend = { text, viaRadio ->
                    scope.launch { if (viaRadio) c.meshtastic.sendText(text) else c.nearby.sendMessage(myName, text) }
                })
            }
        }
    }
}

@Composable
private fun NearbyTab(myName: String, onName: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val mesh = c.nearby
    val scope = rememberCoroutineScope()
    val peers by mesh.peers.collectAsState()
    val status by mesh.status.collectAsState()
    val incoming by mesh.incoming.collectAsState()
    var supplies by remember { mutableStateOf(mesh.mergeSupplies) }
    var pickFile by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { mesh.start(myName); onDispose { mesh.stop() } }

    incoming?.let { inc ->
        AlertDialog(
            onDismissRequest = { mesh.incoming.value = null },
            title = { Text("From ${inc.name}") },
            text = { Text("Add ${inc.plan.summary} to this phone?") },
            confirmButton = { TextButton(onClick = { scope.launch { mesh.applyIncoming() } }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { mesh.incoming.value = null }) { Text("Ignore") } },
        )
    }
    if (pickFile) {
        val files = remember {
            c.storage.allRoots().flatMap { r -> listOf("books", "maps", "shared", "printouts").map { File(r, it) } }
                .flatMap { d -> d.listFiles { f -> f.isFile && !f.name.endsWith(".part") }.orEmpty().toList() }.sortedBy { it.name }
        }
        AlertDialog(
            onDismissRequest = { pickFile = false },
            title = { Text("Send a file") },
            text = {
                LazyColumn {
                    if (files.isEmpty()) item { Text("No PDFs, maps, or printouts yet.") }
                    items(files, key = { it.absolutePath }) { f ->
                        TextButton(onClick = { mesh.sendFile(f); pickFile = false }) { Text("${f.name} · ${f.length() shr 20} MB") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickFile = false }) { Text("Close") } },
        )
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { OutlinedTextField(myName, onName, label = { Text("This phone's name (others see it)") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item { Text(status, style = MaterialTheme.typography.bodySmall) }
        item { Notice("Both phones need this screen open. Check that the 4-character code matches on both before accepting.") }
        items(peers.values.toList(), key = { it.id }) { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(p.name, fontWeight = FontWeight.SemiBold)
                    Text(p.state + (p.token?.let { " · code $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (p.state) {
                            "Found", "Disconnected", "Not connected" -> Button(onClick = { mesh.connect(p.id, myName) }) { Text("Connect") }
                            "Confirm code" -> { Button(onClick = { mesh.accept(p.id) }) { Text("Codes match: accept") }; OutlinedButton(onClick = { mesh.reject(p.id) }) { Text("Reject") } }
                            else -> {}
                        }
                    }
                }
            }
        }
        if (peers.values.any { it.state == "Connected" }) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(supplies, { supplies = it; mesh.mergeSupplies = it })
                    Text("Family phone: share and merge supplies too", style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { mesh.sendSync(myName, supplies) } }) { Text("Send notes, pins & messages") }
                    OutlinedButton(onClick = { pickFile = true }) { Text("Send a file") }
                }
            }
        }
    }
}

@Composable
private fun MeshtasticTab() {
    val c = LocalContext.current.container
    val link = c.meshtastic
    val status by link.status.collectAsState()
    val found by link.found.collectAsState()
    val nodes by link.nodes.collectAsState()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Notice("Experimental. Meshtastic LoRa radios relay short texts for miles with no infrastructure. Pair the radio in Android Bluetooth settings first. Messages go out on the primary channel; positions show up as blue pins on the Map.") }
        item { Text(status, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { link.scan() }) { Text("Find radios") }
                OutlinedButton(onClick = { link.disconnect() }) { Text("Disconnect") }
            }
        }
        items(found, key = { it.address }) { r ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(r.name, fontWeight = FontWeight.SemiBold); Text(r.address, style = MaterialTheme.typography.labelSmall) }
                    Button(onClick = { link.connect(r.address) }) { Text("Connect") }
                }
            }
        }
        if (nodes.isNotEmpty()) {
            item { SectionTitle("Nodes heard (${nodes.size})") }
            items(nodes.entries.toList(), key = { it.key }) { (_, name) -> Text(name) }
        }
    }
}

@Composable
private fun MessagesTab(messages: List<com.conza.outpost.data.MeshMessage>, onSend: (String, Boolean) -> Unit) {
    var text by remember { mutableStateOf("") }
    var radio by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (messages.isEmpty()) item { Text("No messages yet. Messages you send reach every connected phone (or the LoRa mesh).") }
            items(messages, key = { it.id }) { m ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.outgoing) Alignment.End else Alignment.Start) {
                    Text("${m.from} · ${m.transport} · ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.at))}", style = MaterialTheme.typography.labelSmall)
                    Card { Text(m.text, Modifier.padding(10.dp)) }
                }
            }
        }
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(radio, { radio = it }); Text("Send over Meshtastic radio", style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Message") }, maxLines = 3)
            Spacer(Modifier.width(8.dp))
            Button(enabled = text.isNotBlank(), onClick = { onSend(text.trim(), radio); text = "" }) { Text("Send") }
        }
    }
}

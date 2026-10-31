package com.conza.outpost.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.conza.outpost.ai.RemoteLlm
import com.conza.outpost.container
import com.conza.outpost.data.InstalledResource
import com.conza.outpost.ui.common.BackTopBar
import com.conza.outpost.ui.content.formatBytes
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val s = c.settings
    val scope = rememberCoroutineScope()

    var wifiOnly by remember { mutableStateOf(s.wifiOnly) }
    var survival by remember { mutableStateOf(s.survivalMode) }
    var largeText by remember { mutableStateOf(s.largeText) }
    var mapCompat by remember { mutableStateOf(s.mapCompatibilityMode) }
    var volume by remember { mutableIntStateOf(c.storage.selectedVolume) }
    val volumes = remember { c.storage.volumes() }

    var backend by remember { mutableStateOf(s.aiBackend) }
    var chatModel by remember { mutableStateOf(s.chatModelId) }
    var url by remember { mutableStateOf(s.remoteUrl) }
    var api by remember { mutableStateOf(s.remoteApi) }
    var model by remember { mutableStateOf(s.remoteModel) }
    var key by remember { mutableStateOf(s.remoteApiKey) }
    var ctxSize by remember { mutableIntStateOf(s.contextSize) }
    var temp by remember { mutableFloatStateOf(s.temperature) }
    var test by remember { mutableStateOf<String?>(null) }
    val models by produceState<List<InstalledResource>>(emptyList()) { value = c.assistant.installedChatModels() }
    val llmOk by produceState<Boolean?>(null) { value = c.llm.checkAvailable() }
    val engineInfo by produceState("") { value = c.llm.systemInfo() }

    fun saveRemote() { s.remoteUrl = url; s.remoteApi = api; s.remoteModel = model; s.remoteApiKey = key }

    Scaffold(topBar = { BackTopBar("Settings & about", { saveRemote(); onBack() }) }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Heading("Display & power")
            Toggle("Large text", "Bigger type for reading in poor light or one-handed.", largeText) { largeText = it; s.largeText = it }
            Toggle("Survival mode", "Red-on-black, lowest refresh rate, background work paused, AI unloaded when idle. Details in Today → Power.", survival) {
                survival = it; s.survivalMode = it
                scope.launch { if (it) com.conza.outpost.griddown.PowerMonitor.enterSurvival(ctx) else com.conza.outpost.griddown.PowerMonitor.exitSurvival(ctx) }
            }
            Text("Privacy: everything stays on this phone or your SD card. No accounts, analytics, or telemetry. The internet is used only for downloads you start.",
                style = MaterialTheme.typography.bodySmall)

            Heading("Downloads")
            Toggle("Download on Wi-Fi only", "Applies to new downloads. Maps, libraries, and models can be several GB.", wifiOnly) { wifiOnly = it; s.wifiOnly = it }

            Heading("Storage")
            Text("Where new downloads go. Content already on another volume keeps working.", style = MaterialTheme.typography.bodySmall)
            volumes.forEach { v ->
                Row(Modifier.fillMaxWidth().clickable { volume = v.index; c.storage.selectedVolume = v.index }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(volume == v.index, { volume = v.index; c.storage.selectedVolume = v.index })
                    Column {
                        Text(v.label)
                        Text("${formatBytes(v.freeBytes)} free of ${formatBytes(v.totalBytes)} · ${v.root.absolutePath}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Text("Files use Project NOMAD's names, so you can copy .zim and .pmtiles files from a NOMAD server into the zim/ and maps/ folders here.",
                style = MaterialTheme.typography.labelSmall)

            Heading("Assistant")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(backend == "local", { backend = "local"; s.aiBackend = "local" }, label = { Text("On this phone") })
                FilterChip(backend == "remote", { backend = "remote"; s.aiBackend = "remote" }, label = { Text("Server on my network") })
            }
            if (backend == "local") {
                if (llmOk == false) {
                    Text(c.llm.unavailableReason.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { c.llm.resetCrashState(); test = "Restart Outpost to try on-device AI again." }) { Text("Retry on-device AI") }
                }
                if (models.isEmpty()) Text("No chat models installed yet. Get one from Content → AI models.", style = MaterialTheme.typography.bodySmall)
                models.forEach { m ->
                    Row(Modifier.fillMaxWidth().clickable { chatModel = m.resourceId; s.chatModelId = m.resourceId }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(chatModel == m.resourceId || (chatModel == null && m == models.first()), { chatModel = m.resourceId; s.chatModelId = m.resourceId })
                        Text("${m.title} · ${formatBytes(m.sizeBytes)}")
                    }
                }
                Text("Context window (memory for conversation + sources)", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2048, 4096, 8192).forEach { n ->
                        FilterChip(ctxSize == n, { ctxSize = n; s.contextSize = n }, label = { Text("${n / 1024}K") })
                    }
                }
                Text("Larger windows fit more sources but use more RAM. 4K suits most phones.", style = MaterialTheme.typography.labelSmall)
            } else {
                Text("Works with Ollama (start it with OLLAMA_HOST=0.0.0.0) or any OpenAI-compatible server such as LM Studio.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(api == "ollama", { api = "ollama"; s.remoteApi = "ollama" }, label = { Text("Ollama") })
                    FilterChip(api == "openai", { api = "openai"; s.remoteApi = "openai" }, label = { Text("OpenAI-compatible") })
                }
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Server URL") }, singleLine = true,
                    placeholder = { Text(if (api == "ollama") "http://192.168.1.10:11434" else "http://192.168.1.10:1234") })
                OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Model name") }, singleLine = true)
                OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("API key (optional)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        saveRemote()
                        test = "Connecting…"
                        scope.launch {
                            test = runCatching { RemoteLlm(c.http).listModels(api, s.remoteUrl, key) }.fold(
                                { list -> if (list.isEmpty()) "Connected, but the server has no models." else "Connected. Models: " + list.take(8).joinToString() },
                                { "Couldn't connect: ${it.message}" },
                            )
                        }
                    }) { Text("Test connection") }
                }
                test?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Text("Creativity: %.1f".format(temp), style = MaterialTheme.typography.bodyMedium)
            Slider(temp, { temp = it }, valueRange = 0f..1.2f, steps = 11, onValueChangeFinished = { s.temperature = temp })
            Text("Lower is more factual and repeatable; higher is more varied.", style = MaterialTheme.typography.labelSmall)

            Heading("Map")
            Toggle("Map compatibility mode", "Recommended for emulators like BlueStacks. Turn off on a real phone for smoother maps.", mapCompat) {
                mapCompat = it; s.mapCompatibilityMode = it
            }

            Heading("About")
            Text("Outpost brings Project NOMAD's offline knowledge server to a phone: offline Wikipedia and reference libraries, " +
                "maps, a medical reference, notes, tools, and an AI assistant, all without a connection.", style = MaterialTheme.typography.bodySmall)
            Text(
                "Credits: content catalogs, regional maps, and medical datasets from Project NOMAD by Crosstalk Solutions (Apache-2.0 / MIT). " +
                    "Map data © OpenStreetMap contributors (ODbL) via Protomaps; MapLibre Native (BSD-2). Library reader: libzim/Kiwix (GPLv3). " +
                    "On-device AI: llama.cpp (MIT); models from their respective publishers under their own licenses. " +
                    "Drug labels: openFDA (public domain). Remedies: CDC and NIH NCCIH (public domain). Fonts: Noto Sans (OFL). " +
                    "Tools: CyberChef (Apache-2.0), IT-Tools (GPL-3.0), downloaded separately.",
                style = MaterialTheme.typography.labelSmall,
            )
            Text("Outpost is an independent project, not affiliated with or endorsed by Project NOMAD or Crosstalk Solutions. " +
                "Because it includes GPLv3 components, Outpost as distributed is GPLv3.", style = MaterialTheme.typography.labelSmall)
            val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() }
            Text("Version $version", style = MaterialTheme.typography.labelSmall)
            Text("AI engine: " + engineInfo.take(300), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Heading(t: String) {
    HorizontalDivider()
    Text(t, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Toggle(title: String, body: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
        Switch(value, onChange)
    }
}

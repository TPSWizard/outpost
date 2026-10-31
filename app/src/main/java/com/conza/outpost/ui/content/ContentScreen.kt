package com.conza.outpost.ui.content

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.conza.outpost.catalog.CatalogGroup
import com.conza.outpost.catalog.CatalogItem
import com.conza.outpost.catalog.CatalogSection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentScreen(initialTab: Int = 0, vm: ContentViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    var pendingDelete by remember { mutableStateOf<CatalogItem?>(null) }

    // Android 13+: ask once for notifications so download progress is visible.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val requestNotifs = {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val actions = ItemActions(
        download = { requestNotifs(); vm.download(it) },
        downloadAll = { requestNotifs(); vm.downloadAll(it) },
        cancel = vm::cancel,
        delete = { pendingDelete = it },
    )

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Content") },
                actions = {
                    if (state.refreshing) CircularProgressIndicator(Modifier.size(24.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = vm::refreshCatalog) { Icon(Icons.Filled.Refresh, "Refresh catalog") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            StorageBar(state.storage)
            PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                listOf("Maps", "Reference", "Wikipedia", "AI models", "Tools", "Field manuals").forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
                }
            }
            val catalog = state.catalog
            if (catalog == null) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                }
                return@Column
            }
            val sections: List<CatalogSection> = when (tab) {
                0 -> catalog.maps
                1 -> catalog.library
                2 -> listOfNotNull(catalog.wikipedia?.let { CatalogSection("Wikipedia", listOf(it)) })
                3 -> catalog.models
                4 -> catalog.tools
                else -> catalog.manuals
            }
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                sections.forEach { section ->
                    if (sections.size > 1) item(key = "h:${section.title}") {
                        Text(
                            section.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                        )
                    }
                    items(section.groups, key = { it.key }) { group ->
                        GroupCard(group, state, actions, initiallyExpanded = group.pickOne || tab in 3..4 || group.recommended)
                    }
                }
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${item.title}?") },
            text = { Text("Frees about ${formatMb(item.sizeMb)}. You can download it again later.") },
            confirmButton = { TextButton(onClick = { vm.delete(item); pendingDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Keep") } },
        )
    }
}

private data class ItemActions(
    val download: (CatalogItem) -> Unit,
    val downloadAll: (List<CatalogItem>) -> Unit,
    val cancel: (CatalogItem) -> Unit,
    val delete: (CatalogItem) -> Unit,
)

@Composable
private fun StorageBar(s: StorageInfo) {
    if (s.totalBytes <= 0) return
    val used = 1f - s.freeBytes.toFloat() / s.totalBytes
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        LinearProgressIndicator(progress = { used }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.size(4.dp))
        Text(
            "${formatBytes(s.contentBytes)} of content · ${formatBytes(s.freeBytes)} free",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GroupCard(group: CatalogGroup, state: ContentUiState, actions: ItemActions, initiallyExpanded: Boolean) {
    var expanded by rememberSaveable(group.key) { mutableStateOf(initiallyExpanded) }
    val statuses = group.items.map { state.statusOf(it) }
    val installedCount = statuses.count { it is ItemStatus.Installed || it is ItemStatus.UpdateAvailable }
    val totalMb = group.items.sumOf { it.sizeMb }
    val missingMb = group.items.zip(statuses)
        .filter { (_, s) -> s is ItemStatus.Available || s is ItemStatus.Failed || s is ItemStatus.UpdateAvailable }
        .sumOf { it.first.sizeMb }

    Card(
        Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(group.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (group.recommended) {
                            Spacer(Modifier.width(8.dp))
                            AssistChip(onClick = {}, label = { Text("Start here") })
                        }
                    }
                    Text(
                        if (group.pickOne) "${group.items.size} sizes" else
                            "${group.items.size} items · ${formatMb(totalMb)} · $installedCount installed",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
            }
            if (group.description.isNotBlank()) {
                Text(group.description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
            if (!group.pickOne && missingMb > 0 && !group.key.startsWith("models")) {
                FilledTonalButton(
                    onClick = { actions.downloadAll(group.items) },
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (installedCount == 0) "Download all · ${formatMb(missingMb)}" else "Get the rest · ${formatMb(missingMb)}")
                }
            }
            if (expanded) {
                Spacer(Modifier.size(6.dp))
                group.items.zip(statuses).forEach { (item, status) -> ItemRow(item, status, actions) }
            }
        }
    }
}

@Composable
private fun ItemRow(item: CatalogItem, status: ItemStatus, actions: ItemActions) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle(item, status),
                    style = MaterialTheme.typography.labelSmall,
                    color = when (status) {
                        is ItemStatus.Failed -> MaterialTheme.colorScheme.error
                        is ItemStatus.UpdateAvailable -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when (status) {
                ItemStatus.Available, is ItemStatus.Failed ->
                    IconButton(onClick = { actions.download(item) }) { Icon(Icons.Filled.Download, "Download") }
                ItemStatus.Queued, is ItemStatus.Downloading ->
                    IconButton(onClick = { actions.cancel(item) }) { Icon(Icons.Filled.Close, "Cancel") }
                ItemStatus.Installed -> {
                    Icon(Icons.Filled.CheckCircle, "Installed", tint = MaterialTheme.colorScheme.primary)
                    IconButton(onClick = { actions.delete(item) }) { Icon(Icons.Filled.Delete, "Delete") }
                }
                is ItemStatus.UpdateAvailable -> {
                    IconButton(onClick = { actions.download(item) }) { Icon(Icons.Filled.SystemUpdate, "Update") }
                    IconButton(onClick = { actions.delete(item) }) { Icon(Icons.Filled.Delete, "Delete") }
                }
            }
        }
        when (status) {
            is ItemStatus.Downloading -> {
                val f = status.fraction
                if (f != null) LinearProgressIndicator(progress = { f }, Modifier.fillMaxWidth().padding(top = 4.dp))
                else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            ItemStatus.Queued -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            else -> Unit
        }
    }
}

private fun subtitle(item: CatalogItem, s: ItemStatus): String = when (s) {
    ItemStatus.Available -> "${formatMb(item.sizeMb)} · ${item.version}" +
        (item.extra["ram_gb"]?.let { " · needs ~$it GB RAM" } ?: "") + (if (item.extra["recommended"] == "true") " · recommended" else "")
    ItemStatus.Queued -> "Queued · waiting for network"
    is ItemStatus.Downloading ->
        if (s.total > 0) "${formatBytes(s.bytes)} of ${formatBytes(s.total)}" else "Starting…"
    is ItemStatus.Failed -> "Failed: ${s.message}"
    ItemStatus.Installed -> "Installed · ${item.version}"
    is ItemStatus.UpdateAvailable -> "Update: ${s.installedVersion} → ${item.version} · ${formatMb(item.sizeMb)}"
}

fun formatMb(mb: Long): String = if (mb >= 1024) "%.1f GB".format(mb / 1024.0) else "$mb MB"

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> "${b / 1024} KB"
}

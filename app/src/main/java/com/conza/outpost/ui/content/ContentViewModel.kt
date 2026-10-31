package com.conza.outpost.ui.content

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.conza.outpost.catalog.Catalog
import com.conza.outpost.catalog.CatalogItem
import com.conza.outpost.container
import com.conza.outpost.data.InstalledResource
import com.conza.outpost.download.DownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

sealed interface ItemStatus {
    data object Available : ItemStatus
    data object Queued : ItemStatus
    data class Downloading(val fraction: Float?, val bytes: Long, val total: Long) : ItemStatus
    data class Failed(val message: String) : ItemStatus
    data object Installed : ItemStatus
    data class UpdateAvailable(val installedVersion: String) : ItemStatus
}

data class StorageInfo(val freeBytes: Long, val totalBytes: Long, val contentBytes: Long)

data class ContentUiState(
    val catalog: Catalog? = null,
    val installed: Map<String, InstalledResource> = emptyMap(),
    val downloads: Map<String, DownloadState> = emptyMap(),
    val storage: StorageInfo = StorageInfo(0, 0, 0),
    val refreshing: Boolean = false,
    val message: String? = null,
) {
    fun statusOf(item: CatalogItem): ItemStatus {
        val dl = downloads[item.id]
        if (dl != null) when (dl.state) {
            WorkInfo.State.RUNNING -> return ItemStatus.Downloading(dl.fraction, dl.bytes, dl.total)
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> return ItemStatus.Queued
            WorkInfo.State.FAILED -> if (installed[item.id] == null) return ItemStatus.Failed(dl.error ?: "Download failed")
            else -> Unit
        }
        val inst = installed[item.id] ?: return ItemStatus.Available
        return if (inst.version == item.version) ItemStatus.Installed else ItemStatus.UpdateAvailable(inst.version)
    }
}

class ContentViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val refreshing = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val storage = MutableStateFlow(readStorage())

    val state: StateFlow<ContentUiState> = combine(
        c.catalog.catalog,
        c.db.installed().observeAll(),
        c.downloader.observe(),
        combine(storage, refreshing, message) { s, r, m -> Triple(s, r, m) },
    ) { catalog, installed, downloads, (s, r, m) ->
        ContentUiState(
            catalog = catalog,
            installed = installed.associateBy { it.resourceId },
            downloads = downloads,
            storage = s,
            refreshing = r,
            message = m,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContentUiState())

    init {
        viewModelScope.launch {
            runCatching { c.catalog.load() }.onFailure { message.value = "Catalog failed to load: ${it.message}" }
        }
        // Keep the storage bar honest as downloads land.
        viewModelScope.launch { c.db.installed().observeAll().collect { storage.value = readStorage() } }
    }

    fun download(item: CatalogItem) = c.downloader.enqueue(item, c.settings.wifiOnly)

    fun downloadAll(items: List<CatalogItem>) {
        val s = state.value
        items.filter {
            when (s.statusOf(it)) {
                ItemStatus.Available, is ItemStatus.UpdateAvailable, is ItemStatus.Failed -> true
                else -> false
            }
        }.forEach(::download)
    }

    fun cancel(item: CatalogItem) {
        c.downloader.cancel(item.id)
        // Drop the partial so a cancelled download frees its space.
        viewModelScope.launch(Dispatchers.IO) {
            c.storage.partialFileFor(item.kind, item.fileName).delete()
            storage.value = readStorage()
        }
    }

    fun delete(item: CatalogItem) = viewModelScope.launch(Dispatchers.IO) {
        c.db.installed().get(item.id)?.let {
            val f = File(it.filePath)
            if (item.kind == com.conza.outpost.catalog.ResourceKind.ZIM) runCatching { c.library.release(f) }
            f.deleteRecursively()
        }
        c.db.installed().delete(item.id)
        storage.value = readStorage()
    }

    fun refreshCatalog() = viewModelScope.launch {
        refreshing.value = true
        message.value = runCatching { c.catalog.refreshFromUpstream() }.fold(
            onSuccess = { n -> if (n == 0) "Catalog is up to date" else "Catalog updated ($n file${if (n == 1) "" else "s"})" },
            onFailure = { "Couldn't reach the catalog: ${it.message}. Using the saved copy." },
        )
        refreshing.value = false
    }

    fun consumeMessage() { message.value = null }

    private fun readStorage() = StorageInfo(c.storage.freeBytes(), c.storage.totalBytes(), c.storage.usedByContentBytes())
}

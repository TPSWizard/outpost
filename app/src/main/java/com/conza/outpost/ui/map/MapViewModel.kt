package com.conza.outpost.ui.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.conza.outpost.catalog.ResourceKind
import com.conza.outpost.container
import com.conza.outpost.map.MapRegion
import com.conza.outpost.map.MapStyleBuilder
import com.conza.outpost.storage.PmtilesHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import java.io.File

data class Bounds(val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double)

data class MapUiState(
    val loading: Boolean = true,
    val regionTitles: List<String> = emptyList(),
    val styleJson: String? = null,
    val bounds: Bounds? = null,
)

class MapViewModel(app: Application) : AndroidViewModel(app) {
    private val baseStyle: String by lazy {
        app.assets.open("map/base-style.json").bufferedReader().use { it.readText() }
    }

    val state: StateFlow<MapUiState> = kotlinx.coroutines.flow.combine(
        app.container.db.installed().observeByKind(ResourceKind.MAP.name).onStart { emit(emptyList()) },
        com.conza.outpost.download.ContentEvents.changes,
    ) { rows, _ -> rows }
        .map { rows ->
            // Catalog installs plus any .pmtiles copied into the maps folder by hand (e.g. from NOMAD).
            val titles = rows.associate { File(it.filePath).name to it.title }
            val files = app.container.storage.allDirs(ResourceKind.MAP)
                .flatMap { d -> d.listFiles { f -> f.isFile && f.name.endsWith(".pmtiles", true) }.orEmpty().toList() }
                .filter { PmtilesHeader.read(it) != null }
            // One file per region: "<id>_<YYYY-MM>.pmtiles" → keep the newest version of each id.
            val newest = files.groupBy { regionIdOf(it.name) }
                .mapValues { (_, fs) -> fs.maxBy { it.name } }
            if (newest.isEmpty()) return@map MapUiState(loading = false)
            val regions = newest.map { (id, f) -> MapRegion(MapStyleBuilder.sourceNameFor(id), f.absolutePath) }
            val headers = newest.values.mapNotNull { PmtilesHeader.read(it) }
            MapUiState(
                loading = false,
                regionTitles = newest.values.map { titles[it.name] ?: it.nameWithoutExtension },
                styleJson = MapStyleBuilder.build(baseStyle, regions),
                bounds = headers.takeIf { it.isNotEmpty() }?.let { hs ->
                    Bounds(hs.minOf { it.minLon }, hs.minOf { it.minLat }, hs.maxOf { it.maxLon }, hs.maxOf { it.maxLat })
                },
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapUiState())
}

/** "alaska_2025-12.pmtiles" → "alaska"; files without a date suffix use the whole name. */
internal fun regionIdOf(fileName: String): String {
    val base = fileName.removeSuffix(".pmtiles").removeSuffix(".PMTILES")
    return Regex("^(.*)_\\d{4}-\\d{2}$").find(base)?.groupValues?.get(1) ?: base
}

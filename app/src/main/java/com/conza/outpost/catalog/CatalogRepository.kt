package com.conza.outpost.catalog

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Loads the content catalog. Order of precedence:
 *   1. a cached copy fetched earlier from NOMAD's GitHub (filesDir/catalog)
 *   2. the snapshot bundled in the APK (assets/catalog)
 * The bundled snapshot means the app is fully usable on first launch with no network.
 */
class CatalogRepository(
    private val context: Context,
    private val http: OkHttpClient,
) {
    private val cacheDir = File(context.filesDir, "catalog").apply { mkdirs() }
    private val _catalog = MutableStateFlow<Catalog?>(null)
    val catalog: StateFlow<Catalog?> = _catalog

    suspend fun load(): Catalog = withContext(Dispatchers.IO) {
        val base = CatalogParser.build(
            CatalogParser.parseMaps(read(MAPS)),
            CatalogParser.parseKiwix(read(KIWIX)),
            CatalogParser.parseWikipedia(read(WIKIPEDIA)),
        )
        // Outpost's own catalog (models, tools) ships with the app and updates with app releases.
        val extras = context.assets.open("catalog/$EXTRAS").bufferedReader().use { it.readText() }
        val c = CatalogParser.withExtras(base, CatalogParser.parseExtras(extras))
        _catalog.value = c
        c
    }

    /**
     * Pull the latest manifests from upstream. Each file is parsed before it replaces
     * the cache, so a bad upstream push can never brick the catalog.
     * Returns the number of files that changed.
     */
    suspend fun refreshFromUpstream(): Int = withContext(Dispatchers.IO) {
        var changed = 0
        for (name in listOf(MAPS, KIWIX, WIKIPEDIA)) {
            val req = Request.Builder().url(UPSTREAM_BASE + name).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code} fetching $name")
                val body = resp.body?.string() ?: error("Empty body for $name")
                when (name) { // validate
                    MAPS -> CatalogParser.parseMaps(body)
                    KIWIX -> CatalogParser.parseKiwix(body)
                    WIKIPEDIA -> CatalogParser.parseWikipedia(body)
                }
                val target = File(cacheDir, name)
                if (!target.exists() || target.readText() != body) {
                    val tmp = File(cacheDir, "$name.tmp")
                    tmp.writeText(body)
                    tmp.renameTo(target)
                    changed++
                }
            }
        }
        load()
        changed
    }

    private fun read(name: String): String {
        val cached = File(cacheDir, name)
        if (cached.exists()) {
            runCatching { return cached.readText() }
        }
        return context.assets.open("catalog/$name").bufferedReader().use { it.readText() }
    }

    companion object {
        const val MAPS = "maps.json"
        const val KIWIX = "kiwix-categories.json"
        const val WIKIPEDIA = "wikipedia.json"
        const val EXTRAS = "outpost-extras.json"
        const val UPSTREAM_BASE =
            "https://raw.githubusercontent.com/Crosstalk-Solutions/project-nomad/refs/heads/main/collections/"
    }
}

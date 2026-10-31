package com.conza.outpost

import android.app.Application
import android.content.Context
import com.conza.outpost.ai.Assistant
import com.conza.outpost.ai.LocalLlm
import com.conza.outpost.ai.RetrievalIndex
import com.conza.outpost.catalog.CatalogRepository
import com.conza.outpost.data.OutpostDatabase
import com.conza.outpost.download.ContentDownloader
import com.conza.outpost.download.DownloadWorker
import com.conza.outpost.library.ZimLibrary
import com.conza.outpost.medical.DrugDatabase
import com.conza.outpost.medical.MedicalData
import com.conza.outpost.storage.ContentStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import java.util.concurrent.TimeUnit

/** Hand-rolled DI. Heavy pieces are lazy so app start stays fast. */
class AppContainer(private val context: Context) {
    /** Lives as long as the process: background indexing, model loading. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true) // GitHub raw, Kiwix mirrors, and Hugging Face all redirect
        .build()
    val db: OutpostDatabase = OutpostDatabase.create(context)
    val storage = ContentStorage(context)
    val catalog = CatalogRepository(context, http)
    val downloader = ContentDownloader(context)
    val settings = Settings(context)

    /** Loading libzim's native code (~18 MB) is deferred until the Library is opened. */
    val library by lazy { ZimLibrary(storage, db.installed()) }
    val medical by lazy { MedicalData(context) }
    val drugs by lazy { DrugDatabase(storage) }
    val llm by lazy { LocalLlm(context) }
    val index by lazy { RetrievalIndex(db, llm, settings, appScope).also { it.pdfText = com.conza.outpost.ai.AndroidPdfText(context) } }

    /** Every PDF in the books folders (downloaded manuals + imports), with catalog titles where known. */
    suspend fun pdfLibrary(): List<Pair<java.io.File, String>> {
        val titles = db.installed().byKind(com.conza.outpost.catalog.ResourceKind.DOC.name).associate { java.io.File(it.filePath).absolutePath to it.title }
        return storage.allRoots().map { java.io.File(it, "books") }.filter { it.isDirectory }
            .flatMap { d -> d.listFiles { f -> f.isFile && f.name.endsWith(".pdf", true) }.orEmpty().toList() }
            .map { f -> f to (titles[f.absolutePath] ?: f.nameWithoutExtension.replace('_', ' ')) }
    }

    fun syncPdfIndex() = appScope.launch { runCatching { index.syncPdfs(pdfLibrary()) } }
    val assistant by lazy { Assistant(context, this) }
    val gridDown = com.conza.outpost.griddown.GridDownRepo(context, db, settings)
    val nearby by lazy { com.conza.outpost.griddown.NearbyMesh(context, appScope) }
    val meshtastic by lazy { com.conza.outpost.griddown.MeshtasticLink(context, appScope) }
}

/** SharedPreferences-backed settings. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi_only", true)
        set(v) = prefs.edit().putBoolean("wifi_only", v).apply()

    var textZoom: Int
        get() = prefs.getInt("text_zoom", 100)
        set(v) = prefs.edit().putInt("text_zoom", v).apply()

    /**
     * Render the map into a TextureView instead of a SurfaceView. Slightly slower, but far
     * more compatible with emulators (BlueStacks, etc.) whose GL surface handling can hang.
     */
    var mapCompatibilityMode: Boolean
        get() = prefs.getBoolean("map_compat", true)
        set(v) = prefs.edit().putBoolean("map_compat", v).apply()

    // ---- Assistant ----

    /** "local" (on-device llama.cpp) or "remote" (Ollama / OpenAI-compatible server). */
    var aiBackend: String
        get() = prefs.getString("ai_backend", "local") ?: "local"
        set(v) = prefs.edit().putString("ai_backend", v).apply()

    /** Catalog id of the on-device chat model to use. */
    var chatModelId: String?
        get() = prefs.getString("chat_model", null)
        set(v) = prefs.edit().putString("chat_model", v).apply()

    var remoteUrl: String
        get() = prefs.getString("remote_url", "http://192.168.1.10:11434") ?: ""
        set(v) = prefs.edit().putString("remote_url", v.trim().trimEnd('/')).apply()

    var remoteModel: String
        get() = prefs.getString("remote_model", "llama3.2:3b") ?: ""
        set(v) = prefs.edit().putString("remote_model", v.trim()).apply()

    /** "ollama" or "openai" (LM Studio, llama.cpp server, vLLM, …). */
    var remoteApi: String
        get() = prefs.getString("remote_api", "ollama") ?: "ollama"
        set(v) = prefs.edit().putString("remote_api", v).apply()

    var remoteApiKey: String
        get() = prefs.getString("remote_key", "") ?: ""
        set(v) = prefs.edit().putString("remote_key", v.trim()).apply()

    var contextSize: Int
        get() = prefs.getInt("ctx_size", 4096)
        set(v) = prefs.edit().putInt("ctx_size", v).apply()

    var temperature: Float
        get() = prefs.getFloat("temperature", 0.4f)
        set(v) = prefs.edit().putFloat("temperature", v).apply()

    /** Ground answers in the offline library, notes, and documents. */
    var useSources: Boolean
        get() = prefs.getBoolean("use_sources", true)
        set(v) = prefs.edit().putBoolean("use_sources", v).apply()

    // ---- Grid-down ----

    /** Red-on-black, lowest refresh rate, background work paused, AI unloaded when not in use. */
    var survivalMode: Boolean
        get() = prefs.getBoolean("survival_mode", false)
        set(v) = prefs.edit().putBoolean("survival_mode", v).apply()

    /** Bigger type and touch targets for one-handed use in the dark. */
    var largeText: Boolean
        get() = prefs.getBoolean("large_text", false)
        set(v) = prefs.edit().putBoolean("large_text", v).apply()

    /** Epoch day the outage started, or null when there is no outage. */
    var outageStartEpochDay: Long?
        get() = prefs.getLong("outage_start", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
        set(v) = prefs.edit().apply { if (v == null) remove("outage_start") else putLong("outage_start", v) }.apply()

    /** HOT | TEMPERATE | COLD */
    var climate: String
        get() = prefs.getString("climate", "TEMPERATE") ?: "TEMPERATE"
        set(v) = prefs.edit().putString("climate", v).apply()

    var altitudeMeters: Int
        get() = prefs.getInt("altitude_m", 0)
        set(v) = prefs.edit().putInt("altitude_m", v).apply()

    var homeLat: Double?
        get() = prefs.getString("home_lat", null)?.toDoubleOrNull()
        set(v) = prefs.edit().putString("home_lat", v?.toString()).apply()

    var homeLon: Double?
        get() = prefs.getString("home_lon", null)?.toDoubleOrNull()
        set(v) = prefs.edit().putString("home_lon", v?.toString()).apply()

    /** Battery percent one assistant answer costs, measured by the benchmark (null = not measured). */
    var aiPercentPerAnswer: Double?
        get() = prefs.getFloat("ai_pct_answer", -1f).toDouble().takeIf { it >= 0 }
        set(v) = prefs.edit().putFloat("ai_pct_answer", (v ?: -1.0).toFloat()).apply()

    /** True when [aiPercentPerAnswer] came from the charge counter (false = estimated from speed). */
    var aiCostMeasured: Boolean
        get() = prefs.getBoolean("ai_cost_measured", false)
        set(v) = prefs.edit().putBoolean("ai_cost_measured", v).apply()

    /** "short" (≤ 80 words) or "explain". */
    var answerMode: String
        get() = prefs.getString("answer_mode", "short") ?: "short"
        set(v) = prefs.edit().putString("answer_mode", v).apply()

    /** Power budget inputs, stored as JSON. */
    var powerBudget: String?
        get() = prefs.getString("power_budget", null)
        set(v) = prefs.edit().putString("power_budget", v).apply()

    var bleachPercent: Double
        get() = prefs.getFloat("bleach_pct", 6f).toDouble()
        set(v) = prefs.edit().putFloat("bleach_pct", v.toFloat()).apply()

    fun listen(onChange: (String?) -> Unit): android.content.SharedPreferences.OnSharedPreferenceChangeListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, k -> onChange(k) }.also { prefs.registerOnSharedPreferenceChangeListener(it) }

    fun unlisten(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) = prefs.unregisterOnSharedPreferenceChangeListener(l)

    // ---- Benchmark ----
    var lastBenchmark: String?
        get() = prefs.getString("last_benchmark", null)
        set(v) = prefs.edit().putString("last_benchmark", v).apply()
}

class OutpostApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        container = AppContainer(this)
        // No API key or tile server: every tile comes from local .pmtiles files.
        MapLibre.getInstance(this)
        DownloadWorker.ensureChannel(this)
        com.conza.outpost.griddown.PowerMonitor.schedule(this, container.settings.survivalMode)
    }
}

val Context.container: AppContainer get() = (applicationContext as OutpostApp).container

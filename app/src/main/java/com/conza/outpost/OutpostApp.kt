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
    val index by lazy { RetrievalIndex(db, llm, settings, appScope) }
    val assistant by lazy { Assistant(context, this) }
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
    }
}

val Context.container: AppContainer get() = (applicationContext as OutpostApp).container

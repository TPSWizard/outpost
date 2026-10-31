package com.conza.outpost.ui.medical

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.conza.outpost.container
import com.conza.outpost.medical.DrugDbInfo
import com.conza.outpost.medical.DrugHit
import com.conza.outpost.medical.DrugIngestWorker
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class IngestState(val running: Boolean, val text: String, val part: Int, val parts: Int, val pct: Int, val records: Int, val error: String?) {
    val overall: Float get() = if (parts == 0) 0f else ((part - 1) * 100 + pct) / (parts * 100f)
}

@OptIn(FlowPreview::class)
class MedicalViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    val data = c.medical

    private val _info = MutableStateFlow<DrugDbInfo?>(null)
    val info: StateFlow<DrugDbInfo?> = _info.asStateFlow()

    val ingest: StateFlow<IngestState?> = WorkManager.getInstance(app)
        .getWorkInfosForUniqueWorkFlow(DrugIngestWorker.UNIQUE)
        .map { list ->
            val w = list.lastOrNull() ?: return@map null
            when (w.state) {
                WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> IngestState(
                    running = true,
                    text = w.progress.getString(DrugIngestWorker.KEY_TEXT) ?: if (w.state == WorkInfo.State.RUNNING) "Starting…" else "Waiting for network…",
                    part = w.progress.getInt(DrugIngestWorker.KEY_PART, 1),
                    parts = w.progress.getInt(DrugIngestWorker.KEY_PARTS, 0),
                    pct = w.progress.getInt(DrugIngestWorker.KEY_PCT, 0),
                    records = w.progress.getInt(DrugIngestWorker.KEY_RECORDS, 0),
                    error = null,
                )
                WorkInfo.State.FAILED -> IngestState(false, "", 0, 0, 0, 0, w.outputData.getString(DrugIngestWorker.KEY_ERROR) ?: "Build failed")
                WorkInfo.State.SUCCEEDED -> { refreshInfo(); null }
                else -> null
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val query = MutableStateFlow("")
    val byIndication = MutableStateFlow(false)
    private val _results = MutableStateFlow<List<DrugHit>>(emptyList())
    val results: StateFlow<List<DrugHit>> = _results.asStateFlow()
    val selected = MutableStateFlow<Set<Long>>(emptySet())

    init {
        refreshInfo()
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(query, byIndication) { q, b -> q to b }.debounce(250).collectLatest { (q, b) ->
                _results.value = if (q.trim().length < 2) emptyList() else c.drugs.search(q, b)
            }
        }
    }

    fun refreshInfo() { viewModelScope.launch { _info.value = c.drugs.info() } }

    fun startBuild() = DrugIngestWorker.start(getApplication(), c.settings.wifiOnly)
    fun cancelBuild() = DrugIngestWorker.cancel(getApplication())
    fun deleteDatabase() { c.drugs.delete(); viewModelScope.launch { c.db.installed().delete(com.conza.outpost.medical.DrugDatabase.RESOURCE_ID) }; refreshInfo() }

    fun toggle(id: Long) { selected.value = selected.value.let { if (id in it) it - id else if (it.size < 5) it + id else it } }
}

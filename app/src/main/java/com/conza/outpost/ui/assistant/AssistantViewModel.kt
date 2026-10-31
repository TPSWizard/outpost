package com.conza.outpost.ui.assistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.conza.outpost.ai.Readiness
import com.conza.outpost.container
import com.conza.outpost.data.ChatMessage
import com.conza.outpost.data.ChatSession
import com.conza.outpost.data.InstalledResource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.conza.outpost.data.InventoryItem
import com.conza.outpost.data.MapMarker
import com.conza.outpost.data.Note
import com.conza.outpost.griddown.AssistantTools
import com.conza.outpost.griddown.ProposedAction
import com.conza.outpost.griddown.WaterDisinfection

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    val assistant = c.assistant
    val settings = c.settings

    val sessionId = MutableStateFlow<Long?>(null)
    val sessions: StateFlow<List<ChatSession>> = c.db.chats().observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val messages: StateFlow<List<ChatMessage>> = sessionId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else c.db.chats().observeMessages(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val generation = assistant.generation

    private val _readiness = MutableStateFlow<Readiness?>(null)
    val readiness: StateFlow<Readiness?> = _readiness
    private val _models = MutableStateFlow<List<InstalledResource>>(emptyList())
    val models: StateFlow<List<InstalledResource>> = _models
    val useSources = MutableStateFlow(settings.useSources)
    val indexStatus = c.index.status
    val crashNote = MutableStateFlow(c.llm.consumeCrashNote())

    val answerMode = MutableStateFlow(settings.answerMode)
    val pdfStatus = c.index.pdfStatus
    val firstAid get() = c.gridDown.firstAid

    init {
        refresh()
        c.syncPdfIndex()
        // Resume the most recent chat.
        viewModelScope.launch { if (sessionId.value == null) sessionId.value = c.db.chats().observeSessions().first().firstOrNull()?.id }
    }

    fun refresh() = viewModelScope.launch {
        _readiness.value = assistant.readiness()
        _models.value = assistant.installedChatModels()
    }

    fun send(text: String) {
        if (text.isBlank() || assistant.busy) return
        viewModelScope.launch { sessionId.value = assistant.send(sessionId.value, text.trim()) }
    }

    fun stop() = assistant.stop()

    fun newChat() { sessionId.value = null }
    fun open(id: Long) { sessionId.value = id }
    fun delete(id: Long) = viewModelScope.launch {
        c.db.chats().deleteMessages(id); c.db.chats().deleteSession(id)
        if (sessionId.value == id) sessionId.value = null
    }

    fun setUseSources(v: Boolean) { settings.useSources = v; useSources.value = v }

    fun setAnswerMode(mode: String) { settings.answerMode = mode; answerMode.value = mode }

    /** The Assistant screen is visible (true) or was left (false). Survival mode frees the model's memory on leave. */
    fun setVisible(v: Boolean) {
        assistant.assistantVisible = v
        if (!v && settings.survivalMode && !assistant.busy) c.appScope.launch { runCatching { c.llm.unloadAll() } }
    }

    /** Runs a proposed action after the user taps Confirm (or records a dismissal). */
    fun resolveAction(m: ChatMessage, index: Int, confirm: Boolean) = viewModelScope.launch {
        val states = AssistantTools.decodeStates(m.actionState).toMutableList()
        val st = states.getOrNull(index) ?: return@launch
        if (st.status != "proposed") return@launch
        states[index] = if (!confirm) st.copy(status = "dismissed") else runCatching { st.copy(status = "done", result = perform(st.action)) }
            .getOrElse { st.copy(status = "failed", result = it.message) }
        c.db.chats().updateActionState(m.id, AssistantTools.encodeStates(states))
    }

    private suspend fun perform(a: ProposedAction): String = when (a) {
        is ProposedAction.AddSupply -> {
            c.db.inventory().upsert(InventoryItem(category = a.category, name = a.name, quantity = a.quantity, unit = a.unit,
                litersPerUnit = a.litersPerUnit, caloriesPerUnit = a.caloriesPerUnit))
            "Added to Supplies."
        }
        is ProposedAction.WaterDose -> {
            val d = WaterDisinfection.bleachDose(a.liters, a.bleachPercent, a.cloudy)
            "Add ${d.friendly} of ${ProposedAction.fmt(a.bleachPercent)}% bleach (%.2f mL), stir, wait ${d.waitMinutes} min. Calculated by Outpost (EPA method), not the AI.".format(d.milliliters)
        }
        is ProposedAction.Checklist -> {
            val note = Note(title = a.title, body = a.items.joinToString("\n") { "- [ ] $it" })
            val id = c.db.notes().upsert(note)
            runCatching { c.index.indexNote(note.copy(id = id)) }
            "Saved to Notes."
        }
        is ProposedAction.MapPin -> {
            c.db.markers().upsert(MapMarker(name = a.name, lat = a.lat, lon = a.lon))
            "Pin added to the map."
        }
    }

    fun pickModel(id: String) {
        settings.chatModelId = id
        settings.aiBackend = "local"
        refresh()
    }
}

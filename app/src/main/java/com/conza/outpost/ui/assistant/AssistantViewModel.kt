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

    init {
        refresh()
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

    fun pickModel(id: String) {
        settings.chatModelId = id
        settings.aiBackend = "local"
        refresh()
    }
}

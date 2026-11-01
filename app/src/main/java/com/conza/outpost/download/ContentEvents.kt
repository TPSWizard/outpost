package com.conza.outpost.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Bumped whenever content files appear or disappear (a download finishes, a file is imported,
 * received, or deleted). Library and Map listen to it directly, so they refresh right away
 * without waiting for a database change notification or a screen resume.
 */
object ContentEvents {
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes
    fun changed() { _changes.value = _changes.value + 1 }
}

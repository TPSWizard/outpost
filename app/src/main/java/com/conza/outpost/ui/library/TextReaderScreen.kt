package com.conza.outpost.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Reads a downloaded or imported UTF-8 book without a network connection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextReaderScreen(path: String, onBack: () -> Unit) {
    val file = File(path)
    val body = produceState<Result<String>?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { require(file.isFile && file.extension.equals("txt", true)); file.readText(Charsets.UTF_8) }
        }
    }.value
    Scaffold(topBar = {
        TopAppBar(title = { Text(file.nameWithoutExtension.replace('_', ' ')) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }) { padding ->
        when {
            body == null -> Text("Opening reading…", Modifier.padding(padding).padding(16.dp))
            body.isFailure -> Text("Could not open this text file.", Modifier.padding(padding).padding(16.dp))
            else -> {
                val paragraphs = remember(body) {
                    body.getOrThrow().replace("\r\n", "\n").split(Regex("\n[ \t]*\n"))
                        .map { it.trim() }.filter { it.isNotBlank() }
                }
                LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                    items(paragraphs) { paragraph ->
                        Text(paragraph, Modifier.fillMaxWidth().padding(bottom = 12.dp))
                    }
                }
            }
        }
    }
}

package com.conza.outpost.ui.food

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.conza.outpost.food.FoodValidation
import kotlinx.coroutines.launch

internal data class FoodInput(val id: String, val label: String, val value: String = "", val options: List<Pair<String, String>> = emptyList(), val numeric: Boolean = false, val toggle: Boolean = false)
internal data class FoodFormSpec(val title: String, val help: String, val fields: List<FoodInput>, val save: suspend (Map<String, String>) -> Unit)
internal fun input(id: String, label: String, value: Any? = "", numeric: Boolean = false) = FoodInput(id, label, value?.toString().orEmpty(), numeric = numeric)
internal fun toggle(id: String, label: String, value: Boolean) = FoodInput(id, label, value.toString(), toggle = true)
internal fun choice(id: String, label: String, value: String?, options: List<Pair<String, String>>) = FoodInput(id, label, value.orEmpty(), options)
internal fun Map<String, String>.text(id: String) = getValue(id).trim()
internal fun Map<String, String>.opt(id: String) = text(id).ifBlank { null }
internal fun Map<String, String>.number(id: String): Double = text(id).toDoubleOrNull()?.also { FoodValidation.nonnegative(it, id) } ?: error("Enter a valid $id")
internal fun Map<String, String>.optionalNumber(id: String): Double? = opt(id)?.let { number(id) }
internal fun Map<String, String>.integer(id: String): Int = text(id).toIntOrNull() ?: error("Enter a whole number for $id")
internal fun Map<String, String>.flag(id: String) = text(id) == "true"

@Composable
internal fun FoodForm(spec: FoodFormSpec, dismiss: () -> Unit) {
    val values = remember(spec) { mutableStateMapOf<String, String>().apply { spec.fields.forEach { put(it.id, it.value) } } }
    var error by remember(spec) { mutableStateOf<String?>(null) }
    var busy by remember(spec) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(spec.title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(spec.help, style = MaterialTheme.typography.bodySmall)
            spec.fields.forEach { field ->
                when {
                    field.toggle -> Row { Checkbox(values[field.id] == "true", { values[field.id] = it.toString() }); Text(field.label, Modifier.padding(top = 12.dp)) }
                    field.options.isNotEmpty() -> FoodSelect(field.label, values[field.id].orEmpty(), field.options) { values[field.id] = it }
                    else -> OutlinedTextField(values[field.id].orEmpty(), { values[field.id] = it }, label = { Text(field.label) }, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = if (field.numeric) KeyboardType.Decimal else KeyboardType.Text))
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        busy = true
        scope.launch {
            try { spec.save(values.toMap()); dismiss() }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.message ?: "Could not save" }
            finally { busy = false }
        }
    }) { Text(if (busy) "Saving…" else "Save") } }, dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("Cancel") } })
}

@Composable
internal fun FoodSelect(label: String, selected: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(options.firstOrNull { it.first == selected }?.second ?: "Choose…") }
            DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(id); expanded = false }) }
            }
        }
    }
}

@Composable
internal fun FoodCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

internal fun amount(value: Double): String = java.text.DecimalFormat("#,##0.##").format(value)

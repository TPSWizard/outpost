package com.conza.outpost.ui.griddown

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.conza.outpost.container
import com.conza.outpost.data.HouseholdMember
import com.conza.outpost.data.Medication
import com.conza.outpost.data.PlanItem
import com.conza.outpost.griddown.Climate
import com.conza.outpost.griddown.Needs
import com.conza.outpost.ui.common.BackTopBar
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun HouseholdScreen(onBack: () -> Unit) {
    val c = LocalContext.current.container
    val dao = c.db.household()
    val scope = rememberCoroutineScope()
    val members by dao.observeMembers().collectAsState(initial = emptyList())
    val meds by dao.observeMeds().collectAsState(initial = emptyList())
    val plan by dao.observePlan().collectAsState(initial = emptyList())
    var climate by remember { mutableStateOf(c.gridDown.climate()) }
    var altitude by remember { mutableStateOf(c.settings.altitudeMeters.toString()) }
    var lat by remember { mutableStateOf(c.settings.homeLat?.toString().orEmpty()) }
    var lon by remember { mutableStateOf(c.settings.homeLon?.toString().orEmpty()) }
    var editMember by remember { mutableStateOf<HouseholdMember?>(null) }
    var editMed by remember { mutableStateOf<Medication?>(null) }
    var editPlan by remember { mutableStateOf<PlanItem?>(null) }
    val year = LocalDate.now().year

    editMember?.let { m -> MemberDialog(m, onDismiss = { editMember = null }, onSave = { scope.launch { dao.upsertMember(it) }; editMember = null },
        onDelete = { scope.launch { dao.deleteMedsFor(m.id); dao.deleteMember(m.id) }; editMember = null }) }
    editMed?.let { m -> MedDialog(m, members.filter { !it.isPet }, onDismiss = { editMed = null }, onSave = { scope.launch { dao.upsertMed(it) }; editMed = null },
        onDelete = { scope.launch { dao.deleteMed(m.id) }; editMed = null }) }
    editPlan?.let { p -> PlanDialog(p, onDismiss = { editPlan = null }, onSave = { scope.launch { dao.upsertPlan(it) }; editPlan = null },
        onDelete = { scope.launch { dao.deletePlan(p.id) }; editPlan = null }) }

    Scaffold(topBar = { BackTopBar("Household", onBack, subtitle = "Stays on this phone") }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { SectionTitle("People & pets") }
            items(members, key = { "m${it.id}" }) { m ->
                Card(Modifier.fillMaxWidth().clickable { editMember = m }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(m.name, fontWeight = FontWeight.SemiBold)
                        val desc = if (m.isPet) "${m.petType.ifBlank { "pet" }}${m.weightKg?.let { " · ${fmt(it)} kg" } ?: ""}"
                        else "${Needs.age(m, year)?.let { "age $it" } ?: "age unknown"} · ${m.activity} activity${if (m.pregnantOrNursing) " · pregnant/nursing" else ""}"
                        Text(desc, style = MaterialTheme.typography.bodySmall)
                        Text("Needs ≈ %.1f L water%s a day".format(Needs.waterLitersPerDay(m, climate, year),
                            if (m.isPet) "" else ", %.0f kcal".format(Needs.caloriesPerDay(m, climate, year))), style = MaterialTheme.typography.bodySmall)
                        if (m.medicalNotes.isNotBlank()) Text("Medical: ${m.medicalNotes}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editMember = HouseholdMember(name = "") }) { Text("Add person") }
                    OutlinedButton(onClick = { editMember = HouseholdMember(name = "", isPet = true, petType = "dog") }) { Text("Add pet") }
                }
            }

            item { SectionTitle("Medications") }
            items(meds, key = { "d${it.id}" }) { m ->
                val who = members.firstOrNull { it.id == m.memberId }?.name ?: "—"
                Card(Modifier.fillMaxWidth().clickable { editMed = m }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(m.name + if (m.critical) "  · critical" else "", fontWeight = FontWeight.SemiBold)
                        Text("$who · ${fmt(m.dosesPerDay)} a day · ${fmt(m.onHand)} on hand" +
                            (if (m.dosesPerDay > 0) " · ≈ %.0f days".format(m.onHand / m.dosesPerDay) else ""), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                OutlinedButton(enabled = members.any { !it.isPet }, onClick = { editMed = Medication(memberId = members.first { !it.isPet }.id, name = "", dosesPerDay = 1.0, onHand = 30.0) }) {
                    Text("Add medication")
                }
            }

            item { SectionTitle("Where you live") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Climate.entries.forEach { cl ->
                            FilterChip(selected = climate == cl, onClick = { climate = cl; c.settings.climate = cl.name }, label = { Text(cl.label) })
                        }
                    }
                    NumberField("Altitude", altitude, { altitude = it; it.toIntOrNull()?.let { a -> c.settings.altitudeMeters = a } }, Modifier.fillMaxWidth(), decimal = false, suffix = "m")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("Home latitude", lat, { lat = it; c.settings.homeLat = it.toDoubleOrNull() }, Modifier.weight(1f))
                        NumberField("Home longitude", lon, { lon = it; c.settings.homeLon = it.toDoubleOrNull() }, Modifier.weight(1f))
                    }
                    Text("Used for sunrise/sunset and the boiling time at altitude. Long-press the map to read coordinates.", style = MaterialTheme.typography.bodySmall)
                }
            }

            item { SectionTitle("Family plan: contacts & rally points") }
            items(plan, key = { "p${it.id}" }) { p ->
                Card(Modifier.fillMaxWidth().clickable { editPlan = p }) {
                    Column(Modifier.padding(12.dp)) {
                        Text((if (p.type == "rally") "Rally point · " else "Contact · ") + p.name, fontWeight = FontWeight.SemiBold)
                        if (p.detail.isNotBlank()) Text(p.detail, style = MaterialTheme.typography.bodySmall)
                        if (p.lat != null && p.lon != null) Text("%.5f, %.5f".format(p.lat, p.lon), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editPlan = PlanItem(type = "contact", name = "") }) { Text("Add contact") }
                    OutlinedButton(onClick = { editPlan = PlanItem(type = "rally", name = "") }) { Text("Add rally point") }
                }
            }
        }
    }
}

@Composable
private fun EditDialog(title: String, canSave: Boolean, onDismiss: () -> Unit, onSave: () -> Unit, onDelete: (() -> Unit)?, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f))
                if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete") }
            }
        },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } },
        confirmButton = { TextButton(enabled = canSave, onClick = onSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MemberDialog(m: HouseholdMember, onDismiss: () -> Unit, onSave: (HouseholdMember) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(m.name) }
    var birth by remember { mutableStateOf(m.birthYear?.toString().orEmpty()) }
    var activity by remember { mutableStateOf(m.activity) }
    var notes by remember { mutableStateOf(m.medicalNotes) }
    var petType by remember { mutableStateOf(m.petType) }
    var weight by remember { mutableStateOf(m.weightKg?.let { fmt(it) }.orEmpty()) }
    var pregnant by remember { mutableStateOf(m.pregnantOrNursing) }
    EditDialog(if (m.isPet) "Pet" else "Person", name.isNotBlank(), onDismiss, {
        onSave(m.copy(name = name.trim(), birthYear = birth.toIntOrNull(), activity = activity, medicalNotes = notes.trim(), petType = petType,
            weightKg = weight.toDoubleOrNull(), pregnantOrNursing = pregnant))
    }, if (m.id != 0L) onDelete else null) {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
        if (m.isPet) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("dog", "cat", "other").forEach { t -> FilterChip(petType == t, { petType = t }, label = { Text(t) }) }
            }
            NumberField("Weight", weight, { weight = it }, suffix = "kg")
        } else {
            NumberField("Birth year", birth, { birth = it }, decimal = false)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("low", "moderate", "high").forEach { t -> FilterChip(activity == t, { activity = t }, label = { Text(t) }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(pregnant, { pregnant = it }); Text("Pregnant or nursing") }
            OutlinedTextField(notes, { notes = it }, label = { Text("Medical needs (allergies, conditions)") })
        }
    }
}

@Composable
private fun MedDialog(m: Medication, people: List<HouseholdMember>, onDismiss: () -> Unit, onSave: (Medication) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(m.name) }
    var who by remember { mutableStateOf(m.memberId) }
    var perDay by remember { mutableStateOf(fmt(m.dosesPerDay)) }
    var onHand by remember { mutableStateOf(fmt(m.onHand)) }
    var critical by remember { mutableStateOf(m.critical) }
    var notes by remember { mutableStateOf(m.notes) }
    EditDialog("Medication", name.isNotBlank() && perDay.toDoubleOrNull() != null && onHand.toDoubleOrNull() != null, onDismiss, {
        onSave(m.copy(name = name.trim(), memberId = who, dosesPerDay = perDay.toDouble(), onHand = onHand.toDouble(), critical = critical, notes = notes.trim(),
            updatedAt = System.currentTimeMillis()))
    }, if (m.id != 0L) onDelete else null) {
        OutlinedTextField(name, { name = it }, label = { Text("Name (e.g. insulin glargine)") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { people.forEach { p -> FilterChip(who == p.id, { who = p.id }, label = { Text(p.name) }) } }
        NumberField("Doses per day", perDay, { perDay = it })
        NumberField("Doses on hand", onHand, { onHand = it })
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(critical, { critical = it }); Text("Critical (can't safely miss doses)") }
        OutlinedTextField(notes, { notes = it }, label = { Text("Notes (storage, timing)") })
    }
}

@Composable
private fun PlanDialog(p: PlanItem, onDismiss: () -> Unit, onSave: (PlanItem) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(p.name) }
    var detail by remember { mutableStateOf(p.detail) }
    var lat by remember { mutableStateOf(p.lat?.toString().orEmpty()) }
    var lon by remember { mutableStateOf(p.lon?.toString().orEmpty()) }
    EditDialog(if (p.type == "rally") "Rally point" else "Contact", name.isNotBlank(), onDismiss, {
        onSave(p.copy(name = name.trim(), detail = detail.trim(), lat = lat.toDoubleOrNull(), lon = lon.toDoubleOrNull()))
    }, if (p.id != 0L) onDelete else null) {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
        OutlinedTextField(detail, { detail = it }, label = { Text(if (p.type == "rally") "Directions / when to go" else "Phone, radio call sign, address") })
        if (p.type == "rally") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Latitude", lat, { lat = it }, Modifier.weight(1f))
            NumberField("Longitude", lon, { lon = it }, Modifier.weight(1f))
        }
    }
}

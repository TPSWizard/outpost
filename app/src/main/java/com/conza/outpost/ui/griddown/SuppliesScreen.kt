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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.conza.outpost.data.InventoryItem
import com.conza.outpost.griddown.Rationing
import com.conza.outpost.griddown.SupplyCategory
import com.conza.outpost.ui.common.BackTopBar
import com.conza.outpost.ui.common.Notice
import kotlinx.coroutines.launch

/** Common items with sensible per-unit values so entry is quick in the dark. */
private val PRESETS = listOf(
    InventoryItem(category = "WATER", name = "Bottled water 0.5 L", quantity = 24.0, unit = "bottle", litersPerUnit = 0.5),
    InventoryItem(category = "WATER", name = "Water jug 1 gal", quantity = 1.0, unit = "gal"),
    InventoryItem(category = "WATER", name = "Water container 5 gal", quantity = 1.0, unit = "container", litersPerUnit = 18.9),
    InventoryItem(category = "FOOD", name = "Canned beans (15 oz)", quantity = 6.0, unit = "can", caloriesPerUnit = 385.0),
    InventoryItem(category = "FOOD", name = "Rice (1 lb)", quantity = 1.0, unit = "lb", caloriesPerUnit = 1650.0),
    InventoryItem(category = "FOOD", name = "Peanut butter (16 oz)", quantity = 1.0, unit = "jar", caloriesPerUnit = 2660.0),
    InventoryItem(category = "FOOD", name = "Freeze-dried meal (2 serv.)", quantity = 1.0, unit = "pouch", caloriesPerUnit = 500.0),
    InventoryItem(category = "FUEL", name = "Gasoline", quantity = 5.0, unit = "gal", dailyUse = 0.5),
    InventoryItem(category = "FUEL", name = "Propane 1 lb canister", quantity = 4.0, unit = "canister", dailyUse = 0.5),
    InventoryItem(category = "BATTERIES", name = "AA batteries", quantity = 24.0, unit = "cell", dailyUse = 1.0),
    InventoryItem(category = "MEDICINE", name = "Ibuprofen 200 mg", quantity = 100.0, unit = "tablet"),
)

@Composable
fun SuppliesScreen(onBack: () -> Unit, onDrugSearch: (String) -> Unit) {
    val c = LocalContext.current.container
    val dao = c.db.inventory()
    val scope = rememberCoroutineScope()
    val snapshot by remember { c.gridDown.observe() }.collectAsState(initial = null)
    var edit by remember { mutableStateOf<InventoryItem?>(null) }
    var filter by remember { mutableStateOf<SupplyCategory?>(null) }

    edit?.let { item -> ItemDialog(item, onDismiss = { edit = null }, onSave = { scope.launch { dao.upsert(it.copy(updatedAt = System.currentTimeMillis())) }; edit = null },
        onDelete = { scope.launch { dao.delete(item.id) }; edit = null }) }

    Scaffold(
        topBar = { BackTopBar("Supplies", onBack, subtitle = "Days remaining at your household's daily needs") },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { edit = InventoryItem(category = (filter ?: SupplyCategory.WATER).name, name = "", quantity = 1.0) }) {
            Icon(Icons.Filled.Add, null); Text("Add") } },
    ) { pad ->
        val s = snapshot
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s == null) return@LazyColumn
            if (s.members.isEmpty()) item { Notice("Add your household (Today → Household) so days remaining use your family's real needs.") }
            item { WarningCard(s.warnings, "Under ${Rationing.WARN_DAYS.toInt()} days left") }
            items(s.statuses, key = { "s-${it.category}" }) { st ->
                Card(Modifier.fillMaxWidth().clickable { filter = if (filter == st.category) null else st.category },
                    colors = CardDefaults.cardColors(containerColor = if (filter == st.category) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(st.category.label, fontWeight = FontWeight.SemiBold)
                            Text(st.summary, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(st.daysLeft?.let { "%.0f d".format(it) } ?: "—", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                            color = if (st.warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (s.runways.isNotEmpty()) {
                item { SectionTitle("Medication runway") }
                item { Notice("Ask a pharmacist before stretching, splitting, or substituting any medication. Some (insulin, seizure, heart, thyroid) are dangerous to ration.") }
                items(s.runways, key = { "r-${it.med.id}" }) { r ->
                    Card(Modifier.fillMaxWidth().clickable { onDrugSearch(r.med.name) }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.med.name + if (r.med.critical) " · critical" else "", fontWeight = FontWeight.SemiBold)
                                Text("${fmt(r.med.onHand)} on hand · ${fmt(r.med.dosesPerDay)}/day · tap for drug label", style = MaterialTheme.typography.bodySmall)
                            }
                            Text("%.0f d".format(r.daysLeft), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                                color = if (r.warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            item { SectionTitle(filter?.let { "${it.label} items" } ?: "All items") }
            val list = s.items.filter { filter == null || it.category == filter!!.name }
            if (list.isEmpty()) item { Text("Nothing here yet. Tap Add, or pick a common item:", style = MaterialTheme.typography.bodySmall) }
            if (list.isEmpty()) item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PRESETS.filter { filter == null || it.category == filter!!.name }.forEach { p -> FilterChip(false, { edit = p.copy() }, label = { Text(p.name) }) }
                }
            }
            items(list, key = { "i-${it.id}" }) { item ->
                Card(Modifier.fillMaxWidth().clickable { edit = item }) {
                    Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, fontWeight = FontWeight.Medium)
                            val extra = listOfNotNull(
                                Rationing.litersPerUnit(item)?.let { "%.1f L total".format(it * item.quantity) },
                                item.caloriesPerUnit?.let { "%,.0f kcal total".format(it * item.quantity) },
                                item.dailyUse?.let { "uses ${fmt(it)}/day" },
                                item.expiry?.takeIf { it.isNotBlank() }?.let { "exp. $it" },
                            ).joinToString(" · ")
                            Text("${fmt(item.quantity)} ${item.unit}" + if (extra.isNotEmpty()) " · $extra" else "", style = MaterialTheme.typography.bodySmall)
                        }
                        // One-tap use / restock for quick daily updates.
                        IconButton(onClick = { scope.launch { dao.upsert(item.copy(quantity = (item.quantity - 1).coerceAtLeast(0.0), updatedAt = System.currentTimeMillis())) } }) {
                            Icon(Icons.Filled.Remove, "Use one")
                        }
                        IconButton(onClick = { scope.launch { dao.upsert(item.copy(quantity = item.quantity + 1, updatedAt = System.currentTimeMillis())) } }) {
                            Icon(Icons.Filled.Add, "Add one")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemDialog(item: InventoryItem, onDismiss: () -> Unit, onSave: (InventoryItem) -> Unit, onDelete: () -> Unit) {
    var cat by remember { mutableStateOf(SupplyCategory.of(item.category)) }
    var name by remember { mutableStateOf(item.name) }
    var qty by remember { mutableStateOf(fmt(item.quantity)) }
    var unit by remember { mutableStateOf(item.unit) }
    var liters by remember { mutableStateOf(item.litersPerUnit?.let { fmt(it, 3) }.orEmpty()) }
    var kcal by remember { mutableStateOf(item.caloriesPerUnit?.let { fmt(it) }.orEmpty()) }
    var daily by remember { mutableStateOf(item.dailyUse?.let { fmt(it, 2) }.orEmpty()) }
    var expiry by remember { mutableStateOf(item.expiry.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (item.id == 0L) "Add supply" else "Edit supply", Modifier.weight(1f))
                if (item.id != 0L) IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete") }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SupplyCategory.entries.forEach { sc -> FilterChip(cat == sc, { cat = sc }, label = { Text(sc.label) }) }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Quantity", qty, { qty = it }, Modifier.weight(1f))
                    OutlinedTextField(unit, { unit = it }, label = { Text("Unit") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                when (cat) {
                    SupplyCategory.WATER -> NumberField("Liters per unit (blank if unit is L/gal)", liters, { liters = it })
                    SupplyCategory.FOOD -> NumberField("Calories per unit (see label × servings)", kcal, { kcal = it })
                    else -> NumberField("Used per day (optional)", daily, { daily = it })
                }
                OutlinedTextField(expiry, { expiry = it }, label = { Text("Expiry (optional)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && (qty.toDoubleOrNull() ?: -1.0) >= 0, onClick = {
                onSave(item.copy(category = cat.name, name = name.trim(), quantity = qty.toDouble(), unit = unit.trim(),
                    litersPerUnit = liters.toDoubleOrNull(), caloriesPerUnit = kcal.toDoubleOrNull(), dailyUse = daily.toDoubleOrNull(), expiry = expiry.trim().ifBlank { null }))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

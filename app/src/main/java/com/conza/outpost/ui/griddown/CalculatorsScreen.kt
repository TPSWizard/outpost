package com.conza.outpost.ui.griddown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.conza.outpost.container
import com.conza.outpost.griddown.FoodSafety
import com.conza.outpost.griddown.Needs
import com.conza.outpost.griddown.WaterDisinfection
import com.conza.outpost.ui.common.BackTopBar
import java.time.LocalDate

@Composable
fun CalculatorsScreen(onBack: () -> Unit, initialTab: Int = 0) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    Scaffold(topBar = { BackTopBar("Water & food", onBack) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf("Treat water", "Fridge & freezer", "Daily needs").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (tab) {
                    0 -> WaterCalc()
                    1 -> FoodCalc()
                    else -> NeedsCalc()
                }
            }
        }
    }
}

@Composable
private fun ResultCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
fun WaterCalc() {
    val c = LocalContext.current.container
    var amount by remember { mutableStateOf("1") }
    var gallons by remember { mutableStateOf(true) }
    var pct by remember { mutableStateOf(fmt(c.settings.bleachPercent, 2)) }
    var cloudy by remember { mutableStateOf(false) }
    val liters = amount.toDoubleOrNull()?.let { if (gallons) it * Needs.GALLON_L else it }
    val dose = runCatching { WaterDisinfection.bleachDose(liters ?: 0.0, pct.toDoubleOrNull() ?: 0.0, cloudy) }

    Text("Bleach (EPA emergency method)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        NumberField("Water", amount, { amount = it }, Modifier.weight(1f))
        FilterChip(gallons, { gallons = true }, label = { Text("gal") })
        FilterChip(!gallons, { gallons = false }, label = { Text("L") })
    }
    NumberField("Bleach strength (on the label)", pct, { pct = it; it.toDoubleOrNull()?.let { p -> if (p in 4.0..10.0) c.settings.bleachPercent = p } }, Modifier.fillMaxWidth(), suffix = "%")
    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(cloudy, { cloudy = it }); Text("Water is cloudy, colored, or very cold") }
    dose.onSuccess { d ->
        ResultCard {
            Text("Add ${d.friendly}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("= %.2f mL of %s%% bleach. Wait %d minutes.".format(d.milliliters, pct, d.waitMinutes))
            d.instructions.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }.onFailure { Text(it.message ?: "Check the numbers", color = MaterialTheme.colorScheme.error) }

    Text("Boiling", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    var alt by remember { mutableStateOf(c.settings.altitudeMeters.toString()) }
    NumberField("Altitude", alt, { alt = it }, Modifier.fillMaxWidth(), decimal = false, suffix = "m")
    val mins = WaterDisinfection.boilMinutes(alt.toDoubleOrNull() ?: 0.0)
    ResultCard {
        Text("Rolling boil for $mins minute${if (mins > 1) "s" else ""}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("CDC: 1 minute; 3 minutes above 2,000 m (6,500 ft). Let it cool covered. Boiling kills germs but doesn't remove chemicals.")
    }
    Text("Bleach doesn't reliably kill Cryptosporidium; boiling does. Never use bleach with added scents or cleaners.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun FoodCalc() {
    var freezer by remember { mutableStateOf(false) }
    var hours by remember { mutableStateOf("2") }
    var full by remember { mutableStateOf(true) }
    var opened by remember { mutableStateOf(false) }
    val r = FoodSafety.check(if (freezer) FoodSafety.Appliance.FREEZER else FoodSafety.Appliance.FRIDGE, hours.toDoubleOrNull() ?: 0.0, full, opened)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!freezer, { freezer = false }, label = { Text("Refrigerator") })
        FilterChip(freezer, { freezer = true }, label = { Text("Freezer") })
    }
    NumberField("Hours without power", hours, { hours = it }, Modifier.fillMaxWidth())
    if (freezer) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(full, { full = it }); Text("Freezer is full (half full keeps 24 h)") }
    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(opened, { opened = it }); Text("Door has been opened often") }
    ResultCard {
        Text(r.verdict, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Safe window: %.0f hours (USDA).".format(r.safeHours), style = MaterialTheme.typography.bodySmall)
    }
    Text("Throw out if above 40 °F (4 °C) for 2+ hours", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    r.discardIfWarm.forEach { Text("• $it") }
    Text("Usually OK to keep", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    r.usuallyKeep.forEach { Text("• $it") }
    Text("Never taste food to check it. When in doubt, throw it out.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun NeedsCalc() {
    val c = LocalContext.current.container
    val members by c.db.household().observeMembers().collectAsState(initial = emptyList())
    val climate = c.gridDown.climate()
    val year = LocalDate.now().year
    if (members.isEmpty()) { Text("Add your household (Today → Household) to see daily needs."); return }
    val h = Needs.household(members, climate, year)
    ResultCard {
        Text("Your household needs each day", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Water: %.1f L (%.1f gal)".format(h.waterL, h.waterL / Needs.GALLON_L), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Food: %,.0f kcal for people".format(h.peopleKcal) + if (h.pets > 0) " + %,.0f kcal for pets".format(h.petKcal) else "",
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("90 days: %.0f L water (%.0f gal), %,.0f kcal".format(h.waterL * 90, h.waterL * 90 / Needs.GALLON_L, (h.peopleKcal + h.petKcal) * 90))
    }
    members.forEach { m ->
        Row {
            Text(m.name, Modifier.weight(1f))
            Text("%.1f L".format(Needs.waterLitersPerDay(m, climate, year)) + " · %,.0f kcal".format(Needs.caloriesPerDay(m, climate, year)))
        }
    }
    Text("Water: FEMA/Ready.gov 1 gallon per person per day (more in heat, for nursing, or illness). Calories: USDA Dietary Guidelines by age. " +
        "Pets: ~60 mL per kg; energy 70 × kg^0.75 × activity factor.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

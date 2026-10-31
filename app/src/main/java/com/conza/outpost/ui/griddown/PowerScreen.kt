package com.conza.outpost.ui.griddown

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.conza.outpost.container
import com.conza.outpost.griddown.PowerModel
import com.conza.outpost.griddown.PowerMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

private val budgetJson = Json { ignoreUnknownKeys = true }

@Composable
fun PowerScreen(onBack: () -> Unit, onBenchmark: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    var survival by remember { mutableStateOf(c.settings.survivalMode) }
    var estimate by remember { mutableStateOf<PowerMonitor.Estimate?>(null) }
    val modes by produceState<Map<Boolean, Double>>(emptyMap()) {
        value = c.db.gridDown().batterySince(System.currentTimeMillis() - 14 * 86_400_000L)
            .map { PowerModel.Sample(it.at, it.percent, it.charging) to it.survivalMode }
            .let(PowerModel::drainByMode)
    }
    val audit by produceState(emptyList<Pair<String, String>>(), survival) { value = withContext(Dispatchers.IO) { PowerMonitor.audit(ctx) } }
    LaunchedEffect(Unit) { estimate = PowerMonitor.estimate(ctx) }

    val saved = remember { c.settings.powerBudget?.let { runCatching { budgetJson.decodeFromString(PowerModel.BudgetInput.serializer(), it) }.getOrNull() } ?: PowerModel.BudgetInput() }
    val pct = estimate?.reading?.percent?.toDouble() ?: saved.batteryPercent
    var solar by remember { mutableStateOf(fmt(saved.solarWatts)) }
    var sun by remember { mutableStateOf(fmt(saved.sunHours)) }
    var bank by remember { mutableStateOf(fmt(saved.powerBankWh)) }
    var car by remember { mutableStateOf(fmt(saved.carHoursPerDay)) }
    var screen by remember { mutableStateOf(fmt(saved.screenHoursPerDay)) }
    var ai by remember { mutableStateOf(saved.aiAnswersPerDay.toString()) }
    var map by remember { mutableStateOf(fmt(saved.mapMinutesPerDay)) }
    val aiCost = c.settings.aiPercentPerAnswer ?: saved.aiPercentPerAnswer
    val phoneWh = estimate?.reading?.capacityUah?.let { it / 1e6 * 3.85 }?.takeIf { it in 5.0..40.0 } ?: saved.phoneWh
    val idle = (if (survival) modes[true] else modes[false]) ?: saved.idlePercentPerHour
    val input = PowerModel.BudgetInput(
        phoneWh = phoneWh, batteryPercent = pct, solarWatts = solar.toDoubleOrNull() ?: 0.0, sunHours = sun.toDoubleOrNull() ?: 4.0,
        powerBankWh = bank.toDoubleOrNull() ?: 0.0, carHoursPerDay = car.toDoubleOrNull() ?: 0.0, screenHoursPerDay = screen.toDoubleOrNull() ?: 2.0,
        aiAnswersPerDay = ai.toIntOrNull() ?: 0, aiPercentPerAnswer = aiCost, mapMinutesPerDay = map.toDoubleOrNull() ?: 0.0, idlePercentPerHour = idle,
    )
    LaunchedEffect(input) { c.settings.powerBudget = budgetJson.encodeToString(PowerModel.BudgetInput.serializer(), input) }
    val budget = PowerModel.budget(input)

    Scaffold(topBar = { BackTopBar2("Power", onBack) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text(estimate?.text ?: "Reading battery…", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    estimate?.drainPerHour?.let { Text("Recent drain: %.2f %% per hour".format(it), style = MaterialTheme.typography.bodySmall) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Survival mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Red-on-black theme, lowest refresh rate, downloads and indexing paused, AI unloaded when you leave the Assistant, map loads on tap, samples every 3 h.",
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(survival, { v -> survival = v; c.settings.survivalMode = v; scope.launch { if (v) PowerMonitor.enterSurvival(ctx) else PowerMonitor.exitSurvival(ctx) } })
            }
            Text("Measured idle drain: off %s · on %s".format(modes[false]?.let { "%.2f %%/h".format(it) } ?: "not yet", modes[true]?.let { "%.2f %%/h".format(it) } ?: "not yet"),
                style = MaterialTheme.typography.bodySmall)

            SectionTitle("Daily power plan")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Solar panel", solar, { solar = it }, Modifier.weight(1f), suffix = "W")
                NumberField("Sun hours", sun, { sun = it }, Modifier.weight(1f), suffix = "h")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Power bank", bank, { bank = it }, Modifier.weight(1f), suffix = "Wh")
                NumberField("Car charging", car, { car = it }, Modifier.weight(1f), suffix = "h/day")
            }
            Text("Power bank Wh = mAh × 3.7 ÷ 1000 (a 20,000 mAh bank ≈ 74 Wh).", style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Screen on", screen, { screen = it }, Modifier.weight(1f), suffix = "h/day")
                NumberField("AI answers", ai, { ai = it }, Modifier.weight(1f), decimal = false, suffix = "/day")
                NumberField("Map", map, { map = it }, Modifier.weight(1f), suffix = "min")
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(budget.daysUntilEmpty?.let { "Lasts about %.1f days".format(it) } ?: "Charging keeps up",
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Use %.1f Wh/day · in %.1f Wh/day".format(budget.useWhPerDay, budget.inWhPerDay), style = MaterialTheme.typography.bodySmall)
                    budget.plan.forEach { Text("• $it") }
                }
            }

            SectionTitle("Assistant battery cost")
            val measured = c.settings.aiPercentPerAnswer
            Text(
                when {
                    measured == null -> "Not measured yet. Run the Benchmark with a model installed (unplugged) to measure it; using an estimate of %.1f%% per answer.".format(aiCost)
                    c.settings.aiCostMeasured -> "Measured: about %.2f%% of the battery per answer (≈ 200 words).".format(measured)
                    else -> "Estimated from speed: about %.2f%% of the battery per answer (the charge counter wasn't available).".format(measured)
                },
            )
            OutlinedButton(onClick = onBenchmark) { Text("Open Benchmark") }

            SectionTitle("Wakelock & alarm audit")
            audit.forEach { (k, v) ->
                Column { Text(k, fontWeight = FontWeight.Medium); Text(v, style = MaterialTheme.typography.bodySmall) }
                HorizontalDivider()
            }
            OutlinedButton(onClick = {
                runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text("Android battery optimization settings") }
        }
    }
}

@Composable
private fun BackTopBar2(title: String, onBack: () -> Unit) = com.conza.outpost.ui.common.BackTopBar(title, onBack, subtitle = "Battery life, solar, and what uses power")

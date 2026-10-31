package com.conza.outpost.ui.griddown

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.conza.outpost.container
import com.conza.outpost.data.PressureSample
import com.conza.outpost.griddown.PowerMonitor
import com.conza.outpost.griddown.Pressure
import com.conza.outpost.griddown.SunMoon
import com.conza.outpost.ui.common.BackTopBar
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun WeatherScreen(onBack: () -> Unit, onHousehold: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val since = remember { System.currentTimeMillis() - 24 * 3_600_000L }
    val samples by remember { c.db.gridDown().observePressure(since) }.collectAsState(initial = emptyList())
    var live by remember { mutableStateOf<Float?>(null) }
    val hasBaro = remember { PowerMonitor.hasBarometer(ctx) }

    LaunchedEffect(Unit) {
        live = PowerMonitor.readPressure(ctx)
        // Log a reading when the screen opens so trends build up faster.
        live?.let { c.db.gridDown().addPressure(PressureSample(System.currentTimeMillis(), it)) }
    }

    Scaffold(topBar = { BackTopBar("Weather & sky", onBack, subtitle = "From the phone's barometer and the calendar") }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Barometer")
            if (!hasBaro) Text("This phone has no pressure sensor. The Pixel 8 does; most emulators (BlueStacks) don't.")
            else {
                val (trend, rate) = Pressure.trend(samples.map { Pressure.Reading(it.at, it.hPa) }, System.currentTimeMillis())
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(live?.let { "%.1f hPa".format(it) } ?: "Reading…", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(trend.label + (rate?.let { " (%+.1f hPa / 3 h)".format(it) } ?: ""), style = MaterialTheme.typography.titleMedium)
                        Text(trend.advice)
                    }
                }
                if (samples.size >= 2) {
                    val color = MaterialTheme.colorScheme.primary
                    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                        val minT = samples.first().at.toFloat(); val maxT = samples.last().at.toFloat().coerceAtLeast(minT + 1)
                        val minP = samples.minOf { it.hPa } - 1; val maxP = samples.maxOf { it.hPa } + 1
                        val pts = samples.map { Offset((it.at - minT) / (maxT - minT) * size.width, size.height - (it.hPa - minP) / (maxP - minP) * size.height) }
                        pts.zipWithNext().forEach { (a, b) -> drawLine(color, a, b, strokeWidth = 4f) }
                    }
                    Text("Last 24 hours · ${samples.size} readings · logged hourly (every 3 h in Survival mode)", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(onClick = { scope.launch { live = PowerMonitor.readPressure(ctx); live?.let { c.db.gridDown().addPressure(PressureSample(System.currentTimeMillis(), it)) } } }) {
                    Text("Read now")
                }
            }

            SectionTitle("Sun & moon")
            val lat = c.settings.homeLat; val lon = c.settings.homeLon
            val today = LocalDate.now()
            val offset = ZoneId.systemDefault().rules.getOffset(java.time.Instant.now()).totalSeconds / 3600.0
            if (lat == null || lon == null) {
                Text("Set your home location in Household to see sunrise and sunset.")
                OutlinedButton(onClick = onHousehold) { Text("Set location") }
            } else {
                (0..2).forEach { d ->
                    val date = today.plusDays(d.toLong())
                    val rise = SunMoon.format(SunMoon.sunEvent(date, lat, lon, offset, true))
                    val set = SunMoon.format(SunMoon.sunEvent(date, lat, lon, offset, false))
                    Row {
                        Text(if (d == 0) "Today" else date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        Text("Sunrise $rise · Sunset $set")
                    }
                }
                Text("Plan solar charging for the middle of the day; daylight is your free light for chores.", style = MaterialTheme.typography.bodySmall)
            }
            val moon = SunMoon.moon(System.currentTimeMillis())
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(moon.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("%.0f%% lit · day %.0f of the lunar cycle".format(moon.illumination * 100, moon.ageDays))
                    Text(if (moon.illumination > 0.6) "Bright nights: easier to move without lights, and easier to be seen." else "Dark nights: use red light and plan for less visibility.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

package com.conza.outpost.ui.food

import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.conza.outpost.food.*
import kotlinx.coroutines.delay
import java.time.LocalDate

@Composable
internal fun FoodSources(content: FoodContent, ids: List<String>) {
    val uri = LocalUriHandler.current
    ids.distinct().forEach { id ->
        val source = content.sources.first { it.id == id }
        Text("${source.title} · verified ${source.verified}", style = MaterialTheme.typography.labelSmall)
        Text(source.url, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { runCatching { uri.openUri(source.url) } }) { Text("Open source when connected") }
    }
}

@Composable
internal fun RecipeDetails(recipe: CannedFoodRecipe, content: FoodContent, start: (() -> Unit)? = null) {
    var expanded by remember(recipe.id) { mutableStateOf(false) }
    FoodCard(recipe.food) {
        Text("${recipe.category} · ${recipe.method.replace('_', ' ')} · ${recipe.pack}")
        Text(if (recipe.verified) "Verified preparation · ${recipe.version}" else "No verified home-canning procedure available.", color = if (recipe.verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Collapse instructions" else "Read offline instructions") }
        if (expanded) {
            recipe.preparation.forEachIndexed { i, step -> Text("${i + 1}. $step") }
            Text("Peeling: ${recipe.peeling}")
            Text("Precooking: ${recipe.precook}")
            Text("Headspace: ${recipe.headspace}")
            Text("Liquid: ${recipe.liquid}")
            Text("Salt / acid: ${recipe.saltAcid}")
            recipe.schedules.forEach { Text("${it.jar}: ${it.minutes.joinToString(" / ")} minutes") }
            if (recipe.method == "PRESSURE") {
                Text("Dial gauge: ${recipe.dial.joinToString { "through ${it.maxFeet} ft: ${it.psi} psi" }}")
                Text("Weighted gauge: ${recipe.weighted.joinToString { "through ${it.maxFeet} ft: ${it.psi} psi" }}")
            } else if (recipe.verified) Text("Water-bath elevation bands, in the same order as times: ${recipe.waterBathBands.joinToString { "through $it ft" }}. The wizard selects the exact band.")
            recipe.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(if (recipe.method == "PRESSURE") "Cool naturally to zero pressure; never force-cool. After depressurizing and removing the weight/opening the petcock, wait 10 minutes before opening the lid away from you. Follow the canner maker's directions." else "Maintain full boiling throughout. After processing, turn off heat, remove the lid, and wait 5 minutes before removing jars.")
            Text("Cool upright undisturbed 12–24 hours. Do not retighten lids. Remove bands and inspect the seals after cooling. An intact seal cannot establish that an incorrect process was safe. Label and keep properly processed jars in a dark, dry, cool place, ideally 50–70°F, avoiding above 95°F. Plan rotation within one year for quality; this is not a safety expiration.")
            FoodSources(content, recipe.sources + listOf("home-storage", if (recipe.method == "PRESSURE") "pressure" else "bath"))
        }
        if (start != null && recipe.verified) Button(onClick = start) { Text("Set up this exact recipe") }
    }
}

internal fun batchForm(repo: FoodRepository, state: FoodState, recipe: CannedFoodRecipe, opened: (String) -> Unit) = FoodFormSpec("Set up ${recipe.food}",
    "Only the listed preparation and jar sizes are supported. Enter your actual elevation; Tampa is not assumed. This creates a draft batch, not edible inventory. Read every recipe step before starting.",
    listOf(choice("jar", "Supported jar size", recipe.schedules.first().jar, recipe.schedules.map { it.jar to it.jar }),
        choice("gauge", "Gauge type (ignored for water bath)", "DIAL", listOf("DIAL" to "Dial gauge", "WEIGHTED" to "Weighted gauge")),
        input("elevation", "Elevation in feet", "", true), input("jars", "Number of jars", 1, true), input("date", "Processing date YYYY-MM-DD", LocalDate.now()),
        choice("location", "Storage location", "pantry", listOf("" to "Not set") + state.locations.map { it.id to it.name }),
        choice("harvest", "Link a harvest (optional)", "", listOf("" to "No harvest link") + state.harvests.map { it.id to "${it.food} · ${it.date} · ${amount(it.pounds)} lb" }), input("notes", "Notes / canner model")),
) { v ->
    val process = CanningRules.process(recipe, v.text("jar"), v.text("gauge"), v.integer("elevation"))
    val batch = CanningBatch(recipeId = recipe.id, recipeVersion = recipe.version, food = recipe.food, date = v.text("date"), jar = v.text("jar"), jars = v.integer("jars"),
        method = recipe.method, gauge = if (recipe.method == "PRESSURE") v.text("gauge") else "NONE", elevationFeet = v.integer("elevation"), pressurePsi = process.psi, processingMinutes = process.minutes,
        locationId = v.opt("location"), harvestId = v.opt("harvest"), notes = v.text("notes"), rotationDate = PantryMath.rotation(v.text("date"), 12), recipeSnapshot = recipe)
    repo.saveBatch(batch); opened(batch.id)
}

@Composable
internal fun BatchControls(repo: FoodRepository, batch: CanningBatch, run: (suspend () -> Unit) -> Unit, onEdit: (FoodFormSpec) -> Unit) {
    val context = LocalContext.current
    val boot = remember { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).toLong() }
    val elapsed by produceState(initialValue = SystemClock.elapsedRealtime(), batch.id) { while (true) { value = SystemClock.elapsedRealtime(); delay(1000) } }
    val remaining = FoodTimers.remaining(batch, elapsed, boot)
    val unknownTimer = batch.timerElapsedEnds != null && remaining == null
    var confirm by remember(batch.id, batch.stage) { mutableStateOf(false) }
    var seal by remember(batch.id, batch.stage) { mutableStateOf("SEALED") }
    val checks = remember(batch.id, batch.stage) { mutableStateMapOf<Int, Boolean>() }
    FoodCard("${batch.food} · ${batch.id.take(8)}") {
        Text("${batch.jars} × ${batch.jar} · ${batch.date} · ${batch.method.replace('_', ' ')}")
        Text("${batch.processingMinutes} min${batch.pressurePsi?.let { " · $it psi · ${batch.gauge}" }.orEmpty()} · ${batch.elevationFeet} ft")
        Text("Stage: ${batch.stage.replace('_', ' ')} · seal: ${batch.seal} · full restarts: ${batch.restarts}", style = MaterialTheme.typography.titleSmall)
        Text("Outpost does not measure pressure, temperature, venting or seals. Stay with the canner and use an independent timer. Background reminders can be delayed or disabled by Android.", color = MaterialTheme.colorScheme.error)
        if (unknownTimer) Text("Device restarted or timer provenance changed. The timer cannot establish processing. Flag an interruption and review the batch; never assume the food is safe.", color = MaterialTheme.colorScheme.error)
        if (remaining != null) Text("${remaining / 3_600_000}h ${(remaining / 60_000) % 60}m ${(remaining / 1000) % 60}s remaining", style = MaterialTheme.typography.headlineSmall)
        if (batch.stage == "PREPARE") {
            val preflight = listOf(
                "I read and will follow the complete exact recipe, jar size, preparation, headspace and liquid / acid requirements below.",
                if (batch.method == "PRESSURE") "My pressure canner holds at least four upright quart jars, has a rack and working vent; the gauge is checked as required. This is not a small pressure cooker or electric multicooker." else "My boiling-water canner has a rack and room for water at least 1 inch above jars (2 inches for processes longer than 30 minutes).",
                "Jars are sound, correct lids are prepared to manufacturer directions, bands are fingertip tight, and food is prepared, packed and loaded as specified.",
                "Elevation, gauge and schedule above are correct. I have safe heat, water, a jar lifter and an independent timer; I will not leave the canner unattended.")
            preflight.forEachIndexed { i, line -> Row { Checkbox(checks[i] == true, { checks[i] = it }); Text(line, Modifier.padding(top = 10.dp)) } }
        }
        val instruction = when (batch.stage) {
            "PREPARE" -> if (batch.method == "PRESSURE") "Follow the canner maker's water amount (usually 2–3 inches for these standard pressure processes; the recipe may differ). Heat loaded canner with vent open. Start vent timer only once a strong continuous steam funnel escapes." else "Cover jars with required water. Bring to a vigorous boil. The next step requires confirmation of full boiling."
            "VENT" -> "Vent a continuous strong funnel of steam for the full 10 minutes before closing the vent/applying the weight. If steam was not continuous, flag interruption and restart the vent after reviewing the batch."
            "REACH_PROCESS" -> if (batch.method == "PRESSURE") "Close the vent/apply the weight after venting; bring the gauge to at least ${batch.pressurePsi} psi. Start the full ${batch.processingMinutes} minutes only at the required pressure. Maintain it throughout." else "Start the full ${batch.processingMinutes} minutes only at a vigorous boil with the required water coverage. Maintain boiling throughout."
            "PROCESS" -> if (batch.method == "PRESSURE") "Maintain at least ${batch.pressurePsi} psi for the entire ${batch.processingMinutes} minutes. If pressure falls below this, regain pressure and restart the FULL process time. The app cannot detect a pressure drop." else "Keep a vigorous boil throughout. If boiling stops, return to a full boil and restart the FULL process time. Add boiling water as needed to keep jars covered."
            "NATURAL_COOL" -> "Turn off heat. Let the canner cool naturally and depressurize fully. Never force-cool, run water over it, move a hot loaded canner, or release pressure early. Once fully depressurized, remove the weight/open the petcock as the maker directs, then start the 10-minute wait."
            "WAIT_REMOVE" -> "After the wait, open the lid away from your face. Lift jars upright with the lifter to a towel or rack with space between jars. Never retighten bands. Start the 12-hour minimum cooling reminder after removing jars."
            "SEAL_WAIT" -> "Cool upright undisturbed at room temperature 12–24 hours. Do not tilt jars or tighten bands. The reminder is a minimum cooling interval, not proof of a safe process."
            "CHECK_SEALS" -> "After 12–24 hours, remove screw bands and inspect seals: the lid should be concave and not spring up when pressed. Check leakage and jar integrity. Record SEALED only if the entire process was correct and every jar in this batch passed. If any failed, hold the whole batch here and separate it before using food. An intact seal alone never validates unsafe processing."
            "INTERRUPTED" -> "Batch is held. If pressure dropped during processing, regain the required pressure and restart the entire process time. If boiling stopped, return to a boil and restart the entire time. If venting was incomplete, abandon this draft and prepare a new draft to re-vent. If time, temperature, cooling or processing history is uncertain, mark compromised; do not guess or use the restart button as a rescue procedure."
            else -> "Record complete. Proper processing, jar integrity and food condition still matter. Never taste suspect food. Rotation ${batch.rotationDate} is a quality target, not a safety guarantee."
        }
        Text(instruction)
        if (batch.stage == "CHECK_SEALS") FoodSelect("Inspection result for all jars in this record", seal,
            listOf("SEALED" to "Process reviewed; all seals passed", "FAILED" to "One or more failed seals — hold / discard", "COMPROMISED" to "Compromised / discard")) { seal = it }
        if (batch.stage !in setOf("COMPLETE", "PREPARE")) Row { Checkbox(confirm, { confirm = it }); Text("I performed and checked the step above.", Modifier.padding(top = 12.dp)) }
        if (batch.stage != "COMPLETE") {
            val ready = if (batch.stage == "PREPARE") (0..3).all { checks[it] == true } else confirm
            val timed = batch.timerElapsedEnds != null
            Button(enabled = ready && !unknownTimer && (!timed || remaining == 0L) && boot >= 0 && (batch.stage != "INTERRUPTED" || batch.interruptionStage == "PROCESS"), onClick = {
                run {
                    val input = batch.copy(preflightComplete = batch.preflightComplete || batch.stage == "PREPARE", pressureConfirmed = batch.pressureConfirmed || batch.stage == "REACH_PROCESS")
                    var next = CanningWorkflow.advance(input, SystemClock.elapsedRealtime(), boot, System.currentTimeMillis())
                    if (batch.stage == "CHECK_SEALS") next = next.copy(seal = seal)
                    repo.saveBatch(next); FoodTimerWorker.schedule(context, next)
                }
            }) { Text(when (batch.stage) { "PREPARE" -> if (batch.method == "PRESSURE") "Start continuous steam vent: 10 min" else "Continue to boiling confirmation"; "REACH_PROCESS" -> "Start full processing timer"; "NATURAL_COOL" -> "Fully depressurized: start 10-minute wait"; "WAIT_REMOVE" -> "Jars removed: start cooling reminder"; "CHECK_SEALS" -> "Save batch inspection"; "INTERRUPTED" -> "Review complete: return to processing confirmation"; else -> "Confirm step and continue" }) }
        }
        if (batch.stage == "PROCESS" && !unknownTimer) OutlinedButton(onClick = { run {
            val next = FoodTimers.interrupted(batch); repo.saveBatch(next); FoodTimerWorker.schedule(context, next)
        } }) { Text("Observed pressure / boil drop — restart full time") }
        if (batch.stage != "PREPARE" && batch.stage != "COMPLETE") OutlinedButton(onClick = { run {
            val next = FoodTimers.interrupted(batch).copy(interruptionStage = "UNCERTAIN"); repo.saveBatch(next); FoodTimerWorker.schedule(context, next)
        } }) { Text("History / venting / cooling uncertain — HOLD") }
        OutlinedButton(onClick = { run { val next = batch.copy(seal = "COMPROMISED", stage = "COMPLETE", timerEndsAt = null, timerElapsedEnds = null, timerBoot = null); repo.saveBatch(next); FoodTimerWorker.schedule(context, next) } }) { Text("Flag compromised / discard") }
        OutlinedButton(onClick = { onEdit(FoodFormSpec("Batch notes", "Keep processing observations, canner model, failed seals and handling history here.", listOf(input("notes", "Notes", batch.notes))) { v -> repo.saveBatch(batch.copy(notes = v.text("notes"))) }) }) { Text("Edit notes") }
        Button(onClick = { run { repo.addBatchToPantry(batch.id) } }) { Text("Record jars in pantry (held until reviewed)") }
    }
    RecipeDetails(batch.recipeSnapshot ?: repo.content.recipes.single { it.id == batch.recipeId }, repo.content)
}

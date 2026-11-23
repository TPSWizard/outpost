package com.conza.outpost.ui.food

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.conza.outpost.container
import com.conza.outpost.data.InventoryItem
import com.conza.outpost.food.*
import com.conza.outpost.ui.common.BackTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private val sections = listOf("Dashboard", "My pantry", "Canned foods", "Bulk dry storage", "Pressure canning", "Canning recipes", "Food shelf life", "Meal planner", "Garden & harvest", "Shopping lists", "Storage locations", "Safety reference", "Backup & export")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodScreen(onBack: () -> Unit, onFullBackup: () -> Unit) {
    val context = LocalContext.current
    val repo = context.container.food
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var snapshot by remember { mutableStateOf<FoodSnapshot?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var section by rememberSaveable { mutableStateOf("Dashboard") }
    var search by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var days by rememberSaveable { mutableStateOf(30) }
    var activeBatch by rememberSaveable { mutableStateOf<String?>(null) }
    var form by remember { mutableStateOf<FoodFormSpec?>(null) }
    var exportKind by rememberSaveable { mutableStateOf("json") }
    val listState = rememberLazyListState()
    LaunchedEffect(section, activeBatch) { listState.scrollToItem(0) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        scope.launch { snackbar.showSnackbar(if (allowed) "Food timer reminders enabled" else "Reminders are disabled; use an independent timer") }
    }
    val run: (suspend () -> Unit) -> Unit = { block -> scope.launch {
        try { withContext(Dispatchers.IO) { block() } }
        catch (e: Exception) { if (e is CancellationException) throw e; snackbar.showSnackbar(e.message ?: "Operation failed") }
    }; Unit }
    LaunchedEffect(repo) { repo.observe().flowOn(Dispatchers.IO).catch { loadError = "Pantry could not load: ${it.message}. Your saved data has not been reset. Use a full backup before troubleshooting." }.collect { snapshot = it } }
    BackHandler(section != "Dashboard" || activeBatch != null) { if (activeBatch != null) activeBatch = null else section = "Dashboard" }
    form?.let { FoodForm(it) { form = null } }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> if (uri != null) run {
        val text = when (exportKind) {
            "csv" -> FoodBackups.csv(repo.snapshot())
            "reference" -> referenceSheet(repo.content)
            "before" -> java.io.File(context.filesDir, "pantry-before-import.json").takeIf { it.exists() }?.readText() ?: error("No pre-import snapshot exists yet")
            else -> repo.export()
        }
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) } ?: error("Could not open export destination")
        withContext(Dispatchers.Main) { snackbar.showSnackbar("Export saved on this device") }
    } }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) run {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= 20_000_000) { "Backup exceeds 20 MB" }; out.write(buffer, 0, n) }
            out.toString("UTF-8")
        } ?: error("Could not read backup")
        repo.import(text)
        withContext(Dispatchers.Main) { snackbar.showSnackbar("Missing records imported; existing records kept") }
    } }

    Scaffold(topBar = { BackTopBar("Food & Water", { if (activeBatch != null) activeBatch = null else if (section != "Dashboard") section = "Dashboard" else onBack() }, subtitle = section) }, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                sections.forEach { tab -> FilterChip(section == tab, { section = tab; search = ""; category = ""; activeBatch = null }, label = { Text(tab) }) }
            }
            val s = snapshot
            if (s == null) {
                Text(loadError ?: "Loading the offline pantry…", Modifier.padding(16.dp))
                if (loadError != null) Button(onClick = onFullBackup) { Text("Open full backup") }
                return@Column
            }
            val state = s.state; val content = repo.content
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (section) {
                    "Dashboard" -> {
                        item { FoodCard("${state.profile.name} · ${state.profile.people} people") {
                            FoodSelect("Planning profile", state.activeProfileId, state.profiles.map { it.id to "${it.name} · ${it.people} people" }) { id -> run { repo.change { it.copy(activeProfileId = id) } } }
                            Text("${state.profile.location} · ${state.profile.environment}")
                            Text("${state.profile.calories} kcal + ${amount(state.profile.waterGallons)} US gal / person / day. ${state.profile.days}-day initial / ${state.profile.extendedDays}-day extended plan.")
                            Text(if (state.profile.refrigeration) "Planning for reliable refrigeration." else "Planning without refrigeration: choose portions that can be eaten immediately after opening.")
                            Text(if (state.profile.electricCooking) "Electric cooking included in this scenario." else "Electric cooking unavailable in this scenario.")
                            if (state.profile.dietaryNotes.isNotBlank()) Text(state.profile.dietaryNotes)
                            OutlinedButton(onClick = { form = profileForm(repo, state.profile) }) { Text("Edit assumptions") }
                            TextButton(onClick = { form = profileForm(repo, state.profile.copy(name = "Custom household"), true) }) { Text("Add profile") }
                            Text("Food profiles are planning scenarios; your existing Household members and Supplies stay connected through the same inventory.", style = MaterialTheme.typography.bodySmall)
                        } }
                        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            (listOf(7, 30, 60, 90, state.profile.days, state.profile.extendedDays).distinct()).forEach { d -> FilterChip(days == d, { days = d }, label = { Text("$d days") }) }
                        } }
                        val coverage = PantryMath.coverage(state, s.supplies, days)
                        item { FoodCard("$days-day coverage") {
                            Text("${amount(coverage.requiredCalories)} kcal planned · ${amount(coverage.storedCalories)} recorded usable kcal · ${amount(coverage.calorieShortfall)} kcal remaining")
                            Text("${amount(coverage.foodDays)} household food days (${amount(coverage.foodDays * state.profile.people)} person-days)", style = MaterialTheme.typography.titleLarge)
                            Text("Commercial cans ${amount(coverage.cannedDays)} d · dry/bulk ${amount(coverage.dryDays)} d · no-cook ${amount(coverage.noCookDays)} d")
                            Text("Protein ${amount(coverage.proteinGrams)} g · ${amount(coverage.proteinDays)} household days at the editable protein target")
                            Text("Water ${amount(coverage.waterGallons)} / ${amount(coverage.requiredWaterGallons)} US gal · ${amount(coverage.waterDays)} days")
                            Text("Coverage is incomplete when label nutrition or water volume is missing. ${coverage.unknownNutrition} food lots need calorie provenance; ${coverage.unknownProtein} need protein data. ${coverage.heldLots} opened, held, unknown or compromised lots excluded.", color = MaterialTheme.colorScheme.error)
                            Text("Hot weather, illness, pregnancy and extra sanitation can increase water needs. Calories alone do not establish a balanced diet. The 324-can template is not a complete calorie plan.")
                            Text("${s.supplies.count { it.category == "WATER" && it.litersPerUnit == null && runCatching { PantryMath.liters(1.0, it.unit) }.isFailure }} water lots have no recognized volume; verify them in My pantry.")
                            Button(onClick = { section = "My pantry" }) { Text("Manage food and water") }
                        } }
                        item { FoodCard("Shortages by category") {
                            content.products.groupBy { it.category }.forEach { (name, products) ->
                                val targets = state.targets.filter { t -> products.any { it.id == t.productId } }
                                val short = targets.count { PantryMath.needed(it, state, s.supplies) > 0 }
                                if (targets.isNotEmpty()) Text("$name: $short / ${targets.size} targets below desired stock")
                            }
                            Text("Targets compare usable units within each food. Cans, pounds and gallons are not added together as nutrition.", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { section = "Shopping lists" }) { Text("Replenishment list") }
                        } }
                        item { FoodCard("Rotation and storage") {
                            val warnings = state.lots.filter { lot -> s.supplies.any { it.uuid == lot.inventoryUuid && it.quantity > 0 } && (lot.safety != "INSPECT" || lot.openedDate != null || lot.rotationDate?.let { LocalDate.parse(it) <= LocalDate.now().plusDays(30) } == true) }
                            Text("${warnings.size} lots need rotation or condition review")
                            warnings.take(8).forEach { lot -> Text("${s.supplies.firstOrNull { it.uuid == lot.inventoryUuid }?.name}: ${PantryMath.quality(lot)}") }
                            Text("Shelf space recorded: ${amount(state.locations.sumOf { PantryMath.storageUsed(it.id, state, s.supplies) })} L. ${state.lots.count { it.volumeLitersPerUnit == null }} lots have unknown space requirements.")
                            TextButton(onClick = { section = "Storage locations" }) { Text("Inspect storage locations") }
                        } }
                        item { GuideCard(content.guides.first { it.id == "offline" }, content) }
                    }
                    "My pantry" -> {
                        item { Text("Actual stock, shared with Supplies. FIFO order uses purchase date, then rotation date. Nutrition and best-by dates are entered from your product; quality status is separate from safety.") }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { form = lotForm(repo, state) }) { Text("Add food") }
                            OutlinedButton(onClick = { form = lotForm(repo, state, water = true) }) { Text("Add water") }
                        } }
                        item { CatalogFilter(search, { search = it }, category, { category = it }, state.lots.map { it.foodCategory }.filter { it.isNotBlank() }.distinct()) }
                        val sorted = s.supplies.filter { row -> row.name.contains(search, true) && (category.isEmpty() || state.lots.firstOrNull { it.inventoryUuid == row.uuid }?.foodCategory == category) }.sortedWith(compareBy<InventoryItem> { row -> state.lots.firstOrNull { it.inventoryUuid == row.uuid }?.purchaseDate ?: "9999" }.thenBy { row -> state.lots.firstOrNull { it.inventoryUuid == row.uuid }?.rotationDate ?: "9999" })
                        if (sorted.isEmpty()) item { Text("No owned stock matches. Shopping templates do not add stock.") }
                        items(sorted, key = { it.uuid }) { row ->
                            val lot = state.lots.firstOrNull { it.inventoryUuid == row.uuid }
                            FoodCard(row.name) {
                                Text("${amount(row.quantity)} ${row.unit} · ${lot?.kind ?: row.category} · ${state.locations.firstOrNull { it.id == lot?.locationId }?.name ?: "No location"}", style = MaterialTheme.typography.titleLarge)
                                Text("${row.caloriesPerUnit?.let { "${amount(it)} kcal/${row.unit}" } ?: "Calories unknown"} · ${lot?.proteinPerUnit?.let { "${amount(it)} g protein/${row.unit}" } ?: "Protein unknown"}")
                                Text("Label source: ${lot?.nutritionSource?.ifBlank { "Unverified / needs product label" } ?: "Unverified / needs product label"}")
                                Text(PantryMath.quality(lot), color = if (lot?.safety in setOf("HELD", "UNKNOWN", "COMPROMISED")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                                Text("Purchased/packed ${lot?.purchaseDate ?: "unknown"} · manufacturer best-by ${row.expiry ?: "unknown"} · quality rotation ${lot?.rotationDate ?: "unknown"}", style = MaterialTheme.typography.bodySmall)
                                if (lot?.batchId != null) TextButton(onClick = { activeBatch = lot.batchId; section = "Pressure canning" }) { Text("View processing batch") }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(onClick = { form = lotForm(repo, state, row) }) { Text("Edit / condition") }
                                    OutlinedButton(enabled = row.quantity > 0, onClick = { form = FoodFormSpec("Use / waste ${row.name}", "Subtracts from the same Supplies quantity. Opened food without reliable refrigeration should be used promptly; discard if safe handling cannot be assured.",
                                        listOf(input("amount", "Amount in ${row.unit}", minOf(1.0, row.quantity), true), choice("reason", "Reason", "Consumed", listOf("Consumed" to "Consumed", "Waste / spoilage" to "Waste / spoilage", "Damaged / discarded" to "Damaged / discarded")))) { v -> repo.consume(row.uuid, v.number("amount"), v.text("reason")) } }) { Text("Use / waste") }
                                }
                                TextButton(onClick = { form = FoodFormSpec("Remove ${row.name}?", "This removes the shared Supplies row and its lot metadata. Export a backup first if needed.", listOf(toggle("confirm", "Remove this lot", false))) { v -> require(v.flag("confirm")); repo.remove(row.uuid) } }) { Text("Remove lot") }
                            }
                        }
                        item { FoodCard("Recent inventory events") { state.events.takeLast(20).reversed().forEach { event -> Text("${s.supplies.firstOrNull { it.uuid == event.inventoryUuid }?.name ?: event.inventoryUuid.take(8)}: ${amount(event.change)} · ${event.reason}") } } }
                    }
                    "Canned foods", "Bulk dry storage" -> {
                        val kind = if (section == "Canned foods") "COMMERCIAL" else "DRY"
                        val products = content.products.filter { it.kind == kind || (kind == "DRY" && it.kind == "READY") }
                        item { Text(if (kind == "COMMERCIAL") "324 cans are the initial 7-person shopping target. No stock is preloaded. Use real package labels for calories and protein. Manufacturer dates override a generic quality planning window." else "Starter dry-goods targets are editable. White rice and brown rice have different oil content and storage behavior. Avoid moisture in oxygen-reduced packaging.") }
                        item { CatalogFilter(search, { search = it }, category, { category = it }, products.map { it.category }.distinct()) }
                        items(products.filter { it.name.contains(search, true) && (category.isEmpty() || it.category == category) }, key = { it.id }) { product ->
                            FoodCard(product.name) {
                                Text("${product.size} · unit ${product.unit} · ${product.category}")
                                Text("Owned usable ${amount(PantryMath.owned(product.id, state, s.supplies))} ${product.unit}. ${product.portion}")
                                Text(product.nutrition)
                                Text("${if (product.noCook) "No-cook" else "Cooking required / follow product directions"}${if (product.waterNeeded) " · preparation water needed" else ""}${product.cookingMinutes?.let { " · roughly $it cooking min, product directions govern" }.orEmpty()}")
                                val advice = content.storage.single { it.id == product.storageId }
                                StorageCardBody(advice, content)
                                Button(onClick = { form = lotForm(repo, state, product = product) }) { Text("Record owned / purchased") }
                                TextButton(onClick = { form = targetForm(repo, product, state.targets.firstOrNull { it.productId == product.id }) }) { Text("Edit shopping target") }
                            }
                        }
                    }
                    "Pressure canning" -> {
                        val batch = state.batches.firstOrNull { it.id == activeBatch }
                        if (batch != null) {
                            item { TextButton(onClick = { activeBatch = null }) { Text("All batches") } }
                            item { BatchControls(repo, batch, run) { form = it } }
                        } else {
                            item { Text("Pressure canning & food preservation. Every draft keeps its recipe version and exact schedule. Jars remain held until processing and seal review. Timers never measure canner pressure.") }
                            item { Button(onClick = { section = "Canning recipes" }) { Text("Choose a verified recipe") } }
                            if (android.os.Build.VERSION.SDK_INT >= 33) item { TextButton(onClick = { notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow optional timer reminders") } }
                            items(state.batches.reversed(), key = { it.id }) { b -> FoodCard("${b.food} · ${b.date}") {
                                Text("${b.jars} × ${b.jar} · ${b.stage.replace('_', ' ')} · ${b.seal}")
                                if (b.interrupted) Text("Processing interrupted — held", color = MaterialTheme.colorScheme.error)
                                Button(onClick = { activeBatch = b.id }) { Text("Open workflow / record") }
                            } }
                            content.guides.filter { it.section == "Canning" }.forEach { g -> item(g.id) { GuideCard(g, content) } }
                        }
                    }
                    "Canning recipes" -> {
                        item { CatalogFilter(search, { search = it }, category, { category = it }, content.recipes.map { it.category }.distinct()) }
                        items(content.recipes.filter { (it.food + " " + it.pack).contains(search, true) && (category.isEmpty() || category == it.category) }, key = { it.id }) { recipe ->
                            RecipeDetails(recipe, content, if (recipe.verified) ({ form = batchForm(repo, state, recipe) { id -> activeBatch = id; section = "Pressure canning" } }) else null)
                        }
                    }
                    "Food shelf life" -> {
                        content.guides.filter { it.section == "Shelf life" }.forEach { g -> item(g.id) { GuideCard(g, content) } }
                        items(content.storage, key = { it.id }) { advice -> FoodCard(advice.title) { StorageCardBody(advice, content, true) } }
                    }
                    "Meal planner" -> {
                        item { GuideCard(content.guides.first { it.id == "menu-guide" }, content) }
                        val meals = content.meals.map { meal -> state.customMeals.firstOrNull { it.id == meal.id } ?: meal }
                        (1..7).forEach { day ->
                            val daily = meals.filter { it.day == day }.map { MealMath.estimate(it, state.profile.people, state, s.supplies) }
                            item("day-$day") { Text("Day $day · ${amount(daily.sumOf { it.calories })} entered-label kcal for household${if (daily.any { it.incomplete }) " (incomplete)" else " (estimate)"}", style = MaterialTheme.typography.titleLarge) }
                            meals.filter { it.day == day }.forEach { meal -> item(meal.id) {
                                val est = MealMath.estimate(meal, state.profile.people, state, s.supplies)
                                FoodCard("${meal.type}: ${meal.title}") {
                                    Text(meal.ingredients.joinToString("\n") { ingredient -> val p = content.products.single { it.id == ingredient.productId }; "${amount(ingredient.unitsPerPerson * state.profile.people)} ${p.unit} ${p.name}" })
                                    Text(meal.instructions)
                                    Text("${amount(est.calories)} kcal · ${amount(est.protein)} g protein / household${if (est.incomplete) " · incomplete label data" else " · label-based estimate"}")
                                    Text("Planning: ${amount(est.waterLiters)} L cooking water · ${meal.burnerMinutes} household burner min · ${est.energyKwh?.let { "${amount(it)} kWh" } ?: "energy rate unknown"} · ${est.fuelGrams?.let { "${amount(it)} g fuel" } ?: "fuel rate unknown"}")
                                    TextButton(onClick = { form = mealForm(repo, meal) }) { Text("Edit portions / water / cooking") }
                                    OutlinedButton(onClick = { form = FoodFormSpec("Schedule ${meal.title}", "Scheduling does not change stock. Deduct when cooked/eaten; all ingredients must be available and deductions use FIFO atomically.", listOf(input("date", "Date YYYY-MM-DD", LocalDate.now()), input("people", "People", state.profile.people, true))) { v ->
                                        val plan = MealPlan(date = v.text("date"), mealId = meal.id, people = v.integer("people")); repo.change { it.copy(mealPlans = it.mealPlans + plan) }
                                    } }) { Text("Schedule meal") }
                                }
                            } }
                        }
                        item { Text("Scheduled meals", style = MaterialTheme.typography.titleLarge) }
                        items(state.mealPlans.sortedBy { it.date }, key = { it.id }) { plan -> FoodCard("${plan.date} · ${meals.firstOrNull { it.id == plan.mealId }?.title ?: plan.mealId}") {
                            Text("${plan.people} people · ${if (plan.consumed) "Ingredients already deducted" else "Not deducted"}")
                            if (!plan.consumed) Button(onClick = { form = FoodFormSpec("Deduct meal ingredients?", "Uses actual stock in FIFO order. No stock changes if any ingredient is missing. This records ingredients, not water or fuel; record those separately in Supplies.", listOf(toggle("confirm", "This meal has been prepared / consumed", false))) { v -> require(v.flag("confirm")); repo.consumeMeal(plan.id) } }) { Text("Consume and deduct") }
                        } }
                    }
                    "Garden & harvest" -> {
                        item { Button(onClick = { form = harvestForm(repo) }) { Text("Record harvest") } }
                        items(state.harvests.sortedByDescending { it.date }, key = { it.id }) { h -> FoodCard("${h.food} · ${h.date}") {
                            Text("${amount(h.pounds)} lb harvested · fresh ${amount(h.fresh)} · preserved ${amount(h.preserved)} · spoiled ${amount(h.spoiled)} · planting ${amount(h.planting)}")
                            Text("Unallocated ${amount(h.pounds - h.fresh - h.preserved - h.spoiled - h.planting)} lb")
                            Text(h.notes)
                            h.batchIds.forEach { id -> TextButton(onClick = { activeBatch = id; section = "Pressure canning" }) { Text("Preservation batch ${id.take(8)}") } }
                            OutlinedButton(onClick = { form = harvestForm(repo, h) }) { Text("Allocate / edit") }
                            TextButton(onClick = { section = "Canning recipes" }) { Text("Choose preservation method") }
                        } }
                        content.guides.filter { it.section == "Garden" }.forEach { g -> item(g.id) { GuideCard(g, content) } }
                    }
                    "Shopping lists" -> {
                        item { Text("Shopping targets are independent of owned stock. Needed regenerates from usable pantry lots. A checked Owned box means the target is covered; tap it to record actual stock. Purchased records new stock, and Use / waste in My pantry records consumption.") }
                        item { CatalogFilter(search, { search = it }, category, { category = it }, content.products.map { it.category }.distinct()) }
                        items(content.products.filter { it.name.contains(search, true) && (category.isEmpty() || category == it.category) }, key = { it.id }) { p ->
                            val target = state.targets.firstOrNull { it.productId == p.id } ?: PurchaseTarget(p.id, 0.0)
                            val need = PantryMath.needed(target, state, s.supplies); val cases = PantryMath.cases(need, target.caseSize)
                            FoodCard(p.name) {
                                Row { Checkbox(need == 0.0 && target.desired > 0, { form = lotForm(repo, state, product = p) }); Text("Owned ${amount(PantryMath.owned(p.id, state, s.supplies))} / ${amount(target.desired)} ${p.unit}", Modifier.padding(top = 12.dp)) }
                                Text("Need ${amount(need)} ${p.unit} · buy $cases cases of ${target.caseSize}${target.pricePerCase?.let { " · estimated ${amount(it * cases)}" }.orEmpty()}")
                                Text("Priority ${target.priority} · ${target.notes}")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { form = targetForm(repo, p, target) }) { Text("Edit target / price") }
                                    TextButton(onClick = { form = lotForm(repo, state, product = p) }) { Text("Purchased") }
                                }
                            }
                        }
                        item { Text("Equipment", style = MaterialTheme.typography.titleLarge); OutlinedButton(onClick = { form = equipmentForm(repo) }) { Text("Add equipment") } }
                        items(state.equipment, key = { "equipment-${it.id}" }) { e -> FoodCard(e.name) {
                            val need = (e.desired - e.owned).coerceAtLeast(0.0)
                            Row { Checkbox(need == 0.0, { checked -> run { repo.change { it.copy(equipment = it.equipment.map { old -> if (old.id == e.id) old.copy(owned = if (checked) old.desired else 0.0) else old }) } } }); Text("${amount(e.owned)} / ${amount(e.desired)} · need ${amount(need)}", Modifier.padding(top = 12.dp)) }
                            Text("${e.group} · ${e.priority} · ${e.location} · ${e.price?.let { "remaining estimate ${amount(it * need)}" } ?: "price unknown"}")
                            Text(e.notes)
                            TextButton(onClick = { form = equipmentForm(repo, e) }) { Text("Edit equipment") }
                        } }
                    }
                    "Storage locations" -> {
                        item { Button(onClick = { form = locationForm(repo) }) { Text("Add location") } }
                        items(state.locations, key = { it.id }) { location -> FoodCard(location.name) {
                            val used = PantryMath.storageUsed(location.id, state, s.supplies)
                            Text("${location.temperatureF?.let { "$it°F" } ?: "Temperature unknown"} · ${location.humidityPercent?.let { "$it% RH" } ?: "Humidity unknown"}")
                            Text("${amount(used)} L recorded${location.capacityLiters?.let { " / ${amount(it)} L capacity" }.orEmpty()}. Missing lot volumes make this incomplete.")
                            if (location.capacityLiters != null && used > location.capacityLiters) Text("Recorded stock exceeds capacity", color = MaterialTheme.colorScheme.error)
                            if ((location.temperatureF ?: 70.0) > 95) Text("Above 95°F: poor home-canned storage conditions", color = MaterialTheme.colorScheme.error)
                            if ((location.temperatureF ?: 70.0) <= 32) Text("Freezing can damage containers and seals", color = MaterialTheme.colorScheme.error)
                            Text("${if (location.sunlight) "Sunlight exposure" else "No direct sun recorded"} · ${if (location.pestProtected) "Pest protection recorded" else "Pest protection needed"}")
                            Text("Keep cool, dry and dark; monitor dampness, rust and insects. Home-canned quality storage is ideally 50–70°F; Tampa garages/sheds may be unsuitable.")
                            Text(location.notes)
                            TextButton(onClick = { form = locationForm(repo, location) }) { Text("Edit conditions / capacity") }
                        } }
                        item { GuideCard(content.guides.first { it.id == "storage-setup" }, content) }
                    }
                    "Safety reference" -> {
                        content.guides.filter { it.section in setOf("Safety", "Getting started") }.forEach { g -> item(g.id) { GuideCard(g, content) } }
                        item { FoodCard("Bundled source register") { Text("Instructions and source metadata above are already stored on your phone. Opening the original website is optional and requires a connection."); FoodSources(content, content.sources.map { it.id }) } }
                    }
                    "Backup & export" -> {
                        item { FoodCard("Offline data and reference exports") {
                            Text("Save to local storage, an SD card or a USB drive using Android's file picker. JSON includes food/water quantities, label metadata, profiles, locations, shopping targets, equipment, batches, meals, harvests and event history. It excludes downloaded libraries and the rest of Outpost.")
                            Button(onClick = { exportKind = "json"; export.launch("outpost-pantry-${LocalDate.now()}.json") }) { Text("Export pantry JSON") }
                            OutlinedButton(onClick = { exportKind = "csv"; export.launch("outpost-pantry-${LocalDate.now()}.csv") }) { Text("Export inventory CSV") }
                            OutlinedButton(onClick = { exportKind = "reference"; export.launch("outpost-food-reference.txt") }) { Text("Export printable offline reference") }
                            Text("Import adds missing IDs only; existing records win conflicts. It does not overwrite current quantities or replace a full backup. Active imported timers are held for review. Before importing, an automatic pre-import snapshot is saved inside the app.")
                            OutlinedButton(onClick = { importPicker.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }) { Text("Import / merge pantry JSON") }
                            TextButton(onClick = { exportKind = "before"; export.launch("outpost-pantry-before-import.json") }) { Text("Export pre-import snapshot") }
                            Button(onClick = onFullBackup) { Text("Full Outpost backup / restore") }
                            Text("For rollback, restore a pre-update full backup with the matching older app. Never install an old database reader over new data. Full backups contain private household information; keep copies securely.")
                        } }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogFilter(search: String, onSearch: (String) -> Unit, category: String, onCategory: (String) -> Unit, categories: List<String>) {
    Column {
        OutlinedTextField(search, onSearch, label = { Text("Search offline catalog") }, modifier = Modifier.fillMaxWidth())
        FoodSelect("Category", category, listOf("" to "All categories") + categories.map { it to it }, onCategory)
    }
}

@Composable
private fun GuideCard(guide: FoodGuide, content: FoodContent) {
    var expanded by remember(guide.id) { mutableStateOf(false) }
    FoodCard(guide.title) {
        Text(guide.paragraphs.first())
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Collapse guide" else "Read full offline guide") }
        if (expanded) { guide.paragraphs.drop(1).forEach { Text(it) }; FoodSources(content, guide.sources) }
    }
}

@Composable
private fun StorageCardBody(advice: StorageAdvice, content: FoodContent, initiallyExpanded: Boolean = false) {
    var expanded by remember(advice.id) { mutableStateOf(initiallyExpanded) }
    Text(advice.quality)
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide storage details" else "Containers / moisture / discard signs") }
    if (expanded) {
        Text("Container: ${advice.container}"); Text("Conditions: ${advice.conditions}"); Text("Oxygen absorbers: ${advice.oxygen}")
        Text("Mylar: ${advice.mylar}"); Text("Food-grade buckets: ${advice.bucket}"); Text("Vacuum sealing: ${advice.vacuum}"); Text("Discard / inspect: ${advice.discard}")
        FoodSources(content, advice.sources)
    }
}

private fun referenceSheet(content: FoodContent) = buildString {
    appendLine("OUTPOST FOOD & WATER — offline reference ${content.version}")
    appendLine("Quality periods are not safety guarantees. Never taste suspect food. Use only the exact validated preparation, jar size, gauge and elevation. No verified procedure is available for records marked unverified.")
    content.guides.forEach { appendLine("\n${it.title}\n${it.paragraphs.joinToString("\n\n")}") }
    content.storage.forEach { appendLine("\n${it.title}\n${it.container}\n${it.conditions}\n${it.quality}\nOxygen: ${it.oxygen}\nMylar: ${it.mylar}\nBucket: ${it.bucket}\nVacuum: ${it.vacuum}\nDiscard: ${it.discard}") }
    content.recipes.forEach { r -> appendLine("\n${r.food} · ${r.method} · ${r.pack} · verified=${r.verified} · ${r.version}\n${r.preparation.joinToString("\n")}\nPeeling: ${r.peeling}\nPrecook: ${r.precook}\nHeadspace: ${r.headspace}\nLiquid: ${r.liquid}\nSalt/acid: ${r.saltAcid}\n${r.schedules.joinToString { "${it.jar}: ${it.minutes.joinToString("/")} min" }}\nDial: ${r.dial}\nWeighted: ${r.weighted}\nWater-bath maximum-ft bands: ${r.waterBathBands}\n${r.warnings.joinToString("\n")}\nSource IDs: ${r.sources.joinToString()}") }
    appendLine("\nSOURCES — titles, original URLs and verification dates")
    content.sources.forEach { appendLine("${it.id}: ${it.title}\n${it.url}\nVerified ${it.verified}\n") }
}

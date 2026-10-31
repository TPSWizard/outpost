package com.conza.outpost.ui.medical

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.conza.outpost.container
import com.conza.outpost.library.Hit
import com.conza.outpost.medical.DrugHit
import com.conza.outpost.medical.DrugLabel
import com.conza.outpost.medical.Remedy
import com.conza.outpost.ui.common.BackTopBar
import com.conza.outpost.ui.common.Notice
import com.conza.outpost.ui.common.SectionCard
import com.conza.outpost.ui.content.formatBytes
import kotlinx.coroutines.flow.first

class MedicalNav(
    val back: () -> Unit,
    val drug: (Long) -> Unit,
    val interactions: (List<Long>) -> Unit,
    val condition: (String) -> Unit,
    val remedy: (String) -> Unit,
    val article: (bookId: String, path: String) -> Unit,
)

private const val DISCLAIMER = "Reference information from FDA labels, CDC and NIH. Not a substitute for a doctor or pharmacist."

@Composable
fun MedicalHome(nav: MedicalNav, initialQuery: String? = null, vm: MedicalViewModel = viewModel()) {
    androidx.compose.runtime.LaunchedEffect(initialQuery) { if (!initialQuery.isNullOrBlank()) vm.query.value = initialQuery }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val selected by vm.selected.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { BackTopBar("Medical reference", nav.back) },
        floatingActionButton = {
            if (tab == 0 && selected.size >= 2) ExtendedFloatingActionButton(onClick = { nav.interactions(selected.toList()) }) {
                Text("Check ${selected.size} together")
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf("Drugs", "Conditions", "Remedies").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
            }
            Notice(DISCLAIMER)
            when (tab) {
                0 -> DrugsTab(vm, nav)
                1 -> ConditionsTab(vm, nav)
                else -> RemediesTab(vm, nav)
            }
        }
    }
}

@Composable
private fun DrugsTab(vm: MedicalViewModel, nav: MedicalNav) {
    val info by vm.info.collectAsStateWithLifecycle()
    val ingest by vm.ingest.collectAsStateWithLifecycle()
    val q by vm.query.collectAsStateWithLifecycle()
    val byInd by vm.byIndication.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    val i = info
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (i == null || ingest != null) item { BuildCard(i != null, ingest, vm) }
        if (i != null) {
            item {
                OutlinedTextField(
                    value = q, onValueChange = { vm.query.value = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(if (byInd) "What it treats, e.g. heartburn" else "Drug name, e.g. ibuprofen") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Filled.Clear, "Clear") } },
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!byInd, { vm.byIndication.value = false }, label = { Text("By name") })
                    FilterChip(byInd, { vm.byIndication.value = true }, label = { Text("By what it treats") })
                }
            }
            if (selected.isNotEmpty()) item {
                Text("Tick 2–5 drugs, then tap “Check together” to look for interaction warnings.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(results, key = { it.id }) { h -> DrugRow(h, h.id in selected, onToggle = { vm.toggle(h.id) }, onOpen = { nav.drug(h.id) }) }
            if (q.trim().length >= 2 && results.isEmpty()) item { Text("No matching labels.", Modifier.padding(8.dp)) }
            item {
                Spacer(Modifier.size(12.dp))
                Text("${"%,d".format(i.records)} FDA labels · export ${i.exportDate ?: "?"} · ${formatBytes(i.sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    TextButton(onClick = vm::startBuild, enabled = ingest == null) { Text("Update") }
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete the drug database?") },
        text = { Text("You can rebuild it later; it takes a while and about 1.8 GB of downloads.") },
        confirmButton = { TextButton(onClick = { vm.deleteDatabase(); confirmDelete = false }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
    )
}

@Composable
private fun BuildCard(hasDb: Boolean, ingest: IngestState?, vm: MedicalViewModel) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (hasDb) "Updating drug reference" else "Offline drug reference", style = MaterialTheme.typography.titleMedium)
            when {
                ingest?.running == true -> {
                    Text(ingest.text, style = MaterialTheme.typography.bodySmall)
                    if (ingest.parts > 0) LinearProgressIndicator(progress = { ingest.overall }, Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (ingest.records > 0) Text("${"%,d".format(ingest.records)} labels in this part", style = MaterialTheme.typography.labelSmall)
                    Text("You can leave this screen; it keeps going in the background.", style = MaterialTheme.typography.labelSmall)
                    OutlinedButton(onClick = vm::cancelBuild) { Text("Pause") }
                }
                else -> {
                    Text(
                        "Search ~260,000 FDA drug labels by name or by what they treat: uses, dosage, warnings, " +
                            "and interactions. Built on this phone from openFDA's public export: about 1.8 GB to download, " +
                            "2–3 GB on disk, and 20–60 minutes.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ingest?.error?.let { Text("Last attempt failed: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = vm::startBuild) { Text(if (ingest?.error != null) "Resume build" else "Download & build") }
                }
            }
        }
    }
}

@Composable
private fun DrugRow(h: DrugHit, checked: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(h.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            val sub = listOf(h.subtitle, h.productType?.lowercase()?.replaceFirstChar { it.uppercase() }.orEmpty(),
                if (h.labelCount > 1) "${h.labelCount} labels" else "").filter { it.isNotBlank() }.joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider()
}

@Composable
fun DrugDetailScreen(id: Long, nav: MedicalNav) {
    val drugs = LocalContext.current.container.drugs
    val label by produceState<DrugLabel?>(null, id) { value = drugs.label(id) }
    val variants by produceState<List<Pair<Long, String>>>(emptyList(), label) { label?.let { value = drugs.variants(it) } }
    Scaffold(topBar = { BackTopBar(label?.title ?: "Drug", nav.back, label?.generic) }) { pad ->
        val l = label ?: return@Scaffold
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(listOfNotNull(l.manufacturer, l.route?.lowercase(), l.productType?.lowercase()).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            l.boxedWarning?.let { item { BoxedWarning(it) } }
            items(l.sections, key = { it.first }) { (h, t) ->
                SectionCard(h, t, initiallyExpanded = h.startsWith("Uses") || h.startsWith("Purpose") || h.startsWith("Dosage"))
            }
            item { Notice(DISCLAIMER) }
            if (variants.size > 1) {
                item { Text("Other labels for this product", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                items(variants.filter { it.first != l.id }, key = { "v${it.first}" }) { (vid, name) ->
                    Text(name, Modifier.fillMaxWidth().clickable { nav.drug(vid) }.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun BoxedWarning(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error)
                Text("  Boxed warning", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

/**
 * Looks for each drug's name in the *other* drugs' interaction, contraindication, and warning
 * text. This is how NOMAD's interaction check works too: it surfaces what the labels say; it
 * is not a clinical interaction database.
 */
@Composable
fun InteractionsScreen(ids: List<Long>, nav: MedicalNav) {
    val drugs = LocalContext.current.container.drugs
    val labels by produceState<List<DrugLabel>>(emptyList(), ids) { value = ids.mapNotNull { drugs.label(it) } }
    Scaffold(topBar = { BackTopBar("Interaction check", nav.back, labels.joinToString(" + ") { it.title }) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Notice("Shows sentences in each label that mention the other drugs. No mention does NOT mean the combination is safe; ask a pharmacist.") }
            items(labels, key = { it.id }) { l ->
                val others = labels.filter { it.id != l.id }
                val names = others.flatMap { o -> nameTerms(o) }.distinct()
                val hits = mentions(l.interactionsText, names)
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${l.title}'s label mentions…", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        if (hits.isEmpty()) Text("No mention of ${others.joinToString { it.title }}.", style = MaterialTheme.typography.bodySmall)
                        hits.take(8).forEach { s ->
                            Text(buildAnnotatedString {
                                var i = 0
                                val lower = s.lowercase()
                                val ranges = names.flatMap { n -> Regex("\\b${Regex.escape(n)}\\b").findAll(lower).map { it.range } }.sortedBy { it.first }
                                for (r in ranges) {
                                    if (r.first < i) continue
                                    append(s.substring(i, r.first))
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)) { append(s.substring(r.first, r.last + 1)) }
                                    i = r.last + 1
                                }
                                append(s.substring(i))
                            }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private fun nameTerms(l: DrugLabel): List<String> =
    listOfNotNull(l.brand, l.generic).flatMap { it.lowercase().split(Regex(",| and ")) }.map { it.trim() }
        .filter { it.length >= 4 && it !in setOf("tablets", "capsules", "solution", "extra strength", "maximum strength") }

private fun mentions(text: String, names: List<String>): List<String> {
    if (names.isEmpty()) return emptyList()
    val rx = names.map { Regex("\\b${Regex.escape(it)}\\b") }
    return text.split(Regex("(?<=[.;])\\s+|\\n")).map { it.trim() }.filter { s ->
        val lower = s.lowercase(); rx.any { it.containsMatchIn(lower) }
    }.distinct()
}

@Composable
private fun ConditionsTab(vm: MedicalViewModel, nav: MedicalNav) {
    val groups = remember { vm.data.conditions.groupBy { it.category } }
    LazyColumn(contentPadding = PaddingValues(12.dp)) {
        groups.forEach { (cat, list) ->
            item(key = "h:$cat") { Text(cat, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
            items(list, key = { it.slug }) { cnd ->
                val n = vm.data.remediesFor(cnd.slug).size
                Row(Modifier.fillMaxWidth().clickable { nav.condition(cnd.slug) }.padding(vertical = 10.dp)) {
                    Text(cnd.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    if (n > 0) Text("$n remed${if (n == 1) "y" else "ies"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun ConditionScreen(slug: String, nav: MedicalNav) {
    val ctx = LocalContext.current
    val c = ctx.container
    val cond = remember(slug) { c.medical.condition(slug) }
    val remedies = remember(slug) { c.medical.remediesFor(slug) }
    val medicines by produceState<List<DrugHit>?>(null, slug) {
        value = if (!c.drugs.installed || cond == null) null
        else cond.searchTerms.take(4).flatMap { c.drugs.search(it, byIndication = true, limit = 12) }
            .distinctBy { it.title.lowercase() }.sortedByDescending { it.labelCount }.take(15)
    }
    val articles by produceState<List<Hit>>(emptyList(), slug) {
        value = runCatching {
            val books = c.library.observeBooks().first().filter { it.error == null }
            c.library.suggest(books, cond?.label ?: slug, perBook = 3).take(8)
        }.getOrDefault(emptyList())
    }
    Scaffold(topBar = { BackTopBar(cond?.label ?: slug, nav.back, cond?.category) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Notice(DISCLAIMER) }
            item { Header("Home & natural remedies") }
            if (remedies.isEmpty()) item { Text("None listed for this condition.", style = MaterialTheme.typography.bodySmall) }
            items(remedies, key = { "r" + it.slug }) { r -> RemedyRow(r, c.medical.isNatural(r)) { nav.remedy(r.slug) } }
            item { Header("Over-the-counter & prescription products labeled for this") }
            when (val m = medicines) {
                null -> item { Text(if (c.drugs.installed) "Searching…" else "Build the drug reference (Drugs tab) to see labeled products.", style = MaterialTheme.typography.bodySmall) }
                else -> items(m, key = { "d" + it.id }) { h ->
                    Text(h.title + (h.subtitle.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                        Modifier.fillMaxWidth().clickable { nav.drug(h.id) }.padding(vertical = 8.dp))
                    HorizontalDivider()
                }
            }
            if (articles.isNotEmpty()) {
                item { Header("In your library") }
                items(articles, key = { "a" + it.bookId + it.path }) { h ->
                    Column(Modifier.fillMaxWidth().clickable { nav.article(h.bookId, h.path) }.padding(vertical = 8.dp)) {
                        Text(h.title)
                        Text(h.bookTitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun Header(t: String) = Text(t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))

@Composable
private fun RemediesTab(vm: MedicalViewModel, nav: MedicalNav) {
    var natural by rememberSaveable { mutableStateOf(false) }
    val list = if (natural) vm.data.naturalRemedies else vm.data.homeRemedies
    val src = if (natural) vm.data.naturalSource else vm.data.homeSource
    LazyColumn(contentPadding = PaddingValues(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!natural, { natural = false }, label = { Text("Home measures") })
                FilterChip(natural, { natural = true }, label = { Text("Herbs & supplements") })
            }
            Text("Source: ${src.name}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
        }
        items(list, key = { it.slug }) { r -> RemedyRow(r, natural) { nav.remedy(r.slug) } }
    }
}

@Composable
private fun RemedyRow(r: Remedy, natural: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Text(r.name, style = MaterialTheme.typography.bodyLarge)
        Text((if (natural) "Herb/supplement · " else "Home measure · ") + r.uses.take(110) + if (r.uses.length > 110) "…" else "",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}

@Composable
fun RemedyScreen(slug: String, nav: MedicalNav) {
    val c = LocalContext.current.container
    val r = remember(slug) { c.medical.remedy(slug) }
    val uri = LocalUriHandler.current
    Scaffold(topBar = { BackTopBar(r?.name ?: slug, nav.back, r?.commonNames?.joinToString()) }) { pad ->
        val rem = r ?: return@Scaffold
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { SectionCard("Uses", rem.uses, true) }
            item { SectionCard("How it's used", rem.how, true) }
            item { SectionCard("What the evidence says", rem.evidence, true) }
            item {
                SectionCard("Cautions", rem.cautions, true, container = MaterialTheme.colorScheme.errorContainer,
                    titleColor = MaterialTheme.colorScheme.onErrorContainer)
            }
            item {
                Text("Helps with: " + rem.conditions.mapNotNull { c.medical.condition(it)?.label }.joinToString(), style = MaterialTheme.typography.bodySmall)
            }
            item {
                TextButton(onClick = { runCatching { uri.openUri(rem.sourceUrl) } }) { Text("Source (needs internet): ${rem.sourceUrl.removePrefix("https://")}") }
            }
            item { Notice(DISCLAIMER) }
        }
    }
}

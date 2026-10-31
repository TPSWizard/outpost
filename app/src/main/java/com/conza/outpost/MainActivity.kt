package com.conza.outpost

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.conza.outpost.ai.Source
import com.conza.outpost.ui.assistant.AssistantScreen
import com.conza.outpost.ui.content.ContentScreen
import com.conza.outpost.ui.library.LibraryScreen
import com.conza.outpost.ui.map.MapScreen
import com.conza.outpost.ui.medical.ConditionScreen
import com.conza.outpost.ui.medical.DrugDetailScreen
import com.conza.outpost.ui.medical.InteractionsScreen
import com.conza.outpost.ui.medical.MedicalHome
import com.conza.outpost.ui.medical.MedicalNav
import com.conza.outpost.ui.medical.RemedyScreen
import com.conza.outpost.ui.notes.DocumentScreen
import com.conza.outpost.ui.notes.NoteEditorScreen
import com.conza.outpost.ui.notes.NotesScreen
import com.conza.outpost.ui.reader.ReaderScreen
import com.conza.outpost.ui.reader.PdfScreen
import com.conza.outpost.ui.settings.SettingsScreen
import com.conza.outpost.ui.theme.OutpostTheme
import com.conza.outpost.ui.toolkit.BenchmarkScreen
import com.conza.outpost.ui.toolkit.LearnScreen
import com.conza.outpost.ui.toolkit.ToolkitScreen
import com.conza.outpost.ui.tools.ToolViewerScreen
import com.conza.outpost.ui.tools.ToolsScreen

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Today("today", "Today", Icons.Filled.Today),
    Library("library", "Library", Icons.AutoMirrored.Filled.MenuBook),
    Map("map", "Map", Icons.Filled.Map),
    Assistant("assistant", "Assistant", Icons.Filled.AutoAwesome),
    Toolkit("toolkit", "Toolkit", Icons.Filled.Handyman),
}

/** Screens that take over the whole display (no bottom bar). */
private val FULL_SCREEN = listOf("reader", "tool/", "pdf")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = container.settings
        setContent {
            var survival by remember { mutableStateOf(settings.survivalMode) }
            var largeText by remember { mutableStateOf(settings.largeText) }
            DisposableEffect(Unit) {
                val l = settings.listen { k -> if (k == "survival_mode") survival = settings.survivalMode; if (k == "large_text") largeText = settings.largeText }
                onDispose { settings.unlisten(l) }
            }
            LaunchedEffect(survival) { applyRefreshRate(survival) }
            OutpostTheme(dark = survival || isSystemInDarkTheme(), survival = survival, largeText = largeText) {
                val nav = rememberNavController()
                val backStack by nav.currentBackStackEntryAsState()
                val current = backStack?.destination?.route
                var crashReport by remember { mutableStateOf(CrashLog.consume(this@MainActivity)) }
                crashReport?.let { report -> CrashDialog(report, onDismiss = { crashReport = null }) }

                Scaffold(
                    bottomBar = {
                        if (FULL_SCREEN.none { current?.startsWith(it) == true }) NavigationBar {
                            Tab.entries.forEach { t ->
                                NavigationBarItem(
                                    selected = current?.substringBefore('?')?.substringBefore('/') == t.route,
                                    onClick = { nav.goTab(t.route) },
                                    icon = { Icon(t.icon, contentDescription = null) },
                                    label = { Text(t.label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    Column(Modifier.padding(padding)) {
                        LowBatteryBanner(onOpen = { nav.navigate("print?card=phone") })
                        OutpostNavHost(nav, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * Survival mode asks for the display mode with the lowest refresh rate at the current
 * resolution (Pixel 8: 60 Hz instead of up to 120 Hz). Off restores the system's choice.
 */
private fun ComponentActivity.applyRefreshRate(survival: Boolean) {
    val lp = window.attributes
    if (survival) {
        @Suppress("DEPRECATION") val d = windowManager.defaultDisplay
        val cur = d.mode
        val low = d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }.minByOrNull { it.refreshRate }
        if (low != null) { lp.preferredDisplayModeId = low.modeId; lp.preferredRefreshRate = low.refreshRate }
    } else { lp.preferredDisplayModeId = 0; lp.preferredRefreshRate = 0f }
    window.attributes = lp
}

/** Below 15 % on battery: offer the printable "if this phone dies" summary. */
@Composable
private fun LowBatteryBanner(onOpen: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var reading by remember { mutableStateOf(com.conza.outpost.griddown.PowerMonitor.read(ctx)) }
    var dismissed by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) { reading = com.conza.outpost.griddown.PowerMonitor.read(c) }
        }
        ctx.registerReceiver(r, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        onDispose { runCatching { ctx.unregisterReceiver(r) } }
    }
    val rd = reading ?: return
    if (dismissed || rd.charging || rd.percent >= 15f) return
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.errorContainer) {
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Battery %.0f%%. Save the \"if this phone dies\" summary while you can.".format(rd.percent), Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onOpen) { Text("Open") }
            TextButton(onClick = { dismissed = true }) { Text("Later") }
        }
    }
}

private fun NavHostController.goTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

private fun NavHostController.openArticle(bookId: String, path: String?) =
    navigate("reader/${Uri.encode(bookId)}" + (path?.let { "?path=${Uri.encode(it)}" } ?: ""))

@Composable
private fun OutpostNavHost(nav: NavHostController, modifier: Modifier) {
    val medicalNav = MedicalNav(
        back = { nav.popBackStack() },
        drug = { nav.navigate("drug/$it") },
        interactions = { ids -> nav.navigate("interactions/${ids.joinToString(",")}") },
        condition = { nav.navigate("condition/$it") },
        remedy = { nav.navigate("remedy/$it") },
        article = { b, p -> nav.openArticle(b, p) },
    )
    val openSource: (Source) -> Unit = { s ->
        when (s.type) {
            "zim" -> if (s.bookId != null) nav.openArticle(s.bookId, s.path)
            "note" -> s.refId?.let { nav.navigate("note/$it") }
            "doc" -> s.refId?.let { nav.navigate("document/$it") }
            "pdf" -> s.path?.let { nav.navigate("pdffile?path=${Uri.encode(it)}&page=${s.refId ?: 1}") }
        }
    }

    val gridNav = com.conza.outpost.ui.griddown.GridNav(
        firstAid = { nav.navigate("firstaid") },
        supplies = { nav.navigate("supplies") },
        household = { nav.navigate("household") },
        calculators = { nav.navigate("calculators") },
        weather = { nav.navigate("weather") },
        power = { nav.navigate("power") },
        radio = { nav.navigate("radio") },
        mesh = { nav.navigate("mesh") },
        print = { nav.navigate("print") },
        beforeOutage = { nav.navigate("before") },
        back = { nav.popBackStack() },
        drugSearch = { nav.navigate("medical?q=${Uri.encode(it)}") },
    )
    val openPdfAt: (String, Int) -> Unit = { path, page -> nav.navigate("pdffile?path=${Uri.encode(path)}&page=$page") }

    NavHost(nav, startDestination = "today", modifier = modifier) {
        composable("today") { com.conza.outpost.ui.griddown.TodayScreen(gridNav) }
        composable("household") { com.conza.outpost.ui.griddown.HouseholdScreen(onBack = gridNav.back) }
        composable("supplies") { com.conza.outpost.ui.griddown.SuppliesScreen(onBack = gridNav.back, onDrugSearch = gridNav.drugSearch) }
        composable("calculators?tab={tab}", arguments = listOf(navArgument("tab") { type = NavType.IntType; defaultValue = 0 })) { e ->
            com.conza.outpost.ui.griddown.CalculatorsScreen(onBack = gridNav.back, initialTab = e.arguments?.getInt("tab") ?: 0)
        }
        composable("firstaid?topic={topic}", arguments = listOf(navArgument("topic") { type = NavType.StringType; nullable = true; defaultValue = null })) { e ->
            com.conza.outpost.ui.griddown.FirstAidScreen(onBack = gridNav.back, openPdf = openPdfAt, getManuals = { nav.navigate("content?tab=5") },
                initialTopic = e.arguments?.getString("topic"))
        }
        composable("weather") { com.conza.outpost.ui.griddown.WeatherScreen(onBack = gridNav.back, onHousehold = gridNav.household) }
        composable("radio") { com.conza.outpost.ui.griddown.RadioScreen(onBack = gridNav.back) }
        composable("mesh") { com.conza.outpost.ui.griddown.MeshScreen(onBack = gridNav.back) }
        composable("backup") { com.conza.outpost.ui.griddown.BackupScreen(onBack = gridNav.back) }
        composable("print?card={card}", arguments = listOf(navArgument("card") { type = NavType.StringType; nullable = true; defaultValue = null })) { e ->
            com.conza.outpost.ui.griddown.PrintScreen(onBack = gridNav.back, openPdf = { openPdfAt(it, 1) }, preselect = e.arguments?.getString("card"))
        }
        composable("before") {
            com.conza.outpost.ui.griddown.BeforeOutageScreen(onBack = gridNav.back, nav = com.conza.outpost.ui.griddown.BeforeNav(
                maps = { nav.navigate("content?tab=0") }, reference = { nav.navigate("content?tab=1") }, models = { nav.navigate("content?tab=3") },
                manuals = { nav.navigate("content?tab=5") }, medical = { nav.navigate("medical") }, household = gridNav.household,
                supplies = gridNav.supplies, print = gridNav.print, backup = { nav.navigate("backup") }, mesh = gridNav.mesh,
                radio = gridNav.radio, benchmark = { nav.navigate("benchmark") }, settings = { nav.navigate("settings") },
            ))
        }
        composable("power") { com.conza.outpost.ui.griddown.PowerScreen(onBack = gridNav.back, onBenchmark = { nav.navigate("benchmark") }) }
        composable("library?q={q}", arguments = listOf(navArgument("q") { type = NavType.StringType; nullable = true; defaultValue = null })) { e ->
            LibraryScreen(
                onOpen = { b, p -> nav.openArticle(b, p) },
                onBrowseContent = { nav.navigate("content?tab=1") },
                onOpenPdfFile = { nav.navigate("pdffile?path=${Uri.encode(it)}") },
                initialQuery = e.arguments?.getString("q"),
            )
        }
        composable("pdf/{bookId}?path={path}") { e ->
            PdfScreen(e.arguments?.getString("bookId"), e.arguments?.getString("path").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable("pdffile?path={path}&page={page}", arguments = listOf(
            navArgument("path") { type = NavType.StringType; defaultValue = "" },
            navArgument("page") { type = NavType.IntType; defaultValue = 1 },
        )) { e ->
            PdfScreen(null, e.arguments?.getString("path").orEmpty(), onBack = { nav.popBackStack() }, initialPage = e.arguments?.getInt("page") ?: 1)
        }
        composable(
            "reader/{bookId}?path={path}",
            arguments = listOf(
                navArgument("bookId") { type = NavType.StringType },
                navArgument("path") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { e ->
            ReaderScreen(
                e.arguments?.getString("bookId").orEmpty(), e.arguments?.getString("path"),
                onClose = { nav.popBackStack() },
                onOpenDocument = { b, p -> nav.navigate("pdf/${Uri.encode(b)}?path=${Uri.encode(p)}") },
                onSearchLibraries = { q -> nav.navigate("library?q=${Uri.encode(q)}") },
            )
        }
        composable(Tab.Map.route) { MapScreen(onOpenContent = { nav.navigate("content?tab=0") }) }
        composable(Tab.Assistant.route) {
            AssistantScreen(onOpenSource = openSource, onGetModels = { nav.navigate("content?tab=3") }, onSettings = { nav.navigate("settings") },
                onRoute = { r ->
                    when (r) {
                        is com.conza.outpost.griddown.AnswerPolicy.Route.FirstAidTopic -> nav.navigate("firstaid?topic=${r.topicId}")
                        com.conza.outpost.griddown.AnswerPolicy.Route.WaterTreatment -> nav.navigate("calculators?tab=0")
                        com.conza.outpost.griddown.AnswerPolicy.Route.FoodSafety -> nav.navigate("calculators?tab=1")
                    }
                })
        }
        composable(Tab.Toolkit.route) {
            ToolkitScreen(
                onMedical = { nav.navigate("medical") },
                onManuals = { nav.navigate("content?tab=5") },
                onContent = { nav.navigate("content?tab=0") },
                onBeforeOutage = { nav.navigate("before") },
                onBackup = { nav.navigate("backup") },
                onNotes = { nav.navigate("notes") },
                onTools = { nav.navigate("tools") },
                onLearn = { nav.navigate("learn") },
                onBenchmark = { nav.navigate("benchmark") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("content?tab={tab}", arguments = listOf(navArgument("tab") { type = NavType.IntType; defaultValue = 0 })) { e ->
            ContentScreen(initialTab = e.arguments?.getInt("tab") ?: 0)
        }

        composable("medical?q={q}", arguments = listOf(navArgument("q") { type = NavType.StringType; nullable = true; defaultValue = null })) { e ->
            MedicalHome(medicalNav, initialQuery = e.arguments?.getString("q"))
        }
        composable("drug/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
            DrugDetailScreen(e.arguments?.getLong("id") ?: 0, medicalNav)
        }
        composable("interactions/{ids}") { e ->
            InteractionsScreen(e.arguments?.getString("ids").orEmpty().split(",").mapNotNull { it.toLongOrNull() }, medicalNav)
        }
        composable("condition/{slug}") { e -> ConditionScreen(e.arguments?.getString("slug").orEmpty(), medicalNav) }
        composable("remedy/{slug}") { e -> RemedyScreen(e.arguments?.getString("slug").orEmpty(), medicalNav) }

        composable("notes") {
            NotesScreen(onBack = { nav.popBackStack() }, onOpenNote = { nav.navigate("note/$it") }, onOpenDocument = { nav.navigate("document/$it") })
        }
        composable("note/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
            NoteEditorScreen(e.arguments?.getLong("id") ?: 0, onBack = { nav.popBackStack() })
        }
        composable("document/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
            DocumentScreen(e.arguments?.getLong("id") ?: 0, onBack = { nav.popBackStack() })
        }

        composable("tools") { ToolsScreen(onBack = { nav.popBackStack() }, onOpen = { nav.navigate("tool/$it") }) }
        composable("tool/{id}") { e -> ToolViewerScreen(e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() }) }
        composable("learn") { LearnScreen(onBack = { nav.popBackStack() }) }
        composable("benchmark") { BenchmarkScreen(onBack = { nav.popBackStack() }) }
        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
    }
}

@Composable
private fun CrashDialog(report: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Outpost crashed last time") },
        text = {
            SelectionContainer {
                Text(
                    report,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(report)); onDismiss() }) { Text("Copy & close") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

package com.conza.outpost

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDownload
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
    Library("library", "Library", Icons.AutoMirrored.Filled.MenuBook),
    Map("map", "Map", Icons.Filled.Map),
    Assistant("assistant", "Assistant", Icons.Filled.AutoAwesome),
    Toolkit("toolkit", "Toolkit", Icons.Filled.Handyman),
    Content("content", "Content", Icons.Filled.CloudDownload),
}

/** Screens that take over the whole display (no bottom bar). */
private val FULL_SCREEN = listOf("reader", "tool/", "pdf")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OutpostTheme {
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
                    OutpostNavHost(nav, Modifier.padding(padding))
                }
            }
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
        }
    }

    NavHost(nav, startDestination = "library?q={q}", modifier = modifier) {
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
        composable("pdffile?path={path}") { e ->
            PdfScreen(null, e.arguments?.getString("path").orEmpty(), onBack = { nav.popBackStack() })
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
            AssistantScreen(onOpenSource = openSource, onGetModels = { nav.navigate("content?tab=3") }, onSettings = { nav.navigate("settings") })
        }
        composable(Tab.Toolkit.route) {
            ToolkitScreen(
                onMedical = { nav.navigate("medical") },
                onManuals = { nav.navigate("content?tab=5") },
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

        composable("medical") { MedicalHome(medicalNav) }
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

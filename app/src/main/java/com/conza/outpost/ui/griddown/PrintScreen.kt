package com.conza.outpost.ui.griddown

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.conza.outpost.container
import com.conza.outpost.griddown.Cards
import com.conza.outpost.griddown.PdfCards
import com.conza.outpost.ui.common.BackTopBar
import com.conza.outpost.ui.map.MapViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import java.io.File
import java.time.LocalDate

private val CARDS = listOf(
    "phone" to "If this phone dies (one-page summary)",
    "plan" to "Family plan: contacts & rally points",
    "meds" to "Medication schedule",
    "quick" to "Water & first-aid quick reference",
    "map" to "Neighborhood map with pins",
)

@Composable
fun PrintScreen(onBack: () -> Unit, openPdf: (String) -> Unit, preselect: String? = null, mapVm: MapViewModel = viewModel()) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val mapState by mapVm.state.collectAsStateWithLifecycle()
    val picked = remember { mutableStateMapOf<String, Boolean>().apply { CARDS.forEach { (k, _) -> put(k, preselect == null || k == preselect) } } }
    var busy by remember { mutableStateOf(false) }
    var file by remember { mutableStateOf<File?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    suspend fun make() {
        busy = true; note = null
        val snap = c.gridDown.snapshot()
        val plan = c.db.household().plan()
        val chosen = CARDS.map { it.first }.filter { picked[it] == true }
        val cards = chosen.mapNotNull { k ->
            when (k) {
                "phone" -> Cards.phoneDies(snap, plan, c.settings.bleachPercent)
                "plan" -> Cards.familyPlan(snap.members, plan)
                "meds" -> Cards.medSchedule(snap.members, snap.meds)
                "quick" -> Cards.quickRef(c.gridDown.firstAid, c.settings.bleachPercent, c.settings.altitudeMeters)
                else -> null
            }
        }
        var mapPage: Pair<android.graphics.Bitmap, List<String>>? = null
        if ("map" in chosen) {
            val pins = c.db.markers().all()
            val center = c.settings.homeLat?.let { la -> c.settings.homeLon?.let { lo -> LatLng(la, lo) } }
                ?: plan.firstOrNull { it.lat != null }?.let { LatLng(it.lat!!, it.lon!!) }
                ?: pins.firstOrNull()?.let { LatLng(it.lat, it.lon) }
            val style = mapState.styleJson
            if (style == null || center == null) note = "Map page skipped: ${if (style == null) "no offline map downloaded" else "set a home location or add a pin"}."
            else {
                val bmp = PdfCards.mapSnapshot(ctx, style, center, pins)
                if (bmp == null) note = "Map page skipped: the map couldn't be drawn offline here."
                else mapPage = bmp to pins.mapIndexed { i, p -> "${i + 1}. ${p.name}  (%.5f, %.5f)".format(p.lat, p.lon) }.take(30)
            }
        }
        file = withContext(Dispatchers.IO) {
            PdfCards.render(cards, File(c.storage.namedDir("printouts"), "outpost-cards-${LocalDate.now()}.pdf"), mapPage)
        }
        busy = false
    }

    LaunchedEffect(preselect) { if (preselect != null) make() }

    Scaffold(topBar = { BackTopBar("Print cards", onBack, subtitle = "PDFs you can print, share, or keep on the SD card") }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Print these before the outage, and again when things change. Paper works when every battery is flat.", style = MaterialTheme.typography.bodySmall)
            CARDS.forEach { (k, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(picked[k] == true, { picked[k] = it }); Text(label) }
            }
            Button(enabled = !busy && picked.values.any { it }, onClick = { scope.launch { make() } }) { Text("Make PDF") }
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Building…") }
            note?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            file?.let { f ->
                Text("Saved: ${f.name}", fontWeight = FontWeight.SemiBold)
                Text(f.parent ?: "", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { openPdf(f.absolutePath) }) { Text("View") }
                    OutlinedButton(onClick = { share(ctx, f) }) { Text("Share") }
                    OutlinedButton(onClick = { print(ctx, f) }) { Text("Print") }
                }
            }
        }
    }
}

private fun shareable(ctx: Context, f: File): android.net.Uri {
    val dir = File(ctx.cacheDir, "pdf").apply { mkdirs() }
    val copy = File(dir, f.name).also { f.copyTo(it, overwrite = true) }
    return FileProvider.getUriForFile(ctx, ctx.packageName + ".files", copy)
}

private fun share(ctx: Context, f: File) {
    val i = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, shareable(ctx, f))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(Intent.createChooser(i, "Share cards").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Android's print framework (works with Wi-Fi/USB printers via the system print service, no internet). */
private fun print(ctx: Context, f: File) {
    val pm = ctx.getSystemService(PrintManager::class.java) ?: return
    pm.print(f.nameWithoutExtension, object : PrintDocumentAdapter() {
        override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, cancel: CancellationSignal?, cb: LayoutResultCallback, extras: Bundle?) {
            if (cancel?.isCanceled == true) { cb.onLayoutCancelled(); return }
            cb.onLayoutFinished(PrintDocumentInfo.Builder(f.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true)
        }
        override fun onWrite(pages: Array<out PageRange>?, dest: ParcelFileDescriptor, cancel: CancellationSignal?, cb: WriteResultCallback) {
            runCatching { f.inputStream().use { input -> ParcelFileDescriptor.AutoCloseOutputStream(dest).use { input.copyTo(it) } } }
                .onSuccess { cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES)) }
                .onFailure { cb.onWriteFailed(it.message) }
        }
    }, null)
}

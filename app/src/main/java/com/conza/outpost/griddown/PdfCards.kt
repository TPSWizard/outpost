package com.conza.outpost.griddown

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.conza.outpost.data.MapMarker
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import java.io.File
import kotlin.coroutines.resume

/** Renders [PrintCard]s to US-Letter PDFs with Android's PdfDocument (no extra libraries, works offline). */
object PdfCards {
    private const val W = 612
    private const val H = 792
    private const val M = 40f

    fun render(cards: List<PrintCard>, out: File, mapPage: Pair<Bitmap, List<String>>? = null): File {
        val doc = PdfDocument()
        var pageNo = 0
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK }
        val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = Color.DKGRAY }
        val head = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f; typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK }
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.BLACK }
        val rule = Paint().apply { color = Color.GRAY; strokeWidth = 0.7f }

        fun layout(text: String, p: TextPaint, width: Int) =
            StaticLayout.Builder.obtain(text, 0, text.length, p, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(1.5f, 1f).build()

        for (card in cards) {
            var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, ++pageNo).create())
            var c: Canvas = page.canvas
            var y = M
            fun draw(l: StaticLayout, x: Float = M) { c.save(); c.translate(x, y); l.draw(c); c.restore(); y += l.height }
            fun ensure(h: Int) {
                if (y + h > H - M) {
                    doc.finishPage(page)
                    page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, ++pageNo).create())
                    c = page.canvas; y = M
                    draw(layout("${card.title} (continued)", head, (W - 2 * M).toInt())); y += 6
                }
            }
            draw(layout(card.title, title, (W - 2 * M).toInt()))
            draw(layout(card.subtitle + " · Outpost", sub, (W - 2 * M).toInt()))
            y += 6; c.drawLine(M, y, W - M, y, rule); y += 10
            for ((h, lines) in card.sections) {
                val hl = layout(h, head, (W - 2 * M).toInt())
                ensure(hl.height + 20); draw(hl); y += 3
                for (line in lines) {
                    val bl = layout("•  $line", body, (W - 2 * M - 10).toInt())
                    ensure(bl.height); draw(bl, M + 10)
                }
                y += 10
            }
            doc.finishPage(page)
        }

        mapPage?.let { (bmp, legend) ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, ++pageNo).create())
            val c = page.canvas
            c.drawText("Neighborhood map", M, M + 16, title)
            val w = W - 2 * M
            val h = w * bmp.height / bmp.width
            c.drawBitmap(bmp, null, RectF(M, M + 30, M + w, M + 30 + h), null)
            var y = M + 40 + h
            legend.forEach { line -> c.drawText(line, M, y + 12, body); y += 15 }
            doc.finishPage(page)
        }
        out.parentFile?.mkdirs()
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        return out
    }

    /**
     * Offline snapshot of the downloaded map around [center] with numbered [pins]. Returns null
     * if no map covers the area or rendering fails (e.g. on emulators without GL).
     */
    suspend fun mapSnapshot(context: Context, styleJson: String, center: LatLng, pins: List<MapMarker>, zoom: Double = 14.0): Bitmap? =
        withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine { cont ->
                Handler(Looper.getMainLooper()).post {
                    val opts = MapSnapshotter.Options(1000, 800)
                        .withStyleBuilder(Style.Builder().fromJson(styleJson))
                        .withCameraPosition(CameraPosition.Builder().target(center).zoom(zoom).build())
                        .withLogo(false)
                    val snapper = MapSnapshotter(context, opts)
                    snapper.start(
                        { snap: MapSnapshot ->
                            val bmp = snap.bitmap.copy(Bitmap.Config.ARGB_8888, true)
                            val cv = Canvas(bmp)
                            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 30, 30) }
                            val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
                            pins.forEachIndexed { i, p ->
                                val pt = snap.pixelForLatLng(LatLng(p.lat, p.lon))
                                if (pt.x in 0f..bmp.width.toFloat() && pt.y in 0f..bmp.height.toFloat()) {
                                    cv.drawCircle(pt.x, pt.y, 16f, dot)
                                    cv.drawText("${i + 1}", pt.x, pt.y + 8f, num)
                                }
                            }
                            if (cont.isActive) cont.resume(bmp)
                        },
                        { _: String -> if (cont.isActive) cont.resume(null) },
                    )
                    cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { runCatching { snapper.cancel() } } }
                }
            }
        }
}

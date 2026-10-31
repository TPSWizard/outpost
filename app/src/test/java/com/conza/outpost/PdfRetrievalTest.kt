package com.conza.outpost

import com.conza.outpost.ai.PdfPassages
import com.conza.outpost.ai.PdfTextSource
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PDF text extraction → page-tagged passages → page-cited retrieval, on the JVM with desktop
 * PDFBox (the same API as PDFBox-Android, different package).
 */
class PdfRetrievalTest {
    private val desktop = PdfTextSource { f ->
        PDDocument.load(f).use { doc ->
            val s = PDFTextStripper()
            (1..doc.numberOfPages).map { p -> s.startPage = p; s.endPage = p; s.getText(doc) }
        }
    }

    private fun makePdf(pages: List<List<String>>): File {
        val f = File.createTempFile("manual", ".pdf").apply { deleteOnExit() }
        PDDocument().use { doc ->
            pages.forEach { lines ->
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 11f)
                    cs.setLeading(14f)
                    cs.newLineAtOffset(50f, 720f)
                    lines.forEach { cs.showText(it); cs.newLine() }
                    cs.endText()
                }
            }
            doc.save(f)
        }
        return f
    }

    private val manual = listOf(
        listOf("Chapter 1. Introduction", "This manual covers first aid for soldiers in the field.", "Read every chapter before you need it."),
        listOf("Chapter 2. Bleeding", "Apply a tourniquet two to three inches above the wound, never on a joint.",
            "Tighten until the bleeding stops and the distal pulse is gone. Note the time of application."),
        listOf("Chapter 3. Burns", "Cool a thermal burn with clean water. Cover it loosely with a dry, sterile dressing.",
            "Do not break blisters or apply grease or ointment to the burn."),
        listOf("12"), // a page with only a page number
    )

    @Test fun extractsTextPageByPage() {
        val pages = desktop.pages(makePdf(manual))
        assertEquals(4, pages.size)
        assertTrue(pages[1].contains("tourniquet"))
        assertTrue(pages[2].contains("blisters"))
    }

    @Test fun passagesCarryTheirPage() {
        val f = makePdf(manual)
        val chunks = PdfPassages.chunks(f.absolutePath, "FM Test First Aid", desktop.pages(f))
        assertEquals(listOf(1, 2, 3), chunks.map { it.page }.distinct()) // page 4 is just a number: skipped
        assertTrue(chunks.all { it.sourceType == "pdf" && it.sourceRef == f.absolutePath })
        assertEquals(chunks.indices.toList(), chunks.map { it.position })
    }

    @Test fun retrievalCitesTheRightPage() {
        val f = makePdf(manual)
        val chunks = PdfPassages.chunks(f.absolutePath, "FM Test First Aid", desktop.pages(f))
        val tq = PdfPassages.search("where do I put a tourniquet?", chunks)
        assertEquals(2, tq.first().first.page)
        val burn = PdfPassages.search("should I pop burn blisters", chunks)
        assertEquals(3, burn.first().first.page)
        val src = PdfPassages.source(burn.first().first)
        assertEquals("p. 3", src.subtitle)
        assertEquals(3L, src.refId)
        assertEquals(f.absolutePath, src.path)
        assertTrue(PdfPassages.search("satellite uplink frequency", chunks).isEmpty())
    }

    @Test fun cleansExtractionArtifacts() {
        assertEquals("tourniquet above\nthe wound", PdfPassages.clean("tour-\nniquet   above \n\n  the wound "))
    }

    @Test fun longPagesSplitButKeepPage() {
        val long = (1..60).map { "Sentence number $it about water purification with iodine tablets." }
        val chunks = PdfPassages.chunks("/x.pdf", "Long", listOf(long.joinToString(" "), "Second page about knots and lashings for shelters."))
        assertTrue(chunks.count { it.page == 1 } > 1)
        assertEquals(2, PdfPassages.search("knots lashings", chunks).first().first.page)
    }
}

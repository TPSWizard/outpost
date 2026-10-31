package com.conza.outpost.ai

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/** PDFBox-Android text extraction, one page at a time, buffering to temp files so 4 GB phones cope. */
class AndroidPdfText(private val context: Context) : PdfTextSource {
    @Volatile private var ready = false

    override fun pages(file: File): List<String> {
        if (!ready) { PDFBoxResourceLoader.init(context.applicationContext); ready = true }
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            val stripper = PDFTextStripper()
            return (1..doc.numberOfPages).map { p ->
                stripper.startPage = p
                stripper.endPage = p
                runCatching { stripper.getText(doc) }.getOrDefault("")
            }
        }
    }
}

package com.conza.outpost

import com.conza.outpost.ai.Source
import com.conza.outpost.ai.Sources
import com.conza.outpost.ai.TextTools
import com.conza.outpost.catalog.CatalogParser
import com.conza.outpost.catalog.ResourceKind
import com.conza.outpost.medical.DrugDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AssistantLogicTest {
    @Test fun htmlToTextDropsScriptsTagsAndEntities() {
        val html = """<html><head><title>x</title><style>p{}</style></head><body>
            <script>alert(1)</script><h1>Burns</h1><p>Cool the burn &amp; cover it.<sup>[3]</sup></p>
            <ul><li>Run cool water</li><li>Don&#39;t use ice</li></ul></body></html>"""
        val t = TextTools.htmlToText(html)
        assertTrue(t.contains("Burns"))
        assertTrue(t.contains("Cool the burn & cover it."))
        assertTrue(t.contains("Don't use ice"))
        assertFalse(t.contains("alert"))
        assertFalse(t.contains("<"))
        assertFalse(t.contains("[3]"))
    }

    @Test fun chunksRespectTargetAndCoverText() {
        val para = (1..40).joinToString(" ") { "Sentence number $it explains something useful." }
        val text = listOf(para, "Short paragraph.", para).joinToString("\n\n")
        val chunks = TextTools.chunk(text, 500, 80)
        assertTrue(chunks.size > 3)
        assertTrue(chunks.all { it.length <= 1000 })
        assertTrue(chunks.any { it.contains("Sentence number 40") })
        assertTrue(chunks.any { it.contains("Short paragraph.") })
    }

    @Test fun rankPrefersRelevantPassage() {
        val passages = listOf(
            "The stock market fell today as investors worried about rates.",
            "To purify water, bring it to a rolling boil for one minute; at altitude boil for three minutes.",
            "Water polo is a team sport played in a pool.",
        )
        val best = TextTools.rank("how do I purify water by boiling", passages).first().first
        assertEquals(1, best)
    }

    @Test fun keywordQueryDropsFiller() {
        assertEquals("treat second degree burn", TextTools.keywordQuery("How do I treat a second degree burn?"))
    }

    @Test fun embeddingsRoundTrip() {
        val v = floatArrayOf(0.1f, -2.5f, 3.25f)
        val back = TextTools.toFloats(TextTools.toBytes(v))
        assertTrue(v.contentEquals(back))
        assertEquals(1f, TextTools.cosine(v, v), 1e-5f)
    }

    @Test fun sourcesRoundTrip() {
        val list = listOf(Source("zim", "Burn", "Medical Library", "zimgit-medicine_en_2024-08", "A/Burn"), Source("note", "Plan", refId = 7))
        assertEquals(list, Sources.decode(Sources.encode(list)))
        assertNull(Sources.encode(emptyList()))
        assertTrue(Sources.decode("not json").isEmpty())
    }

    @Test fun ftsQueryIsSafe() {
        assertEquals("name:ibu*", DrugDatabase.ftsQuery("Ibu", "name"))
        assertEquals("indications:heart* indications:burn*", DrugDatabase.ftsQuery("heart-burn\"; DROP TABLE", "indications")?.substringBefore(" indications:drop"))
        assertNull(DrugDatabase.ftsQuery("a !", "name"))
    }

    @Test fun cleanSectionStripsAllCapsHeading() {
        assertEquals("Temporarily relieves minor aches.", DrugDatabase.cleanSection("INDICATIONS & USAGE Temporarily relieves minor aches."))
        assertEquals("Uses temporarily relieves pain", DrugDatabase.cleanSection("Uses temporarily relieves pain"))
    }

    @Test fun extrasCatalogParses() {
        val base = CatalogParser.build(
            CatalogParser.parseMaps(File("src/main/assets/catalog/maps.json").readText()),
            CatalogParser.parseKiwix(File("src/main/assets/catalog/kiwix-categories.json").readText()),
            CatalogParser.parseWikipedia(File("src/main/assets/catalog/wikipedia.json").readText()),
        )
        val c = CatalogParser.withExtras(base, CatalogParser.parseExtras(File("src/main/assets/catalog/outpost-extras.json").readText()))
        val models = c.models.flatMap { s -> s.groups.flatMap { it.items } }
        assertTrue(models.size >= 5)
        assertTrue(models.all { it.kind == ResourceKind.MODEL && it.fileName.endsWith(".gguf") })
        assertTrue(models.any { it.extra["role"] == "embedding" })
        val tools = c.tools.flatMap { s -> s.groups.flatMap { it.items } }
        assertTrue(tools.any { it.id == "cyberchef" && it.extra["entry"]!!.endsWith(".html") })
        val manuals = c.manuals.flatMap { s -> s.groups.flatMap { it.items } }
        assertTrue(manuals.size >= 20)
        assertTrue(manuals.all { it.kind == ResourceKind.DOC && it.fileName.endsWith(".pdf") && it.url.startsWith("https://archive.org/download/") })
        // Model and tool ids must be unique: they key installs and downloads.
        val ids = (models + tools + manuals).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}

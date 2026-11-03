package com.conza.outpost

import com.conza.outpost.ai.Assistant
import com.conza.outpost.ai.Source
import com.conza.outpost.ai.Sources
import com.conza.outpost.ai.TextTools
import com.conza.outpost.catalog.CatalogParser
import com.conza.outpost.catalog.ResourceKind
import com.conza.outpost.griddown.AssistantTools
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

    @Test fun rankOverStoredTermsMatchesRankOverText() {
        val passages = listOf(
            "Boil water for one minute to make it safe to drink.",
            "A tourniquet goes two to three inches above the wound, never on a joint.",
            "",
        )
        val query = "how long to boil water"
        assertEquals(TextTools.rank(query, passages), TextTools.rankTerms(query, passages.map { TextTools.terms(it) }))
        assertEquals(passages.indices.map { it to 0.0 }, TextTools.rankTerms("the of and", passages.map { TextTools.terms(it) }))
    }

    @Test fun toolPromptOnlyForQuestionsThatCouldNeedAnAction() {
        for (q in listOf("Add 24 bottles of water to my supplies", "How much bleach for 20 liters of cloudy water?",
            "Make a checklist for the go bag", "Mark this spot as the creek", "log that we have 3 days of fuel")) {
            assertTrue(q, AssistantTools.wantsAction(q))
        }
        for (q in listOf("How do I treat a second degree burn?", "What are the signs of hypothermia?",
            "Explain how a tourniquet works", "Which wild plants are safe to eat in Florida?")) {
            assertFalse(q, AssistantTools.wantsAction(q))
        }
    }

    @Test fun shortAnswersGetASmallerPassageBudget() {
        val (shortCount, shortChars) = Assistant.passageBudget(short = true)
        val (longCount, longChars) = Assistant.passageBudget(short = false)
        assertTrue(shortCount < longCount && shortChars < longChars)
        assertTrue(longChars <= 1000) // four passages at this size are about 1,000 prompt tokens
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
        // Chat models ship as Q4_0: the weight format the Arm int8 matrix kernels (KleidiAI, repack) accelerate on phones.
        val chat = models.filter { it.extra["role"] == "chat" }
        assertTrue(chat.size >= 5)
        assertTrue(chat.all { it.version == "q4_0" && it.url.contains("q4_0", ignoreCase = true) })
        val tools = c.tools.flatMap { s -> s.groups.flatMap { it.items } }
        assertTrue(tools.any { it.id == "cyberchef" && it.extra["entry"]!!.endsWith(".html") })
        val manuals = c.manuals.flatMap { s -> s.groups.flatMap { it.items } }
        assertTrue(manuals.size >= 20)
        assertTrue(manuals.all { it.kind == ResourceKind.DOC && it.fileName.endsWith(".pdf") && (it.url.startsWith("https://archive.org/download/") || it.url.startsWith("https://www.ncvec.org/downloads/")) })
        // Model and tool ids must be unique: they key installs and downloads.
        val ids = (models + tools + manuals).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}

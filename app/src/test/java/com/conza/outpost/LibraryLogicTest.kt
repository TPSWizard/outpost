package com.conza.outpost

import com.conza.outpost.library.ZimUrls
import com.conza.outpost.ui.map.regionIdOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryLogicTest {
    @Test fun zimUrlRoundTripsTrickyPaths() {
        val cases = listOf(
            "A/Main_Page",
            "Main_Page",
            "A/C++",                       // '+' must stay literal
            "A/Œuvre d'art (film)",        // unicode, space, apostrophe, parens
            "-/assets/style.css",
            "A/100%_pure",                 // literal percent
            "I/m/Logo.svg.png",
        )
        for (p in cases) {
            val url = ZimUrls.build("wikipedia_en_top_mini_2026-06", p)
            val t = ZimUrls.parse(url)!!
            assertEquals(p, t.path)
            assertEquals("wikipedia_en_top_mini_2026-06", t.bookId)
        }
    }

    @Test fun zimUrlKeepsQuerySeparately() {
        val t = ZimUrls.parse("https://${ZimUrls.HOST}/book/page.html?v=2")!!
        assertEquals("page.html", t.path)
        assertEquals("v=2", t.query)
    }

    @Test fun foreignUrlsAreNotZim() {
        assertNull(ZimUrls.parse("https://en.wikipedia.org/wiki/Foo"))
        assertNull(ZimUrls.parse("https://${ZimUrls.HOST}/onlybook"))
    }

    @Test fun regionIdsStripVersion() {
        assertEquals("alaska", regionIdOf("alaska_2025-12.pmtiles"))
        assertEquals("new_york", regionIdOf("new_york_2025-12.pmtiles"))
        assertEquals("custom", regionIdOf("custom.pmtiles"))
    }
}

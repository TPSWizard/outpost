package com.conza.outpost

import com.conza.outpost.catalog.CatalogParser
import com.conza.outpost.catalog.ResourceKind
import com.conza.outpost.map.MapRegion
import com.conza.outpost.map.MapStyleBuilder
import com.conza.outpost.storage.PmtilesHeader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CoreLogicTest {
    private fun asset(path: String) = File("src/main/assets/$path").readText()

    private fun catalog() = CatalogParser.build(
        CatalogParser.parseMaps(asset("catalog/maps.json")),
        CatalogParser.parseKiwix(asset("catalog/kiwix-categories.json")),
        CatalogParser.parseWikipedia(asset("catalog/wikipedia.json")),
    )

    @Test fun parsesBundledNomadCatalogs() {
        val c = catalog()
        val maps = c.maps.single().groups.flatMap { it.items }
        assertTrue("expected all US states + more", maps.size >= 50)
        assertTrue(maps.all { it.kind == ResourceKind.MAP && it.url.endsWith(".pmtiles") })
        assertTrue(c.library.isNotEmpty())
        assertNotNull(c.wikipedia)
        // "No Wikipedia" has a null URL and must be dropped, not crash.
        assertTrue(c.wikipedia!!.items.none { it.id == "none" })
    }

    @Test fun tiersAreCumulative() {
        val c = catalog()
        val medicine = c.library.first { it.title == "Medicine" }.groups
        val essential = medicine.first { it.key == "zim:medicine-essential" }.items.map { it.id }
        val standard = medicine.first { it.key == "zim:medicine-standard" }.items.map { it.id }
        val comprehensive = medicine.first { it.key == "zim:medicine-comprehensive" }.items.map { it.id }
        assertTrue(standard.containsAll(essential))
        assertTrue(comprehensive.containsAll(standard))
        assertEquals(comprehensive.size, comprehensive.toSet().size)
    }

    @Test fun fileNamesMatchNomadConvention() {
        val hawaii = catalog().maps.single().groups.flatMap { it.items }.first { it.id == "hawaii" }
        assertTrue(hawaii.fileName.matches(Regex("hawaii_\\d{4}-\\d{2}\\.pmtiles")))
    }

    @Test fun styleClonesLayersPerRegionAndPointsAtBundledAssets() {
        val base = asset("map/base-style.json")
        val baseLayers = Json.parseToJsonElement(base).jsonObject["layers"]!!.jsonArray
        val sourced = baseLayers.count { "source" in it.jsonObject }
        val unsourced = baseLayers.size - sourced

        val out = Json.parseToJsonElement(
            MapStyleBuilder.build(base, listOf(MapRegion("hawaii", "/data/maps/hawaii.pmtiles"), MapRegion("oregon", "/data/maps/oregon.pmtiles")))
        ).jsonObject

        val layers = out["layers"]!!.jsonArray.map { it.jsonObject }
        assertEquals(unsourced + sourced * 2, layers.size)
        assertEquals(layers.size, layers.map { it["id"]!!.jsonPrimitive.content }.toSet().size) // ids unique
        assertEquals("pmtiles://file:///data/maps/hawaii.pmtiles",
            out["sources"]!!.jsonObject["hawaii"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals(MapStyleBuilder.GLYPHS, out["glyphs"]!!.jsonPrimitive.content)
        assertEquals(MapStyleBuilder.SPRITE, out["sprite"]!!.jsonPrimitive.content)

        // Every font referenced by the style must exist as a bundled folder.
        val text = out.toString()
        MapStyleBuilder.FONT_RENAMES.forEach { (old, new) ->
            assertFalse("unrenamed font $old", text.contains("\"$old\""))
            if (text.contains("\"$new\"")) assertTrue(File("src/main/assets/map/fonts/$new/0-255.pbf").exists())
        }
        assertTrue(File("src/main/assets/map/sprites/v4/light@2x.png").exists())
    }

    @Test fun pmtilesHeaderParses() {
        val b = ByteBuffer.allocate(PmtilesHeader.SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.put("PMTiles".toByteArray()); b.put(3)
        b.put(100, 0); b.put(101, 15)
        b.putInt(102, (-160.5 * 1e7).toInt()); b.putInt(106, (18.9 * 1e7).toInt())
        b.putInt(110, (-154.8 * 1e7).toInt()); b.putInt(114, (22.2 * 1e7).toInt())
        b.put(118, 7); b.putInt(119, (-157.8 * 1e7).toInt()); b.putInt(123, (21.3 * 1e7).toInt())
        val h = PmtilesHeader.parse(b.array())!!
        assertEquals(15, h.maxZoom)
        assertEquals(-160.5, h.minLon, 1e-6)
        assertEquals(22.2, h.maxLat, 1e-6)
        assertEquals(7, h.centerZoom)
    }

    @Test fun pmtilesHeaderRejectsHtml() {
        val html = "<!DOCTYPE html><html>captive portal</html>".padEnd(200).toByteArray()
        assertNull(PmtilesHeader.parse(html))
    }
}

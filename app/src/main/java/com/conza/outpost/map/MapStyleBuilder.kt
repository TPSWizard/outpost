package com.conza.outpost.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One installed .pmtiles file to render. */
data class MapRegion(val name: String, val filePath: String)

/**
 * Kotlin port of NOMAD's MapService.generateStylesFile().
 *
 * NOMAD ships one Protomaps style whose layers all point at a single "protomaps" source.
 * Regional maps are separate .pmtiles files, so for every installed region we clone the
 * source-bound layers and point them at that region's file.
 *
 * Two deliberate differences from upstream:
 *  - Layers are emitted layer-major (all regions' water, then all roads, then all labels)
 *    instead of region-major, so one region's land fill can't paint over a neighbor's
 *    labels along a shared border.
 *  - Glyph/sprite URLs point at assets bundled in the APK, and font stacks are renamed
 *    to space-free folder names so asset lookups never depend on URL percent-decoding.
 */
object MapStyleBuilder {

    const val GLYPHS = "asset://map/fonts/{fontstack}/{range}.pbf"
    const val SPRITE = "asset://map/sprites/v4/light"
    private const val ATTRIBUTION =
        "<a href=\"https://github.com/protomaps/basemaps\">Protomaps</a> © <a href=\"https://openstreetmap.org\">OpenStreetMap</a>"

    /** Upstream font stack name → folder name under assets/map/fonts. */
    val FONT_RENAMES = mapOf(
        "Noto Sans Regular" to "NotoSans-Regular",
        "Noto Sans Medium" to "NotoSans-Medium",
        "Noto Sans Italic" to "NotoSans-Italic",
    )

    private val json = Json { prettyPrint = false }

    fun build(baseStyleJson: String, regions: List<MapRegion>): String {
        val base = Json.parseToJsonElement(baseStyleJson).jsonObject
        val layers = (base["layers"] as? JsonArray).orEmpty().map { it.jsonObject }

        val unsourced = layers.filter { it["source"] == null }
        val templates = layers.filter { it["source"] != null }

        val sources = buildJsonObject {
            for (r in regions) {
                put(r.name, buildJsonObject {
                    put("type", "vector")
                    put("attribution", ATTRIBUTION)
                    put("url", "pmtiles://file://${r.filePath}")
                })
            }
        }

        val outLayers = buildList {
            addAll(unsourced)
            for (t in templates) {
                for (r in regions) {
                    add(JsonObject(t + mapOf(
                        "id" to JsonPrimitive("${t["id"]!!.jsonPrimitive.content}-${r.name}"),
                        "source" to JsonPrimitive(r.name),
                    )))
                }
            }
        }

        val style = JsonObject(base + mapOf(
            "sources" to sources,
            "layers" to JsonArray(outLayers),
            "glyphs" to JsonPrimitive(GLYPHS),
            "sprite" to JsonPrimitive(SPRITE),
        ))
        return json.encodeToString(JsonElement.serializer(), renameFonts(style))
    }

    /** Source names must be unique and stable; derive from the catalog id. */
    fun sourceNameFor(resourceId: String): String =
        resourceId.lowercase().replace(Regex("[^a-z0-9_-]"), "-")

    private fun renameFonts(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { renameFonts(it.value) })
        is JsonArray -> JsonArray(e.map { renameFonts(it) })
        is JsonPrimitive -> if (e.isString && e.content in FONT_RENAMES) JsonPrimitive(FONT_RENAMES[e.content]) else e
    }
}

package com.conza.outpost.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Wire models for Project NOMAD's catalog files (the collections folder of the NOMAD repo).
 * Field names mirror the upstream JSON exactly so we can consume their published
 * manifests without a translation layer. Unknown fields are ignored by the parser.
 */

@Serializable
data class SpecResource(
    val id: String,
    val version: String? = null,
    val title: String,
    val description: String = "",
    val url: String? = null,
    @SerialName("size_mb") val sizeMb: Long = 0,
)

// ---- maps.json ----
@Serializable
data class MapsSpec(
    @SerialName("spec_version") val specVersion: String,
    val collections: List<MapCollection>,
)

@Serializable
data class MapCollection(
    val name: String,
    val slug: String,
    val description: String = "",
    val resources: List<SpecResource>,
)

// ---- kiwix-categories.json ----
@Serializable
data class KiwixSpec(
    @SerialName("spec_version") val specVersion: String,
    val categories: List<KiwixCategory>,
)

@Serializable
data class KiwixCategory(
    val name: String,
    val slug: String,
    val description: String = "",
    val tiers: List<KiwixTier>,
)

@Serializable
data class KiwixTier(
    val name: String,
    val slug: String,
    val description: String = "",
    val recommended: Boolean = false,
    /** Upstream tiers are cumulative: "standard" includes everything in "essential". */
    val includesTier: String? = null,
    val resources: List<SpecResource>,
)

// ---- wikipedia.json ----
@Serializable
data class WikipediaSpec(
    @SerialName("spec_version") val specVersion: String,
    val options: List<WikipediaOption>,
)

/** Wikipedia options use `name` where the other catalogs use `title`. */
@Serializable
data class WikipediaOption(
    val id: String,
    val name: String,
    val description: String = "",
    val url: String? = null,
    val version: String? = null,
    @SerialName("size_mb") val sizeMb: Long = 0,
) {
    fun toSpec() = SpecResource(id = id, version = version, title = name, description = description, url = url, sizeMb = sizeMb)
}

/** What kind of file a resource downloads to; drives storage folder and validation. */
enum class ResourceKind(val folder: String, val extension: String) {
    MAP("maps", "pmtiles"),
    ZIM("zim", "zim"),
    MODEL("models", "gguf"),
    /** Offline web tools (CyberChef, IT-Tools): downloaded as .zip, extracted into a folder. */
    TOOL("tools", "zip"),
    /** PDF books (field manuals, guides): read in the built-in PDF viewer, listed in the Library. */
    DOC("books", "pdf"),
}

@Serializable
data class ManualCategory(
    val slug: String,
    val name: String,
    val description: String = "",
    val recommended: Boolean = false,
    val resources: List<ExtraResource>,
)

// ---- outpost-extras.json (Outpost's own catalog: AI models + tool packs) ----
@Serializable
data class ExtrasSpec(
    @SerialName("spec_version") val specVersion: String,
    val models: List<ExtraResource> = emptyList(),
    val tools: List<ExtraResource> = emptyList(),
    val manuals: List<ManualCategory> = emptyList(),
)

@Serializable
data class ExtraResource(
    val id: String,
    val version: String,
    val title: String,
    val description: String = "",
    val url: String,
    @SerialName("size_mb") val sizeMb: Long = 0,
    val role: String? = null,
    @SerialName("ram_gb") val ramGb: Int? = null,
    val recommended: Boolean = false,
    val entry: String? = null,
) {
    fun toItem(kind: ResourceKind) = CatalogItem(
        id = id, version = version, title = title, description = description, url = url, sizeMb = sizeMb, kind = kind,
        extra = buildMap {
            role?.let { put("role", it) }
            ramGb?.let { put("ram_gb", it.toString()) }
            entry?.let { put("entry", it) }
            if (recommended) put("recommended", "true")
        },
    )
}

/** A downloadable item, normalized across all three catalogs. */
data class CatalogItem(
    val id: String,
    val version: String,
    val title: String,
    val description: String,
    val url: String,
    val sizeMb: Long,
    val kind: ResourceKind,
    /** Kind-specific fields: model role/RAM, tool entry page. */
    val extra: Map<String, String> = emptyMap(),
) {
    /** Same naming scheme as NOMAD (`<id>_<version>.<ext>`) so files are interchangeable. */
    val fileName: String get() = "${id}_$version.${kind.extension}"
}

fun SpecResource.toItem(kind: ResourceKind): CatalogItem? {
    val u = url ?: return null // e.g. the "No Wikipedia" option
    return CatalogItem(
        id = id,
        version = version ?: "unversioned",
        title = title,
        description = description,
        url = u,
        sizeMb = sizeMb,
        kind = kind,
    )
}

/** A group shown as one card in the UI (a map region, a Kiwix tier, or the Wikipedia picker). */
data class CatalogGroup(
    val key: String,
    val title: String,
    val description: String,
    val items: List<CatalogItem>,
    val recommended: Boolean = false,
    /** For Wikipedia: options are mutually exclusive sizes of the same thing. */
    val pickOne: Boolean = false,
)

data class CatalogSection(val title: String, val groups: List<CatalogGroup>)

data class Catalog(
    val maps: List<CatalogSection>,
    val library: List<CatalogSection>,
    val wikipedia: CatalogGroup?,
    val versions: Map<String, String>,
    val models: List<CatalogSection> = emptyList(),
    val tools: List<CatalogSection> = emptyList(),
    val manuals: List<CatalogSection> = emptyList(),
) {
    fun allItems(): List<CatalogItem> =
        (maps + library + models + tools + manuals).flatMap { s -> s.groups.flatMap { it.items } } + (wikipedia?.items ?: emptyList())

    fun item(id: String): CatalogItem? = allItems().firstOrNull { it.id == id }
}

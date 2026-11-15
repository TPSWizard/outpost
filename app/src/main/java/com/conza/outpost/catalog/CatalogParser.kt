package com.conza.outpost.catalog

import kotlinx.serialization.json.Json

/** Pure parsing/flattening logic — no Android dependencies, so it is unit-tested directly. */
object CatalogParser {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun parseMaps(text: String): MapsSpec = json.decodeFromString(MapsSpec.serializer(), text)
    fun parseKiwix(text: String): KiwixSpec = json.decodeFromString(KiwixSpec.serializer(), text)
    fun parseWikipedia(text: String): WikipediaSpec = json.decodeFromString(WikipediaSpec.serializer(), text)
    fun parseExtras(text: String): ExtrasSpec = json.decodeFromString(ExtrasSpec.serializer(), text)

    fun withExtras(c: Catalog, extras: ExtrasSpec): Catalog {
        val chat = extras.models.filter { it.role != "embedding" }.map { it.toItem(ResourceKind.MODEL) }
        val embed = extras.models.filter { it.role == "embedding" }.map { it.toItem(ResourceKind.MODEL) }
        return c.copy(
            models = listOf(
                CatalogSection("AI models", listOfNotNull(
                    CatalogGroup("models:chat", "Chat models",
                        "Run the assistant on this phone. Pick one that fits your phone's memory; you can install several.",
                        chat, recommended = false),
                    embed.takeIf { it.isNotEmpty() }?.let {
                        CatalogGroup("models:embed", "Search model",
                            "Lets the assistant find relevant passages in your notes and imported documents.", it)
                    },
                )),
            ),
            tools = listOf(
                CatalogSection("Tools", listOf(
                    CatalogGroup("tools", "Offline tool kits",
                        "Self-contained web apps that run inside Outpost with no connection.",
                        extras.tools.map { it.toItem(ResourceKind.TOOL) }),
                )),
            ),
            manuals = listOf(
                CatalogSection("Field manuals", listOf(CatalogGroup(
                    "manuals:field-basics", "Field basics · survival, navigation, hygiene",
                    "Compact pre-trip pack of three historical manuals. Check current local rules and public-health guidance alongside them.",
                    extras.manuals.flatMap { it.resources }
                        .filter { it.id in setOf("fm-21-76", "fm-3-25-26", "fm-21-10") }
                        .map { it.toItem(ResourceKind.DOC) },
                    recommended = true,
                )) + extras.manuals.map { m ->
                    CatalogGroup("manuals:${m.slug}", m.name, m.description, m.resources.map { it.toItem(ResourceKind.DOC) }, recommended = m.recommended)
                } + listOf(CatalogGroup(
                    "readings:history", "Historical reading",
                    "Historical works for context and decision-making. Not current emergency instructions.",
                    extras.readings.map { it.toItem(ResourceKind.TEXT) },
                ))),
            ),
            versions = c.versions + ("extras" to extras.specVersion),
        )
    }

    fun build(maps: MapsSpec, kiwix: KiwixSpec, wikipedia: WikipediaSpec): Catalog {
        val mapSection = CatalogSection(
            title = "Regional maps",
            groups = maps.collections.map { c ->
                CatalogGroup(
                    key = "map:${c.slug}",
                    title = c.name,
                    description = c.description + " These are offline basemaps, not contour or elevation maps.",
                    items = c.resources.mapNotNull { it.toItem(ResourceKind.MAP)?.copy(
                        description = "Offline regional basemap for ${it.title}. No terrain contours or elevation data; carry a topographic map for terrain navigation."
                    ) },
                )
            },
        )

        val librarySections = kiwix.categories.map { cat ->
            CatalogSection(
                title = cat.name,
                groups = cat.tiers.map { tier ->
                    CatalogGroup(
                        key = "zim:${tier.slug}",
                        title = "${cat.name} · ${tier.name}",
                        description = if (cat.slug == "survival") when (tier.slug) {
                            "survival-essential" -> "Water, food preparation, and knots. Compact text-first references; videos are optional below."
                            "survival-standard" -> "Official disaster planning, outdoor skills, and radio references. Includes Essential; videos are optional below."
                            "survival-comprehensive" -> "Post-disaster recovery, off-grid technology, and historical reading. Includes Standard; videos are optional below."
                            else -> "Text-first references; videos are optional below."
                        } else tier.description,
                        items = resolveTier(cat, tier).filterNot { cat.slug == "survival" && it.url.contains("/videos/") },
                        recommended = tier.recommended,
                    )
                } + if (cat.slug == "survival") listOf(CatalogGroup(
                    key = "zim:survival-video",
                    title = "Survival & Preparedness · Optional videos",
                    description = "Large video libraries; download only if you have the storage and need them. Not included in the text tiers.",
                    items = cat.tiers.flatMap { it.resources }.filter { it.url?.contains("/videos/") == true }
                        .distinctBy { it.id }.mapNotNull { it.toItem(ResourceKind.ZIM) },
                )) else emptyList(),
            )
        }

        val wiki = wikipedia.options.mapNotNull { it.toSpec().toItem(ResourceKind.ZIM) }
        val wikiGroup = if (wiki.isEmpty()) null else CatalogGroup(
            key = "wikipedia",
            title = "Wikipedia",
            description = "Pick one size. Larger editions include images and more articles.",
            items = wiki,
            pickOne = true,
        )

        return Catalog(
            maps = listOf(mapSection),
            library = librarySections,
            wikipedia = wikiGroup,
            versions = mapOf(
                "maps" to maps.specVersion,
                "kiwix" to kiwix.specVersion,
                "wikipedia" to wikipedia.specVersion,
            ),
        )
    }

    /**
     * Expand a tier's `includesTier` chain the same way NOMAD does, so picking
     * "Comprehensive" also pulls in Standard and Essential. Guards against cycles.
     */
    fun resolveTier(category: KiwixCategory, tier: KiwixTier): List<CatalogItem> {
        val bySlug = category.tiers.associateBy { it.slug }
        val seen = mutableSetOf<String>()
        val out = LinkedHashMap<String, CatalogItem>()
        var current: KiwixTier? = tier
        val chain = mutableListOf<KiwixTier>()
        while (current != null && seen.add(current.slug)) {
            chain += current
            current = current.includesTier?.let { bySlug[it] }
        }
        // Base tiers first so the list reads Essential → Comprehensive.
        for (t in chain.asReversed()) {
            for (r in t.resources) {
                r.toItem(ResourceKind.ZIM)?.let { out.putIfAbsent(it.id, it) }
            }
        }
        return out.values.toList()
    }
}

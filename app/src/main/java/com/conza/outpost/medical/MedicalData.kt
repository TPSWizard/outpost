package com.conza.outpost.medical

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Condition(val slug: String, val label: String, val category: String, val searchTerms: List<String> = emptyList())

@Serializable
data class ConditionsFile(val version: String = "", val conditions: List<Condition>)

@Serializable
data class Remedy(
    val slug: String,
    val name: String,
    val commonNames: List<String> = emptyList(),
    val conditions: List<String> = emptyList(),
    val uses: String = "",
    val how: String = "",
    val evidence: String = "",
    val cautions: String = "",
    val sourceUrl: String = "",
)

@Serializable
data class RemedySource(val name: String = "", val url: String = "", val license: String = "")

@Serializable
data class RemediesFile(val version: String = "", val source: RemedySource = RemedySource(), val remedies: List<Remedy>)

/**
 * NOMAD's curated conditions + remedies (CDC home measures, NCCIH herbs). Public-domain US
 * government content, bundled in the APK (it's tiny), so it works with no download.
 */
class MedicalData(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun read(ctx: Context, name: String) = ctx.assets.open("medical/$name").bufferedReader().use { it.readText() }

    val conditions: List<Condition> = json.decodeFromString(ConditionsFile.serializer(), read(context, "conditions.json")).conditions
    private val home = json.decodeFromString(RemediesFile.serializer(), read(context, "home_remedies.json"))
    private val natural = json.decodeFromString(RemediesFile.serializer(), read(context, "natural_remedies.json"))

    val homeRemedies: List<Remedy> get() = home.remedies
    val naturalRemedies: List<Remedy> get() = natural.remedies
    val homeSource: RemedySource get() = home.source
    val naturalSource: RemedySource get() = natural.source

    fun condition(slug: String) = conditions.firstOrNull { it.slug == slug }
    fun remedy(slug: String) = (homeRemedies + naturalRemedies).firstOrNull { it.slug == slug }
    fun isNatural(r: Remedy) = naturalRemedies.any { it.slug == r.slug }
    fun remediesFor(slug: String) = (homeRemedies + naturalRemedies).filter { slug in it.conditions }
}

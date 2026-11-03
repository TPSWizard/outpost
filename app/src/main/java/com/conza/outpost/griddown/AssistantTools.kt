package com.conza.outpost.griddown

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** An action the model proposed. Nothing runs until the user taps Confirm. */
@Serializable
sealed class ProposedAction {
    abstract val label: String

    @Serializable
    data class AddSupply(val category: String, val name: String, val quantity: Double, val unit: String = "",
                         val litersPerUnit: Double? = null, val caloriesPerUnit: Double? = null) : ProposedAction() {
        override val label get() = "Add %s %s %s to supplies".format(fmt(quantity), unit, name).replace("  ", " ")
    }

    @Serializable
    data class WaterDose(val liters: Double, val bleachPercent: Double = 6.0, val cloudy: Boolean = false) : ProposedAction() {
        override val label get() = "Calculate bleach for %s L of %s water".format(fmt(liters), if (cloudy) "cloudy" else "clear")
    }

    @Serializable
    data class Checklist(val title: String, val items: List<String>) : ProposedAction() {
        override val label get() = "Save checklist \"$title\" (${items.size} items) as a note"
    }

    @Serializable
    data class MapPin(val name: String, val lat: Double, val lon: Double) : ProposedAction() {
        override val label get() = "Drop pin \"$name\" at %.5f, %.5f".format(lat, lon)
    }

    companion object { fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else "%.2f".format(d).trimEnd('0') }
}

@Serializable
data class ActionState(val action: ProposedAction, val status: String = "proposed", val result: String? = null)

object AssistantTools {
    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }
    val stateJson = Json { ignoreUnknownKeys = true; classDiscriminator = "kind" }

    /** Instructions appended to the system prompt so small models emit parseable actions. */
    const val TOOL_PROMPT =
        "If the user asks you to record, save, add, calculate a bleach dose, make a checklist, or mark a place, " +
            "put each action on its own line at the very end, exactly like:\n" +
            "ACTION {\"type\":\"add_supply\",\"category\":\"WATER\",\"name\":\"bottled water\",\"quantity\":24,\"unit\":\"bottle\",\"liters_per_unit\":0.5}\n" +
            "ACTION {\"type\":\"water_dose\",\"liters\":20,\"bleach_percent\":6,\"cloudy\":false}\n" +
            "ACTION {\"type\":\"checklist\",\"title\":\"Go bag\",\"items\":[\"Water\",\"Meds\"]}\n" +
            "ACTION {\"type\":\"map_pin\",\"name\":\"Creek\",\"lat\":27.95,\"lon\":-82.45}\n" +
            "Categories: WATER, FOOD, MEDICINE, FUEL, BATTERIES, OTHER. The user confirms each action before it runs."

    private val ACTION_LINE = Regex("""(?m)^\s*ACTION\s*:?\s*(\{.*\})\s*$""")

    private val ACTION_WORDS = Regex(
        "\\b(add|record|save|log|track|remember|stock|bought|calculat\\w*|how much|dose|drops|bleach|purif\\w*|" +
            "disinfect\\w*|checklist|list|pack|go.?bag|kit|mark|pin|waypoint|location|spot|cache|stash)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Whether [question] might be asking for an action (record a supply, compute a bleach dose, make a
     * checklist, pin a place). [TOOL_PROMPT] is ~200 tokens the phone has to read before every answer,
     * so it only goes in when it could matter; a false positive costs a little time, a miss costs the action.
     */
    fun wantsAction(question: String): Boolean = ACTION_WORDS.containsMatchIn(question)

    /** Splits a reply into display text and proposed actions. Malformed actions are dropped. */
    fun parse(reply: String): Pair<String, List<ProposedAction>> {
        val actions = ACTION_LINE.findAll(reply).mapNotNull { m -> runCatching { toAction(lenient.parseToJsonElement(m.groupValues[1]).jsonObject) }.getOrNull() }.toList()
        val text = reply.replace(ACTION_LINE, "").trim()
        return text to actions
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.num(k: String) = this[k]?.jsonPrimitive?.doubleOrNull ?: str(k)?.toDoubleOrNull()

    private fun toAction(o: JsonObject): ProposedAction? = when (o.str("type")?.lowercase()) {
        "add_supply", "add_inventory" -> ProposedAction.AddSupply(
            category = SupplyCategory.of(o.str("category")?.uppercase() ?: "OTHER").name,
            name = o.str("name")?.takeIf { it.isNotBlank() } ?: return null,
            quantity = o.num("quantity")?.takeIf { it > 0 } ?: return null,
            unit = o.str("unit").orEmpty(),
            litersPerUnit = o.num("liters_per_unit"),
            caloriesPerUnit = o.num("calories_per_unit"),
        )
        "water_dose" -> ProposedAction.WaterDose(
            liters = o.num("liters")?.takeIf { it > 0 } ?: return null,
            bleachPercent = o.num("bleach_percent") ?: 6.0,
            cloudy = o["cloudy"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
        "checklist" -> ProposedAction.Checklist(
            title = o.str("title") ?: "Checklist",
            items = o["items"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() } ?: return null,
        )
        "map_pin" -> {
            val lat = o.num("lat") ?: return null
            val lon = o.num("lon") ?: return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) null else ProposedAction.MapPin(o.str("name") ?: "Pin", lat, lon)
        }
        else -> null
    }

    fun encodeStates(states: List<ActionState>): String? =
        if (states.isEmpty()) null else stateJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(ActionState.serializer()), states)

    fun decodeStates(s: String?): List<ActionState> =
        if (s.isNullOrBlank()) emptyList() else runCatching { stateJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ActionState.serializer()), s) }.getOrDefault(emptyList())
}

/** Deterministic routing: some questions must be answered by a checklist or calculator, not the model. */
object AnswerPolicy {
    sealed interface Route {
        data class FirstAidTopic(val topicId: String, val title: String) : Route
        data object WaterTreatment : Route
        data object FoodSafety : Route
    }

    private val WATER = Regex("""\b(bleach|purif|disinfect|treat(ing)? water|boil(ing)? water|safe to drink|drinkable|potable)""")
    private val FOOD = Regex("""\b(fridge|refrigerator|freezer|spoil|food (is )?safe|thaw)""")

    fun route(question: String, firstAid: FaFile?): List<Route> = buildList {
        val q = question.lowercase()
        firstAid?.let { f -> FirstAid.match(f, q)?.let { add(Route.FirstAidTopic(it.id, it.title)) } }
        if (WATER.containsMatchIn(q)) add(Route.WaterTreatment)
        if (FOOD.containsMatchIn(q)) add(Route.FoodSafety)
    }

    /** Compact household context for the system prompt (aim: < 120 tokens). */
    fun contextBlock(
        day: Int?,
        people: Int,
        pets: Int,
        climate: Climate,
        statuses: List<CategoryStatus>,
        meds: List<MedRunway>,
    ): String = buildString {
        append("Household context: ")
        if (day != null) append("day $day of a power/communications outage (${Outage.phase(day).name.lowercase()}). ")
        append("$people ${if (people == 1) "person" else "people"}")
        if (pets > 0) append(", $pets pet${if (pets == 1) "" else "s"}")
        append(", ${climate.label.lowercase()} climate. ")
        val known = statuses.filter { it.daysLeft != null }
        if (known.isNotEmpty()) append("Supplies: " + known.joinToString(", ") { "${it.category.label.lowercase()} ~%.0f days".format(it.daysLeft) } + ". ")
        meds.filter { it.med.critical }.take(3).takeIf { it.isNotEmpty() }?.let { m ->
            append("Critical medications: " + m.joinToString(", ") { "${it.med.name} ~%.0f days".format(it.daysLeft) } + ". ")
        }
        append("Use these numbers when relevant.")
    }.trim()
}

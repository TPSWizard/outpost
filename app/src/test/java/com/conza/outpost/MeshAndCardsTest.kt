package com.conza.outpost

import com.conza.outpost.data.HouseholdMember
import com.conza.outpost.data.InventoryItem
import com.conza.outpost.data.MapMarker
import com.conza.outpost.data.MeshMessage
import com.conza.outpost.data.Medication
import com.conza.outpost.data.Note
import com.conza.outpost.data.PlanItem
import com.conza.outpost.griddown.Cards
import com.conza.outpost.griddown.Climate
import com.conza.outpost.griddown.FirstAid
import com.conza.outpost.griddown.GridSnapshot
import com.conza.outpost.griddown.MeshProto
import com.conza.outpost.griddown.Rationing
import com.conza.outpost.griddown.Sync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class MeshAndCardsTest {
    // ---- Sync bundle merge ----
    private val pinA = MapMarker(id = 1, name = "Creek", lat = 27.9, lon = -82.4, uuid = "pin-a")
    private val item = InventoryItem(id = 7, uuid = "item-1", category = "WATER", name = "Jugs", quantity = 4.0, unit = "gal", updatedAt = 1_000)

    @Test fun bundleRoundTrips() {
        val b = Sync.bundle("Pixel A", listOf(pinA), listOf(item), listOf(Note(title = "Plan", body = "Meet at the church")), emptyList(), now = 5)
        val back = Sync.decode(Sync.encode(b))
        assertEquals(b, back)
    }

    @Test fun mergeAddsNewPinsOnceAndKeepsExisting() {
        val pinB = pinA.copy(id = 0, uuid = "pin-b", name = "Well")
        val b = Sync.bundle("Pixel B", listOf(pinA, pinB, pinB), emptyList(), emptyList(), emptyList())
        val plan = Sync.plan(b, listOf(pinA), emptyList(), emptyList(), emptySet(), mergeSupplies = false)
        assertEquals(listOf("pin-b"), plan.pins.map { it.uuid })
        assertEquals(0L, plan.pins.first().id) // inserted as new rows
    }

    @Test fun suppliesMergeOnlyForFamilyAndNewerWins() {
        val newer = item.copy(id = 99, quantity = 2.0, updatedAt = 2_000)
        val fresh = item.copy(id = 50, uuid = "item-2", name = "Rice", category = "FOOD", updatedAt = 10)
        val b = Sync.bundle("Pixel B", emptyList(), listOf(newer, fresh), emptyList(), emptyList())
        assertTrue(Sync.plan(b, emptyList(), listOf(item), emptyList(), emptySet(), mergeSupplies = false).let { it.newItems.isEmpty() && it.updatedItems.isEmpty() })
        val p = Sync.plan(b, emptyList(), listOf(item), emptyList(), emptySet(), mergeSupplies = true)
        assertEquals(1, p.updatedItems.size)
        assertEquals(7L, p.updatedItems.first().id) // keeps the local row id
        assertEquals(2.0, p.updatedItems.first().quantity, 0.0)
        assertEquals(listOf("item-2"), p.newItems.map { it.uuid })
        assertEquals(0L, p.newItems.first().id)
        // Older remote edit loses.
        val older = Sync.bundle("Pixel B", emptyList(), listOf(item.copy(quantity = 9.0, updatedAt = 500)), emptyList(), emptyList())
        assertTrue(Sync.plan(older, emptyList(), listOf(item), emptyList(), emptySet(), true).updatedItems.isEmpty())
    }

    @Test fun notesAndMessagesAreNotDuplicated() {
        val msg = MeshMessage(uuid = "m1", transport = "nearby", from = "B", text = "Water at noon", outgoing = true)
        val b = Sync.bundle("Pixel B", emptyList(), emptyList(), listOf(Note(title = "Plan", body = "Meet at the church"), Note(title = "New", body = "Fresh")), listOf(msg))
        val p = Sync.plan(b, emptyList(), emptyList(), listOf(Note(title = "Mine", body = "Meet at the church")), setOf("m1"), false)
        assertEquals(listOf("From Pixel B: New"), p.notes.map { it.title })
        assertTrue(p.messages.isEmpty())
        assertEquals("1 note", p.summary)
        val p2 = Sync.plan(b, emptyList(), emptyList(), emptyList(), emptySet(), false)
        assertFalse(p2.messages.first().outgoing)
    }

    // ---- Meshtastic protobuf ----
    @Test fun textPacketEncodesAndDecodes() {
        val toRadio = MeshProto.text("Need water at the school", packetId = 0x1234, to = MeshProto.BROADCAST)
        // Wrap the ToRadio.packet bytes as FromRadio.packet (field 2) with a sender, as a radio would deliver it.
        val packet = MeshProto.fields(toRadio)[1]!!.first() as ByteArray
        val withFrom = MeshProto.Writer().fixed32(1, 0xA1B2C3D4).toByteArray() + packet
        val fromRadio = MeshProto.Writer().bytes(2, withFrom).toByteArray()
        val m = MeshProto.decodeFromRadio(fromRadio) as MeshProto.FromRadio.Text
        assertEquals("Need water at the school", m.text)
        assertEquals(0xA1B2C3D4, m.from)
        assertEquals(MeshProto.BROADCAST, m.to)
        assertEquals(0x1234L, m.id)
        assertEquals("!a1b2c3d4", MeshProto.nodeId(m.from))
    }

    @Test fun positionDecodesNegativeLongitude() {
        val pos = MeshProto.position(27.9506, -82.4572)
        val data = MeshProto.Writer().uint(1, MeshProto.POSITION_APP.toLong()).bytes(2, pos)
        val packet = MeshProto.Writer().fixed32(1, 42).message(4, data)
        val m = MeshProto.decodeFromRadio(MeshProto.Writer().message(2, packet).toByteArray()) as MeshProto.FromRadio.Position
        assertEquals(27.9506, m.lat, 1e-6)
        assertEquals(-82.4572, m.lon, 1e-6)
        assertEquals(null, MeshProto.decodePosition(MeshProto.position(0.0, 0.0)))
    }

    @Test fun nodeInfoMyInfoAndConfigComplete() {
        val user = MeshProto.Writer().string(1, "!0000002a").string(2, "Dad's radio").string(3, "DAD")
        val node = MeshProto.Writer().uint(1, 42).message(2, user)
        val n = MeshProto.decodeFromRadio(MeshProto.Writer().message(4, node).toByteArray()) as MeshProto.FromRadio.Node
        assertEquals(42L, n.num); assertEquals("Dad's radio", n.longName); assertEquals("DAD", n.shortName)
        val my = MeshProto.decodeFromRadio(MeshProto.Writer().message(3, MeshProto.Writer().uint(1, 7)).toByteArray())
        assertEquals(MeshProto.FromRadio.MyInfo(7), my)
        assertEquals(MeshProto.FromRadio.ConfigComplete(99), MeshProto.decodeFromRadio(MeshProto.Writer().uint(7, 99).toByteArray()))
        assertEquals(MeshProto.FromRadio.Other, MeshProto.decodeFromRadio(byteArrayOf(0x12, 0x7F))) // truncated → ignored, no crash
        assertEquals(listOf(0x18, 0x05), MeshProto.wantConfig(5).map { it.toInt() })
    }

    // ---- Printable cards ----
    private val firstAid by lazy { FirstAid.parse(File("src/main/assets/griddown/firstaid.json").readText()) }
    private val family = listOf(
        HouseholdMember(id = 1, name = "Justin", birthYear = 1988, medicalNotes = "Penicillin allergy"),
        HouseholdMember(id = 2, name = "Sam", birthYear = 2016),
        HouseholdMember(id = 3, name = "Rex", isPet = true, petType = "dog", weightKg = 30.0),
    )
    private val meds = listOf(Medication(id = 1, memberId = 1, name = "Insulin glargine", dosesPerDay = 1.0, onHand = 20.0, critical = true))

    @Test fun doseTimes() {
        assertEquals(listOf("8 AM", "8 PM"), Cards.doseTimes(2.0))
        assertEquals(4, Cards.doseTimes(4.0).size)
    }

    @Test fun familyPlanHasBlanksWhenEmptyAndRealDataOtherwise() {
        val blank = Cards.familyPlan(emptyList(), emptyList(), 2026)
        assertTrue(blank.sections.first { it.first == "Rally points" }.second.first().contains("____"))
        val plan = listOf(PlanItem(type = "contact", name = "Aunt May", detail = "555-0100"), PlanItem(type = "rally", name = "Library", lat = 27.95, lon = -82.45))
        val card = Cards.familyPlan(family, plan, 2026)
        assertTrue(card.sections.first { it.first == "Household" }.second.any { it.contains("Justin, age 38") && it.contains("Penicillin") })
        assertEquals(listOf("Aunt May: 555-0100"), card.sections.first { it.first == "Contacts" }.second)
        assertTrue(card.sections.first { it.first == "Rally points" }.second.first().contains("27.95000"))
    }

    @Test fun medScheduleAndQuickRef() {
        val m = Cards.medSchedule(family, meds)
        assertTrue(m.sections.first { it.first == "Justin" }.second.first().contains("CRITICAL") )
        assertTrue(m.sections.first { it.first == "Justin" }.second.first().contains("≈ 20 days"))
        val q = Cards.quickRef(firstAid, 6.0, 0)
        assertTrue(q.sections.first().second.any { it.startsWith("1 gal: 8 drops (cloudy: 16 drops)") })
        assertTrue(q.sections.any { it.first == "Severe bleeding" && it.second.size == 5 && it.second.all { s -> s.contains(" p.") } })
        assertTrue(Cards.quickRef(firstAid, 6.0, 2500).sections.first { it.first == "Boiling" }.second.first().contains("3 min"))
    }

    @Test fun phoneDiesSummary() {
        val items = listOf(InventoryItem(category = "WATER", name = "Jugs", quantity = 20.0, unit = "gal"))
        val snap = GridSnapshot(12, Climate.TEMPERATE, family, meds, items, Rationing.status(items, family, meds, Climate.TEMPERATE, 2026), Rationing.medRunway(meds))
        val card = Cards.phoneDies(snap, listOf(PlanItem(type = "rally", name = "Library")), 6.0)
        assertTrue(card.subtitle.contains("day 12"))
        assertEquals("Insulin glargine: 1×/day, ~20 days left", card.sections.first { it.first == "Critical medications" }.second.single())
        assertTrue(card.sections.first { it.first == "Supplies (days left)" }.second.any { it.startsWith("Water") })
        assertTrue(card.sections.any { it.first == "Radio" })
    }
}

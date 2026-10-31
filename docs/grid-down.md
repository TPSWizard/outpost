# Outpost: grid-down design (90-day outage)

Scenario: a family of four at home without grid power, cell service, or internet for 90 days. They have
one or two Android phones (one is a Pixel 8), a small solar panel or power bank, maybe a car, and possibly
a handheld radio. Everything has to work from what is already on the phone.

## Principles

1. **Power first.** Nothing runs in the background unless the user asked for it. Survival mode turns off
   everything that isn't essential.
2. **Deterministic over generative.** Water doses, food safety, rationing, and first aid come from
   calculators and checklists with published sources. The AI explains them and points to them; it never
   replaces them.
3. **Plain words, big targets.** One-handed use in the dark by a stressed, non-technical person.
   Red-on-black night theme, large-text mode, and nothing important hidden behind small icons.
4. **Paper is the last backup.** Anything critical can be printed or saved as a PDF card before
   batteries run out.

## Screens and navigation

The bottom bar becomes **Today · Library · Map · Assistant · Toolkit**. Content moves into the Toolkit,
next to the new pre-outage download checklist.

| Screen | Purpose |
|---|---|
| **Today** (new start screen) | Day N of the outage, battery-life estimate, alerts (low battery, supplies under 14 days, falling pressure), today's checklist by Water / Food / Health / Security / Communication / Morale |
| Household | People (age, activity, medical notes, medications), pets, climate, altitude, home location, outage start date, contacts, rally points |
| Supplies | Inventory by category, days remaining per category, medication runway, warnings |
| Calculators | Bleach dose, boiling time, fridge/freezer food safety, daily water and calorie needs |
| First aid | Step-by-step checklists for bleeding, burns, fractures, hypothermia, heat illness, dehydration, and infection, each step citing a manual page |
| Weather | Barometric trend (phone sensor), sunrise/sunset, moon phase |
| Power | Survival mode, battery estimate, power budget planner, background-activity audit |
| Radio | Frequency reference (FRS/GMRS/MURS/CB/marine/NOAA/ham), message log, listening schedule |
| Family mesh | Phone-to-phone sharing (Nearby Connections): notes, pins, supplies, messages, content files |
| Meshtastic (experimental) | LoRa radio over Bluetooth LE: text messages and positions shown as map pins |
| Print cards | PDF cards: family plan, medications, water and first-aid quick reference, neighborhood map |
| Backup | Back up and restore all personal data to an SD card or USB drive |
| Before the outage | Checklist of what to download and prepare while the internet still works |
| Check content | Confirms every downloaded file still opens |

## Data model (Room migration v2 → v3)

The migration only adds tables and nullable or defaulted columns, so it is a Room `AutoMigration`; no
existing data changes.

New tables:
- `household_members`: name, birth year, activity level, medical notes, is-pet flag, pet weight
- `medications`: member, name, dose per day, count on hand, critical flag, notes
- `inventory_items`: category (WATER, FOOD, MEDICINE, FUEL, BATTERIES, OTHER), name, quantity, unit,
  liters per unit, calories per unit, daily use (for fuel and batteries), expiry, updated time, stable UUID
- `checklist_done`: task id + outage day
- `pressure_samples`, `battery_samples`: timestamped sensor readings
- `plan_items`: contacts and rally points (with optional coordinates)
- `radio_log`: time, channel or frequency, direction, station, text
- `mesh_messages`: peer, text, time, direction

New columns:
- `chunks.sourceRef` and `chunks.page`, so search results inside a PDF can cite and open the right page
- `chat_messages.actionState`, which records proposed tool actions and whether the user confirmed them
- `map_markers.uuid`, so pins can be merged between phones

Household settings (outage start date, climate, altitude, home coordinates) are in `SharedPreferences`
next to the existing settings.

## Power strategy

- **Measured, not guessed.** `BatterySampleWorker` records the battery level once an hour, and the app
  records it whenever it opens or closes. The estimate uses only intervals spent on battery
  (charging excluded), with recent hours weighted more, and is shown as "≈ 3.5 days at current use".
- **Survival mode** (one switch):
  - Red-on-black theme and the lowest refresh rate the display supports.
  - Downloads paused and background search indexing stopped.
  - The AI model unloaded when you leave the Assistant.
  - The map shows a "tap to load map" placeholder instead of rendering automatically.
  - Battery sampling drops to every 3 hours.
- **AI cost per answer.** The benchmark reads the battery charge counter (µAh) before and after
  generating a fixed number of tokens. That gives a cost per answer in battery %; if the counter is too
  coarse, it falls back to an estimate based on power draw, labeled as an estimate.
- **Power budget.** Inputs: phone battery capacity, solar watts × sun hours × efficiency, power bank
  Wh, car charging hours, and planned use (screen hours, AI answers, map minutes). Output: daily energy
  in versus out, sustainable days, and a plain charging plan.
- **Audit.** Lists scheduled background work, whether battery optimization applies to Outpost, and
  battery drain measured with survival mode on versus off.
- **Low battery.** Below 15%, Today shows a banner that creates the "if this phone dies" card.

## Mesh protocol choice

- **Phone to phone: Google Nearby Connections.** It works over Bluetooth, BLE, and Wi-Fi Direct with no
  router or internet, handles discovery and encryption, and sends large files (whole ZIMs). It needs
  Google Play Services, which the Pixel 8 and most Android phones have. Data is exchanged as a JSON
  "sync bundle" (notes, pins, supplies, messages), merged by stable IDs and last-modified time.
- **Long range: Meshtastic over BLE.** Outpost implements just enough of the Meshtastic phone API (the
  `ToRadio`/`FromRadio` protobufs) to send and receive text and read positions. The protobuf encoding is
  hand-written and unit-tested. It is marked experimental because it hasn't been tested with a real
  radio. MeshCore uses a different protocol and is not included.

## Assistant changes

- **Context.** A compact block (Day N, household summary, supplies days-left, critical medication
  runway) goes into the system prompt, under 120 tokens.
- **PDF search.** Field manuals and imported PDFs are turned into text page by page with PDFBox-Android
  and indexed by `RetrievalIndex` with page numbers. A cited source opens the PDF at that page.
- **Tool actions.** The model can propose `ACTION {json}` lines: add a supply, compute a water dose,
  create a checklist, or drop a pin. Each appears as a card that runs only when the user taps Confirm.
  Water-dose results always come from the calculator.
- **Answer policy.** A keyword router sends first-aid and water-treatment questions to the matching
  checklist or calculator card first, then the model answers with that card's content as a source.
- **Answer length.** "Short" (≤ 80 words, 200-token cap) saves battery; "Explain" allows 768 tokens.
- **Always a source.** Answers list their numbered sources (articles, notes, PDF pages). An answer with no source
  says so: "the model's general knowledge, not your library".

## Phased plan

1. Goals 1–4 and the assistant changes in goal 6, with unit tests for every calculator, rule, and
   decision tree, plus a JVM test for PDF extraction and page-cited retrieval.
2. Goal 7 (cards, backup, content check), goal 8 (content pack), and the pre-outage checklist.
3. Goal 5: radio reference and log, the Nearby family mesh, and Meshtastic (experimental).

## Dependencies added

| Dependency | License | Size added | Why |
|---|---|---|---|
| `com.tom-roush:pdfbox-android` 2.0.27.0 | Apache-2.0 | ~3.3 MB AAR (~1.5 MB after R8) | Pure-Java PDF text extraction. MuPDF is AGPL and ~10 MB of native code per ABI; Android's `PdfRenderer` can't extract text. |
| `com.google.android.gms:play-services-nearby` 19.5.1 | Android SDK License (proprietary) | ~0.4 MB | Offline phone-to-phone discovery and file transfer over Bluetooth/Wi-Fi Direct |
| `org.apache.pdfbox:pdfbox` 2.0.37 (tests only) | Apache-2.0 | 0 (not shipped) | JVM test for page-cited PDF retrieval; same API as the Android port |

Not added:
- **ML Kit barcode scanning plus CameraX (~3–5 MB):** an offline barcode is just a number with no product
  database behind it, so supplies are entered by hand.
- **Weather and astronomy libraries:** the barometer trend and NOAA sunrise/moon formulas are a few
  dozen lines each.

## Grid-down essentials catalog (goal 8)

Every URL was fetched and checked to start with `%PDF` on 2026-10-31. Public domain only.

| Id | Title | Source |
|---|---|---|
| `usda-home-canning` | USDA Complete Guide to Home Canning (2015) | archive.org copy of the USDA publication |
| `usda-farm-garden-1922` | USDA Farmers' Bulletin 937: The Farm Garden in the North | archive.org (USDA) |
| `usda-garden-plans-1941` | USDA Plans for the Home Vegetable Garden | archive.org (USDA) |
| `usda-farmstead-water-1925` | USDA Farmers' Bulletin 1448: Farmstead Water Supply (wells, springs, cisterns) | archive.org (USDA) |
| `ncvec-technician-pool` | FCC Technician question pool 2026–2030 | ncvec.org |
| `ncvec-general-pool` | FCC General question pool 2023–2027 (6th errata) | ncvec.org |
| `ncvec-extra-pool` | FCC Extra question pool 2024–2028 (4th errata) | ncvec.org |

Already in the catalog: FEMA *Are You Ready?* (covers power outages, generator safety, and carbon monoxide),
*Nuclear War Survival Skills*, and the Army survival, first-aid, hygiene, and security manuals.

Not bundled, and why:
- **Red Cross guides** are copyrighted.
- **Ready.gov, CDC, CPSC, OSHA, and EPA PDFs** (generator and CO safety, private wells) couldn't be fetched from the
  build environment, which got HTTP 403 from those hosts, so their URLs couldn't be checked. Add them later only
  after checking them.
- **Off-grid solar.** No public-domain consumer guide was reachable (NREL's server refused connections). The Power
  screen's budget covers sizing, and iFixit and DIY Stack Exchange cover the practical side.
- **Seed saving.** Modern seed-saving books are copyrighted. The 1922 and 1941 USDA garden bulletins and the
  Gardening Stack Exchange ZIM cover it.

Recommended Kiwix ZIMs. All of them are in the bundled catalog under Content → Reference: `zimgit-water_en`,
`zimgit-post-disaster_en`, `zimgit-food-preparation_en`, `zimgit-knots_en`, `zimgit-medicine_en`, `ifixit_en_all`,
`gardening.stackexchange.com_en_all`, `diy.stackexchange.com_en_all`, `cd3wdproject.org_en_all`.

## Safety and scope

Defensive and protective only:
- In scope: first aid, sanitation, home security, communications, preparedness, and firearm safety and
  maintenance from public manuals.
- No content or features for building weapons, explosives, or harming people.
- Medical screens carry a short "not a substitute for a clinician" note and cite the published
  protocol they follow.

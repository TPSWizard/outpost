# Outpost bushcraft and field knowledge audit

## Implementation update (working tree, 2026-11-15)

The counts and gaps below describe the original source and live v1.2.2 APK at
the start of the audit. The current working tree adds 20 reviewed, bundled
field cards, with direct access from Today and Toolkit. The cards cover water,
food, navigation, weather, shelter, fire, sanitation, being lost, rescue signals,
first aid, outages, and civilian shelter during violent, chemical, blast,
drone, and fallout hazards. Each card includes use conditions, equipment,
steps, limitations, a help threshold, and public sources. They are part of the
app assets and need no separate download or model.

The working tree also separates text-first survival ZIMs from optional videos,
adds a compact recommended three-manual pack, corrects the basemap wording,
labels scanned PDFs that cannot supply searchable text, adds public analytic
reading and a local decision notebook, and routes urgent safety questions to
reviewed cards. The optional downloads remain choices; the files are **not**
bundled. Android CI built debug APKs, but no release APK has been built or
tested on a phone in this environment.
See [offline-ai-audit.md](offline-ai-audit.md) for the assistant pipeline and
remaining device verification.

Date: 2026-11-15. Scope: repository head `2c72f16` and the locally available
`outpost-1.2.2-phone-arm64.apk`, the build linked from conzahub.com/outpost.
This is a content and implementation audit, not a field test or an inventory of
what any particular phone has downloaded.

## What a fresh installation actually contains

- The APK contains catalog metadata for **28 optional PDF manuals** in seven
  groups: seven survival/fieldcraft, one navigation, four medical, seven
  grid-down essentials, three disaster, two security/self-defense, and four
  firearms titles. There are **zero embedded PDFs**.
- The APK's Kiwix catalog lists 15 Survival & Preparedness resources, four
  DIY & Repair resources, and seven Agriculture & Food resources. There are
  **zero embedded ZIM files**, map regions, or GGUF models.
- Directly usable without further content downloads: 42 outage tasks (seven
  water, seven food, nine health, seven security, six communications, six
  morale), seven first-aid decision topics, water/food calculators, radio
  reference, and small medical JSON datasets. These are predominantly
  household outage tools, not a bushcraft curriculum.
- The live APK's extras catalog has spec `2026-10-31b`; the current source
  extras catalog has spec `2026-11-03`. The manual IDs and resource IDs are
  unchanged, but source changes after the APK build are not yet on the phone.

Evidence: `app/src/main/assets/catalog/outpost-extras.json`,
`app/src/main/assets/catalog/kiwix-categories.json`,
`app/src/main/assets/griddown/checklists.json`,
`app/src/main/assets/griddown/firstaid.json`, and inspection of the APK ZIP
entries. `CatalogRepository` loads metadata from APK assets. The downloader
fetches content separately; a `%PDF` signature proves file type, not provenance
or completeness. The before-outage screen explicitly prompts manual and ZIM
downloads.

**Conclusion:** catalog breadth is good; usable bushcraft knowledge on a
fresh offline phone is thin. The 28-manual count describes choices to download,
not pages already stored on the phone. No installed-resource database from a
user's device was available, so personal offline coverage is unknown.

## Coverage by field problem

| Field problem | Current coverage | Gap and first useful addition |
|---|---|---|
| Lost person / rescue | Map, pins, radio, FM 21-60 and FM 21-76-1 optional | A bundled decision card: assess location, retrace only if safe, call for help, stay put once rescue is expected, make yourself visible. Use the NPS emergency plan. |
| Shelter / campsite | Army survival PDFs optional | A bundled site and exposure checklist for wind, rain, falling limbs, flooding, and temperature; shelter is absent from the 42 outage tasks. |
| Water | Built-in treatment calculator and seven outage tasks; optional water ZIM and Army manuals | Explain biological versus chemical/radiological contamination. A boil/bleach instruction cannot make a chemically contaminated source safe. |
| Fire / heat | Survival PDFs optional; outage checklist covers cooking and indoor CO hazards | A fire-permission and wildfire-risk decision card, safe stove use, and how to leave a fire cold. Fire making is not always the right first action. |
| Land navigation | Optional FM 3-25.26; downloaded vector map and GPS | Offline map-and-compass practice, declination, route reversal, and printed USGS topo map import. Base map style has roads, land cover and water but no contour or elevation layer despite catalog text calling maps topographic. |
| Rope and repair | Optional rigging/mountaineering PDFs and knots ZIM; DIY Q&A catalog | Short knot selection and gear-repair cards with limits; do not imply improvised ropework is suitable for climbing or rescue loads. |
| Hygiene | Optional FM 21-10; household health tasks | Backcountry hand hygiene and human-waste disposal by local land rules. |
| Food / foraging | Optional food prep, gardening, canning, agriculture sources | A decision guide that favors carried food and vetted local identification. No universal plant-edibility test or AI-only mushroom identification. Canning needs tested process and altitude context. |
| Weather and terrain hazards | Barometer and sun/moon calculations, no forecast without external source; first-aid heat/cold topics | Lightning, flash-flood, heat and cold exposure cards; radio forecast check before losing service. |
| Decision making | Outage phases, supplies and checklists | A simple field journal that records observation, source, confidence, alternatives, decision, and what would change the decision. |

These are qualitative coverage labels, not a claim that every page in every
optional manual has been reviewed. The listed PDFs may contain much more than
their catalog descriptions, but the app does not expose that knowledge until
the user downloads them and the text is indexed.

## Content priorities

### 1. Ship a small, reliable field core inside the APK

Write and review ten short, offline cards: trip plan/ten essentials; lost and
found; shelter and exposure; water-source screening and treatment; fire and CO;
map/compass and declination; weather threats; field sanitation; food safety;
and signaling. Each should state: **when to use**, **when not to**, **source**,
**review date**, **equipment**, and **escalation point**. Include text and
simple diagrams rather than requiring a model or a multi-GB download. Keep
regional laws and restrictions as a pre-trip check, because offline copies age.

Strong primary sources:

- NPS [Ten Essentials](https://www.nps.gov/articles/10essentials.htm) and
  [Outdoor Emergency Plan](https://www.nps.gov/articles/gtgemergencyplan.htm).
- CDC [backcountry water treatment](https://www.cdc.gov/drinking-water/prevention/water-treatment-hiking-camping-traveling.html)
  and its [treatment comparison PDF](https://stacks.cdc.gov/view/cdc/115891).
- NWS [lightning safety](https://www.weather.gov/safety/lightning-tips).
- USGS [topographic maps](https://www.usgs.gov/faqs/how-do-i-find-download-or-order-topographic-maps)
  and [magnetic declination](https://www.usgs.gov/faqs/what-declination).
- NPS [backcountry waste disposal](https://home.nps.gov/articles/000/idkt_lnt_3.htm)
  and [campfire rules](https://home.nps.gov/articles/campfires.htm).
- National Center for Home Food Preservation [safe canning processes](https://nchfp.uga.edu/how/general-canning-information/ensuring-safe-canned-foods/).

### 2. Rework the optional download tiers

The current Survival Essential tier contains 20 + 97 + 27 MB of focused text
resources, plus 1,342 + 879 MB of video: **2,365 MB** of catalog estimates.
The Standard tier adds 7,777 MB; Comprehensive adds 4,757 MB, for about
**14,899 MB** cumulatively. Tier inheritance is implemented by
`CatalogParser.resolveTier`. The low-storage essential pack can be the
**144 MB** of water, food-preparation and knots resources, with a clear
separate video category. A recommended pre-trip pack should also explicitly
include the core survival, navigation, and hygiene manuals and show the
combined storage cost before download. Check current URLs, PDF text search,
metadata and distribution rights before calling a pack verified.

### 3. Add civilian intelligence and historical strategy as a reading pack

The current catalog has no dedicated CIA analysis or *Art of War* item. A
1.2 GB Project Gutenberg military-science ZIM is listed, but its actual
contents were not inspected; it should not stand in for a curated title.

- CIA [*A Tradecraft Primer*](https://www.cia.gov/resources/csi/static/Tradecraft-Primer-apr09.pdf):
  key assumptions, source-quality checks, competing hypotheses, indicators,
  and reconsidering a judgment. This is public analytic methodology.
- CIA [*Psychology of Intelligence Analysis*](https://www.cia.gov/resources/csi/books-monographs/psychology-of-intelligence-analysis-2/):
  cognitive bias and evaluation of incomplete information. Longer reading,
  lower priority for a phone's quick-reference surface.
- ODNI [ICD 203 Analytic Standards](https://www.odni.gov/files/documents/ICD/ICD-203_TA_Analytic_Standards_21_Dec_2022.pdf):
  distinguish information from assumption and judgment, express uncertainty,
  assess alternatives, explain why a conclusion changes.
- Project Gutenberg [*The Art of War*, Lionel Giles translation](https://www.gutenberg.org/ebooks/132):
  a compact historical text, public domain in the US. Add as history and
  strategy, with an editorial note on its age and translation, and link broad
  lessons to preparation, logistics, terrain awareness, and avoiding needless
  conflict.
- CIA [1951 survival-course overview](https://www.cia.gov/stories/story/wilderness-survival-training-from-the-cia-archives/):
  useful historical provenance and reflection on patience, observation and
  preparation. Its original manual link currently redirects to the FOIA
  reading-room home page in this audit; do not add it as a download until the
  exact file and reuse status are independently verified. Historic field
  advice must be checked against current public-health and land guidance.

The product feature here is an **evidence notebook**, not a covert-operations
manual: a user can record a claim, source/date, what they observed, other
explanations, confidence, and an action threshold. Example: conflicting
reports about a washed-out trail or bridge. Keep the activity within public
information and personal safety; do not turn intelligence sources into
instructions for surveillance, intrusion, deception, or violence.

## Corrections to make before expanding the corpus

1. `w-treat` currently says to treat any non-bottled water by boiling or
   bleach. Add a prominent contaminated-source branch: CDC says these do not
   make chemical, toxin or radiological contamination safe. The water-source
   pin task should not imply a creek or pond is safe to consume.
2. `f-preserve` offers drying, salting and canning and points only low-acid
   canning to the USDA guide. Route all canning advice to a tested recipe and
   elevation-specific process. Avoid model-generated preservation times.
3. The assistant prompt explicitly permits an answer “from general knowledge”
   if offline sources lack the answer. For water, wild plants, medical care,
   food preservation and other hazardous field questions, the product should
   say that it cannot verify the method and open a reviewed card or source.
   Retrieved PDF passages are short and image-only PDFs produce no searchable
   text; an indexed title alone is not proof of coverage.
4. The catalog calls state PMTiles “topographic maps,” while the bundled
   vector style has no contour, hillshade or elevation source layer. Change
   that description or add real terrain data and a topo viewer.
5. Downloads currently pass a file-signature check (`%PDF`, ZIM magic, etc.)
   without a catalog hash. Add SHA-256 and source/publisher/version/license
   fields to high-trust resources. Keep source trust separate from file
   integrity: a valid PDF can still be old, wrong, or mislabeled.

## Acceptance checks for the next release

- On a clean offline installation, all core cards open with no PDF, ZIM,
  model or map download; each names its source and review date.
- A pre-trip setup view distinguishes **listed**, **downloaded**, **indexed**
  and **usable offline**; it reports storage cost for a chosen pack.
- Test five situations with sources missing and present: lost hiker,
  unknown surface water near a chemical spill, storm on an exposed ridge,
  unidentified mushroom, and low-acid canning. The assistant must not
  invent a safe procedure where a reviewed source is missing.
- Confirm that PDF viewer page numbers match citations and scanned PDFs
  visibly report that they are not searchable.
- Review URL reachability, document identity, version, publication rights,
  and extraction quality for every resource promoted to “Essential.”

This audit inspected source, APK entries and the cited primary sources. It
did not install the app on a phone, download and read all 28 manuals or 15
survival ZIMs, validate every catalog URL, or test a user's installed content.

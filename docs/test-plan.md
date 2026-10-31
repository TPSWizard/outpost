# Outpost 1.2.0 test plan (grid-down release)

Two passes: **BlueStacks** (quick UI check on a PC, no barometer, Bluetooth, or real battery) and the **Pixel 8**
(the real target). Each step lists what to tap and what you should see.

## Reporting a failure

1. Write down the step number, what you tapped, and what happened.
2. If Outpost crashed, reopen it: a **"Outpost crashed last time"** dialog shows the error. Tap **Copy & close** and paste the text.
3. If it crashed without that dialog (a native crash, or the screen froze), connect the phone with USB debugging on and run:
   ```
   adb logcat -b crash -d > outpost-crash.txt
   adb logcat -d -s AndroidRuntime:E DEBUG:* libc:* > outpost-log.txt
   ```
   On BlueStacks, turn on Settings → Advanced → Android Debug Bridge, then `adb connect 127.0.0.1:5555` first.
4. Send the step number, the copied text, and both files.

## Install

| Device | APK |
|---|---|
| Pixel 8 | `outpost-1.2.0-phone-arm64.apk` |
| BlueStacks | `outpost-1.2.0-universal.apk` (drag it onto the window) |

1.2.0 installs over 1.1.0 and keeps your data (database v2 → v3 migrates automatically). **Expected:** the app opens on the new **Today** tab. The bottom bar shows Today · Library · Map · Assistant · Toolkit, and Content has moved to Toolkit → Content & downloads.

## A. BlueStacks pass (UI and logic)

| # | Do | Expect |
|---|---|---|
| A1 | Open the app | Today shows a Battery card (BlueStacks reports a fake battery level), "No outage running", and the tool tiles |
| A2 | Tap **Before the outage** | 15 steps; downloads you already have show a check mark; steps that need internet are marked "needs internet" |
| A3 | Back → **Household** → Add person (name, birth year 1988) → Save; add a child (2016), add a pet dog (30 kg) | Each card shows water and calorie needs per day |
| A4 | Add medication for the adult: "Insulin", 1 a day, 20 on hand, Critical | Card shows "≈ 20 days" |
| A5 | Pick climate **Hot** | Water needs double |
| A6 | Set home latitude/longitude (e.g. 27.95 / -82.46) and altitude | Values stay after leaving and returning |
| A7 | Add a contact and a rally point | Listed under "Family plan" |
| A8 | Today → **Supplies** → Add → Water, "Jugs", 10, unit "gal" → Save | Water status shows days left; under 14 days it turns red and appears in the warning card |
| A9 | Tap a preset chip (e.g. "Canned beans") → Save; use − / + | Food days update immediately |
| A10 | Tap the Insulin row under "Medication runway" | Medical reference opens with "Insulin" in the search box |
| A11 | Today → **Outage started today** | Title changes to "Day 1 of the outage"; checklist groups appear (Water, Food, Health, Security, Communication, Morale); pet and medication tasks are there because of A3/A4 |
| A12 | Tick a few tasks, leave Today and return | Ticks are kept; the counters ("2/5") update |
| A13 | **Water & food** → Treat water: 1 gal, 6 %, clear | "Add 8 drops"; cloudy → 16 drops; 8.25 % → 6 drops |
| A14 | Fridge & freezer → Freezer, 30 h, full | "Safe for about 18 more hours" |
| A15 | **First aid** → Severe bleeding → Yes | Steps with "ATP 4-02.11, p. 63 · open manual at this page" links; the clinician note at the top |
| A16 | Tap a page link with the manual **not** downloaded | Dialog: "Manual not downloaded" → Field manuals |
| A17 | Toolkit → Content & downloads → Field manuals → Casualty care → download ATP 4-02.11, then repeat A15/A16 | The PDF opens at the cited page (subtitle "Opened at page 63") |
| A18 | **Weather & sky** | "This phone has no pressure sensor" (normal on BlueStacks); sunrise/sunset for 3 days; moon phase |
| A19 | **Power** | Battery card, Survival switch, budget fields; enter Solar 20 W → plan updates; audit lists "Wakelocks: None held" |
| A20 | Turn **Survival mode** on | Whole app turns red on black; Map shows "Map paused · Tap to load the map" |
| A21 | Turn Survival off; Settings → **Large text** | Text everywhere gets larger; turn it off again |
| A22 | **Radio** → Channels / Log (add an entry) / Schedule → "Start from the template" | Frequencies listed; log saved; 6 schedule slots added |
| A23 | **Print cards** → leave all ticked → Make PDF → View | Multi-page PDF opens; on BlueStacks the map page may be skipped with a note (no GL snapshot), which is acceptable |
| A24 | Toolkit → **Backup & verify** → Back up → save to Downloads | "Backup saved (2 files)"; the content check lists your downloads, all green |
| A25 | Assistant (with a model): "We have 24 bottles of water, half a liter each. Add them to supplies." | Answer plus an "Add 24 bottle bottled water to supplies" card → Confirm → "✓ Added to Supplies." and the item appears in Supplies |
| A26 | Assistant: "How much bleach for 5 gallons of cloudy water?" | A "Water treatment calculator" card above the answer; tapping it opens the calculator |
| A27 | Assistant: "How do I stop severe bleeding from a leg?" | A "First-aid checklist: Severe bleeding" card first; the answer is short (Short mode); if the manual is downloaded and indexed, a source "ATP 4-02.11 … · p. NN" opens the PDF at that page |
| A28 | Toggle **Explain** and ask again | Longer answer |
| A29 | Today → **Share nearby** | Permission prompt → Allow. Status "Looking for phones…" (BlueStacks usually can't find phones; that's fine) |

## B. Pixel 8 pass (hardware and battery)

Do A1–A28 quickly, then:

| # | Do | Expect |
|---|---|---|
| B1 | Weather & sky | A pressure reading in hPa (about 1000–1030); after 3+ hours of hourly samples a trend appears |
| B2 | Leave the phone unplugged overnight with Survival mode **off**, then a second night with it **on** | Power → "Measured idle drain: off X %/h · on Y %/h"; Y should be lower. Report both numbers |
| B3 | Survival on → check refresh rate (Developer options → Show refresh rate) | 60 Hz instead of up to 120 |
| B4 | Survival on → ask the Assistant a question → switch to the Today tab | Memory is freed (Settings → Developer options → Running services shows Outpost using less RAM) |
| B5 | Benchmark, **unplugged**, with a model installed | New row "AI battery per answer: 0.xx% (measured)". Power → "Assistant battery cost: Measured …" |
| B6 | Let the battery drop below 15 % (or test at 14 %) | A red banner "Battery 14 %. Save the 'if this phone dies' summary…" → Open → the PDF is built |
| B7 | Print cards with a map region downloaded and home location set | Last page: the map with numbered red pins and a legend |
| B8 | Two phones: both open **Share nearby** | Each sees the other → Connect → both show the same 4-character code → "Codes match: accept" on both → Connected |
| B9 | Send notes, pins & messages; on the other phone tap **Add** | "Merged from …: 2 pins, 1 note"; pins appear on the Map. With "Family phone" ticked, supplies merge too |
| B10 | Messages tab → send "test" | Appears on the other phone |
| B11 | Send a file (a downloaded field manual PDF) | It arrives in the other phone's Library → Books & field manuals |
| B12 | (Optional, Meshtastic radio) Pair the radio in Android Bluetooth settings → Meshtastic tab → Find radios → Connect | Status "Connected to …"; nodes heard; send a message from Messages with "Send over Meshtastic radio" ticked; positions from other nodes appear as blue pins |
| B13 | Backup → save to the SD card / USB-C drive → Restore it | App restarts with the same data |
| B14 | Airplane mode on, Wi-Fi off; repeat A11–A27 | Everything works with no network |

## Automated tests

`./gradlew :app:testDebugUnitTest` runs 58 JVM tests:
- calculators (EPA bleach table, CDC boiling, USDA food safety, water and calorie needs)
- rationing and medication runway, the power model and survival-mode drain comparison, pressure trends
- sunrise/sunset compared against published tables (Tampa, Seattle, Svalbard polar night), moon phases
- outage phases and checklist rules
- first-aid tree validation (no broken links, cycles, or steps without sources)
- assistant action parsing and answer routing
- PDF extraction and page-cited retrieval with desktop PDFBox
- Nearby sync merge rules
- Meshtastic protobuf encoding and decoding
- printable card contents

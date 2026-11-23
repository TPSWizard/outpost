# Food & Water implementation

Outpost 1.4.0 adds an offline food and water workspace inside the existing Kotlin/Compose app. Open **Today → Food & Water**, **Supplies → Food & Water**, or the first tile in **Toolkit**. All reference text, recipe tables, source titles, verification dates, calculations and data entry work without a network or an AI model. Opening an original source website is optional.

## Features

- Editable Tampa planning profiles for 7, 6 and 9 people, plus custom profiles. The default 7-person/30-day scenario requires 420,000 kcal and a 210-US-gallon minimum water baseline. Heat, illness, pets, food preparation and additional sanitation may increase water needs. Initial and extended horizons, calories, protein target, dietary notes, refrigeration, electric cooking and fuel assumptions are editable. Quick scenarios cover 7/30/60/90 days.
- 43 catalog foods: the exact 324-can shopping template and 20 dry/bulk/ready-meal staples. Shopping targets never create stock. Actual food and water lots use the existing Supplies quantities. Record labels, product size, servings, calories/protein per stock unit, dates, conditions, location, price, preparation needs and optional shelf-space volume. Custom food groups and quantities are supported.
- Coverage separates commercial cans, dry goods and no-cook calories. Missing label provenance remains incomplete. Existing Supplies estimates without an entered nutrition source do not count in the new calorie total. Held, compromised, unknown and opened lots are excluded; a rotation date is not an edibility certificate. Protein and water are separate budgets.
- Searchable catalogs, category filters, FIFO stock ordering, consumption and waste events, case purchasing, current replenishment counts, editable equipment checklists, and locations with optional temperature, humidity and capacity. Capacity is incomplete while lot volumes are unknown.
- 34 verified preparation records, including hot/raw variants, and four explicitly unsupported records. Pressure schedules select the exact recipe, jar size, dial/weighted gauge and entered elevation. Water-bath schedules have their own elevation-specific times. Fish pints never imply a quart process. Home-canned and commercial quality guidance is kept separate.
- Guided batches save exact recipe snapshots, canner settings, date, jars, location, harvest link, process state, inspection, notes and rotation target. Preflight and user confirmations cover loading, venting, reaching pressure/full boil, full processing, natural cooling and seal review. No automatic pressure readings or declarations of safety are made.
- Persistent monotonic timers cover ten-minute venting, processing, post-depressurization wait, water-bath removal wait and minimum seal-cooling reminder. WorkManager notifications are best effort and optional. The app must not replace supervision or an independent timer. Android can delay or suppress notifications. Same-boot restarts retain countdowns; phone reboots and imported/restored active timers require review.
- A witnessed pressure/boil loss during processing requires the full processing time again. Uncertain venting, cooling, rebooted or restored histories cannot use the restart shortcut. Suspect batches can be held or marked compromised. Failed seals do not become usable stock. Partial failures hold the entire record; separate and review jars outside the workflow before use.
- A seven-day, 21-meal rotating example menu has no-cook and cooked meals. Portions, cooking water and burner minutes are editable. Calories and protein derive from entered labels, with incomplete totals shown explicitly. Stove kW or measured grams/hour give optional fuel estimates. Scheduling does not deduct stock; confirmed consumption deducts all ingredients atomically in FIFO order and cannot run twice. Water/fuel usage is recorded separately in Supplies.
- Central Florida potato/sweet-potato and seasonal garden guides cover healthy planting stock, small plots, soil, water, straw mulch limitations and harvest handling. Harvest records allocate pounds among fresh use, preserved, spoiled and suitable planting material, and link to canning batches.
- Offline safety guides address botulism, refrigeration failure, clean water/hygiene, outdoor cooking, carbon monoxide, alarms/extinguishers, household security and safe firearm storage. They do not include injurious traps or weapon construction.

## Local architecture and migration

Room advances **3 → 4** with one additive `food_workspace` table (`id INTEGER PRIMARY KEY`, `payload TEXT NOT NULL`). Room generates the auto-migration; schemas 1–4 are committed. Upgrade paths 1→2→3→4 remain registered. No destructive migration or existing-table rewrite is used.

The workspace is typed, versioned JSON (`FoodState.format = 1`) stored transactionally through Room. It contains profiles, lot metadata keyed by existing inventory UUID, locations, targets, equipment, batches, harvests, meals and inventory events. `inventory_items` remains the single quantity ledger. Repository transactions keep deductions and their metadata/event changes together. Existing household members, supplies, notes, chats, pins, downloads and settings are retained.

Java API desugaring supplies date/time APIs on Android 7 (API 24–25). No new runtime service or account is required. Food timer jobs have no network constraint or network operation.

## Backups and rollback

**Food & Water → Backup & export** saves local JSON, inventory CSV and a printable text reference through Android's file picker. JSON includes food/water quantities and all workspace records. It omits unrelated Outpost data and downloaded content. CSV neutralizes spreadsheet formula prefixes in user text. Import is bounded to 20 MB and validates formats, dates, units, finite nonnegative quantities, IDs, recipe snapshots and schedules before writing.

On a pristine workspace, JSON import restores the saved food configuration and stock. Otherwise it adds missing IDs only; existing records win conflicts, including quantities and edited targets. This is intentionally a merge, not an overwrite restore. A pre-import snapshot is saved in internal storage and can be exported from the same screen. A full replacement restore is available through the existing full Outpost backup UI.

Full ZIP backups already contain the complete Room database, including the new table. Full restore rejects unsupported database versions before replacing live data. Both full restore and JSON import invalidate active food timer provenance, hold affected batches and clear their countdowns. Completed records are retained.

Before upgrading a production installation, export a full ZIP and copy it to independent local storage. To roll back, retain that pre-update archive and the matching signed older APK. Android may require uninstalling a newer APK before installing an older one; uninstalling deletes private data, so verify the backup first. Restore the pre-update ZIP into the matching older app. Never open a version-4 database with a version-3 app. JSON food merge cannot downgrade a Room database. Preview uses the separate `com.conza.outpost.preview` application ID and does not access production app data.

## Build and verification

Use JDK 21 and Android SDK platform 36/build-tools 36.0.0. Native dependencies are already checked in.

```sh
node scripts/build-food-content.mjs
./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon
./gradlew :app:testPreviewUnitTest :app:assemblePreview :app:lintPreview --no-daemon
```

On Windows use `gradlew.bat`. Preview arm64 output is `app/build/outputs/apk/preview/app-arm64-v8a-preview.apk`; it is labeled **Outpost Preview**. The preview workflow verifies signing, separate package/provider authorities, ABI, bundled field/food guides and on-device AI libraries before publishing its artifact. It also publishes test/lint reports and Room schemas. It uses the repository's existing development signing setup; do not treat a CI preview as a production signing-key migration.

`FoodLogicTest` covers the starter template, units, profiles, coverage/provenance, quality dates, home/commercial distinction, exact pressure/water-bath schedules, unsupported recipes/jars, interruption/reboot recovery, case rounding, harvest constraints, backup validation and menu calculations. `FoodRepositoryTest` uses Robolectric/Room to check airplane-mode content/data operations, database recreation, FIFO/atomic/double-deduction behavior, JSON restoration/conflicts, held batches, forged recipe rejection and upgrades from every committed old schema. Existing tests run in the same suites. The empty version-1 schema has no note to seed; versions 2 and 3 preserve a note, and version 3 also preserves Supplies stock.

Build/test results and any remaining limitations are recorded in `food-verification.md`. JVM airplane-mode tests are not a substitute for testing on the user's physical phone. No phone-specific performance or canner sensor testing is claimed.

## Data maintenance and verified scope

Edit `scripts/build-food-content.mjs`, then regenerate the bundled asset and [source register](food-sources.md). The generator rejects duplicate IDs, missing sources, incorrect canned totals, unsupported recipes with schedules and mismatched elevation bands. Review every changed time, pressure, preparation, jar size and source against the original official publication before updating its verification date. Do not extrapolate from similar foods.

Existing batch snapshots must continue to match an included verified recipe edition. When changing a published recipe, add a new unique recipe ID/version and retain the old immutable edition so existing records can still be reviewed/imported. Imports reject unknown or modified recipe editions. Keep source metadata and concise original offline instructions together; do not make a website link the sole instruction.

The [source register](food-sources.md) lists all official references and verification dates. USDA/UGA NCHFP supplies the canning procedures; USDA FSIS and Nebraska Extension supply commercial storage guidance; CDC/FoodSafety.gov cover emergency safety; Utah State Extension covers dry storage; UF/IFAS and Minnesota Extension cover gardening.

No approved schedule is supplied for white-flesh peaches, pumpkin puree/butter, custom vegetable broth, cream/thickened soups or unlisted seafood/custom recipes. Pressure tables above 8,000 ft are intentionally blocked in this edition; seek an exact process from Extension. These are explicit scope limits, not unverified instructions presented as approved. Nutrient values, storage capacities, garden yields and fuel rates are not invented. Water and burner values in the example menu are editable planning assumptions, not validated food-processing schedules. A qualified preservation specialist has not independently certified this application.

## Phone check in airplane mode

1. Keep the current Outpost installed; install the separate Preview APK. Open Toolkit → Food & Water.
2. Enable airplane mode and disable Wi-Fi. Browse recipes and expand their instructions/source metadata; no downloads should be needed.
3. Confirm the initial planning values: 7 people, 420,000 kcal and 210 gal for 30 days; actual inventory starts empty in a fresh preview.
4. Record an actual commercially canned lot with label nutrition and a water lot. Verify shared Supplies quantities and coverage. Switch to 6/9 people; stock must stay unchanged.
5. Export JSON/CSV to local storage. Close/reopen the app and verify lots, dates, targets and profile persist. Test import into a fresh preview only after saving any existing data.
6. Browse a pint-only fish recipe; there must be no quart option. Try 1,001 ft with weighted gauge and compare with 1,000 ft. Unsupported recipes must not offer a workflow.
7. Create a **dry-run draft with no canner/food in use**. Read its preflight, source and timer limits. Do not skip steps or use food to test software. Verify interruption states hold the batch and never make it edible.
8. Record and allocate a sample harvest; link it to a draft batch. Edit a shopping target; verify actual stock is unchanged.
9. Schedule a meal with missing ingredients; attempted deduction must leave stock unchanged. Test FIFO deduction with measured sample stock and restore your own inventory after testing.
10. Keep a paper copy of essential recipes and a separate timer available. Take a full backup before any production update.

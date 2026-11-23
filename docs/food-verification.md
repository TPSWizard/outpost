# Food & Water verification

Verification date: 2026-11-22 (America/New_York).

Verified build commit: `02b2931ac7346a3c34b02513f503e9e0d4110791` on `codex/food-storage-preservation`. Application code is unchanged from `e2ab16b`; the final commit bounds CI build parallelism and retains packaging diagnostics. [Review PR #4](https://github.com/TPSWizard/outpost/pull/4) targets the existing `codex/outpost-preview` branch. Existing commits retain their original authors; implementation commits use the repository's configured user identity. No history was rewritten or force-pushed.

## Automated results

| Check | Result / evidence |
|---|---|
| Debug tests and APK assembly | Passed: [Android verification run](https://github.com/TPSWizard/outpost/actions/runs/45105538562) |
| Preview tests | **87 passed, 0 failures**: 66 existing tests and 21 food tests (14 logic/content, 7 Room/repository) |
| Optimized Preview assembly | Passed with resource shrinking / R8 enabled |
| Full Preview lint | **0 errors**, 64 warnings, 2 hints; no errors suppressed |
| APK signature | `apksigner verify --verbose` passed |
| APK identity | `com.conza.outpost.preview`, label **Outpost Preview**, ARM64; no shared user ID; provider authorities use the Preview package |
| Bundled content / native AI | Food JSON, field guide JSON, `liboutpost_llm.so` and `libllama.so` present |
| Room version-4 schema | Regenerated schema matches the committed schema |
| Content generation | `node --check`, regeneration and git diff checks passed |
| Hosted APK | HTTP **200**, Android package MIME type, attachment filename and full-file SHA-256 match |

The [completed Preview run](https://github.com/TPSWizard/outpost/actions/runs/45105538534) contains the test/lint reports, schema and signed APK. There are no unresolved failed automated checks. Remaining lint warnings cover existing dependency/version/style issues, including transitive Bouncy Castle TLS-method warnings; they were not hidden by a baseline. Runtime behavior of those dependency warnings was outside this food-feature audit.

## Installable test download

[Download the ARM64 Food & Water Preview APK](https://conzahub.com/outpost/outpost-1.4.0-food-preview-02b2931-arm64.apk).

- Version: `1.4.0-preview` (code 14).
- Size: **33,870,757 bytes** (33.9 MB decimal).
- SHA-256: `5788520aa1aec13089f541ab6eed34107a47743ae0e935f21d424e3f5ed1fc46`.
- The public download was fetched in full and hashes to exactly the CI artifact digest.
- Open **Toolkit → Food & Water** after installation. Guides, catalog, recipes, inventory and calculations need no model or download.
- This is the separate Preview package; regular Outpost and its private data are not replaced. Keep a backup of any existing Preview data before attempting to update a Preview signed by a different CI development key.

The suite exercises the exact 324-can template, all starter dry targets, unit conversions, 6/7/9-person coverage, entered nutrition and unknown provenance, FIFO/atomic/single-use meal deduction, calendar rotation, quality versus safety, home versus commercial stock, exact recipe/gauge/altitude schedules, unsupported jars/recipes, pressure and boiling interruptions, natural cooling and seal timers, reboot/restore invalidation, database recreation, airplane-mode data/reference operations, JSON round trips/merge conflicts, legacy duplicate supply IDs and all Room upgrade paths. Existing tests run in the same suites.

Earlier failures were corrected rather than suppressed: a migration fixture attempted to insert tables before they existed; the new compatibility test initially returned an object instead of JUnit's required void/Unit; full lint also exposed two pre-existing Bluetooth return-code comparisons, now using `BluetoothStatusCodes.SUCCESS`. One optimized APK packaging attempt failed inside Android Gradle's splitter without exposing its inner cause. The final workflow runs tests/lint before assembly, uses two workers and records stacktraces; its optimized APK assembly and all verification steps passed. No application test was removed to obtain that result.

## Scope and remaining validation

The content generator produces 43 products, 34 verified preparation records, four unsupported records, 11 offline guides, 21 meal examples and 21 editable equipment items, with 57 source records. Source metadata and verification dates are bundled. Regeneration is deterministic and is checked against git.

No physical phone or real canner was used for testing in this workspace. JVM/Room tests include airplane-mode settings, but do not establish physical-device behavior. Android notifications remain best effort, and the app cannot measure pressure, venting, temperature or seal integrity. Use direct supervision and an independent timer. A qualified food-preservation specialist has not independently certified the app. The explicitly unsupported foods and elevation limits remain blocked as documented in [the implementation guide](food-storage.md).

Android SDK tooling is not installed in this Windows workspace; Android builds, tests, lint, signing/package checks and APK verification run in GitHub Actions. The preview retains the repository's development signing setup. Its package is separate from regular Outpost. A previously installed Preview from a different CI development key may not accept an in-place update; back up that Preview's data first and keep regular Outpost installed.

## Changed files

Paths below are relative to the existing preview branch used as the base of PR #4.

| Area | Files |
|---|---|
| Build / CI | `.github/workflows/outpost-preview.yml`, `app/build.gradle.kts` |
| App / navigation | `app/src/main/java/com/conza/outpost/MainActivity.kt`, `app/src/main/java/com/conza/outpost/OutpostApp.kt` |
| Room migration | `app/src/main/java/com/conza/outpost/data/Database.kt`, `app/src/main/java/com/conza/outpost/data/FoodWorkspace.kt`, `app/schemas/com.conza.outpost.data.OutpostDatabase/4.json` |
| Domain / persistence | `app/src/main/java/com/conza/outpost/food/FoodModels.kt`, `app/src/main/java/com/conza/outpost/food/FoodLogic.kt`, `app/src/main/java/com/conza/outpost/food/FoodRepository.kt`, `app/src/main/java/com/conza/outpost/food/FoodTimerWorker.kt` |
| Native screens | `app/src/main/java/com/conza/outpost/ui/food/FoodScreen.kt`, `app/src/main/java/com/conza/outpost/ui/food/FoodForms.kt`, `app/src/main/java/com/conza/outpost/ui/food/FoodEditors.kt`, `app/src/main/java/com/conza/outpost/ui/food/FoodCanning.kt` |
| Existing screen integration | `app/src/main/java/com/conza/outpost/ui/griddown/TodayScreen.kt`, `app/src/main/java/com/conza/outpost/ui/griddown/SuppliesScreen.kt`, `app/src/main/java/com/conza/outpost/ui/toolkit/ToolkitScreens.kt` |
| Restore / lint correction | `app/src/main/java/com/conza/outpost/griddown/Backup.kt`, `app/src/main/java/com/conza/outpost/griddown/MeshtasticLink.kt` |
| Bundled content | `app/src/main/assets/food/content.json`, `scripts/build-food-content.mjs` |
| Tests | `app/src/test/java/com/conza/outpost/FoodLogicTest.kt`, `app/src/test/java/com/conza/outpost/FoodRepositoryTest.kt` |
| Documentation | `README.md`, `docs/food-storage.md`, `docs/food-sources.md`, `docs/food-verification.md` |

Migration, rollback, build commands, source maintenance and the user-facing offline phone check are in [food-storage.md](food-storage.md). All official source titles, URLs and verification dates are listed in [food-sources.md](food-sources.md).

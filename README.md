# Outpost

An offline knowledge, maps, and AI app for Android, rebuilt from
[Project NOMAD](https://github.com/Crosstalk-Solutions/project-nomad). NOMAD is a Debian server that runs a set
of Docker containers. Outpost implements each of those features natively inside one Android app.

| NOMAD feature (container) | Outpost |
|---|---|
| Information Library (kiwix-serve) | **Library** tab: libzim reader with title and full-text search |
| Offline Maps (ProtoMaps + MapLibre GL) | **Map** tab: MapLibre Native reading `.pmtiles` directly, GPS, pins |
| AI Assistant (Ollama + Qdrant) | **Assistant** tab: llama.cpp on-device, or a remote Ollama / OpenAI-compatible server; retrieval over the library, notes, and documents with cited sources |
| Drug Reference, Conditions, Remedies | **Toolkit → Medical**: openFDA labels built into SQLite FTS4 on the phone; NOMAD's CDC/NCCIH data bundled |
| Notes (FlatNotes) | **Toolkit → Notes & documents**: Markdown notes plus imported text/HTML documents |
| Data Tools (CyberChef), IT-Tools | **Toolkit → Data tools**: downloadable offline web apps served from local files |
| Education (Kolibri) | **Toolkit → Education**: hands off to the official Kolibri Android app |
| System Benchmark | **Toolkit → Benchmark**: CPU, memory, storage, and tokens/sec |
| Easy Setup + content manager | **Toolkit → Content & downloads**: NOMAD's own catalogs plus AI models, tool packs, and field manuals |
| (new) Grid-down mode | **Today** tab: "Day N of the outage", daily checklists, supplies, power budget, first aid, radio, and phone-to-phone sharing (see [docs/grid-down.md](docs/grid-down.md)) |
| Supply Depot (Jellyfin, Vaultwarden, …) | Not ported: these are server apps |

## Highlights

- **Content Manager.** Uses NOMAD's catalog files unchanged (bundled snapshot plus refresh from GitHub), along with
  Outpost's own catalog of GGUF models and tool packs (`assets/catalog/outpost-extras.json`).
  - Downloads run in WorkManager foreground jobs and resume over HTTP Range, surviving restarts and reboots.
  - Files are checked by signature (PMTiles header, ZIM magic number, GGUF, ZIP) before they're registered.
  - Downloads can go to an SD card; readers scan every storage volume. Files use NOMAD's `<id>_<version>` names,
    so `.zim` and `.pmtiles` files copied over from a NOMAD server work as-is.
- **Library.** `ZimWebViewClient` serves articles from `https://zim.outpost.local/<book>/<path>` without
  touching the network, blocks every other host, and streams large media straight from the file.
- **Assistant (`ai/`).**
  - `outpost_llm.cpp` is a small JNI bridge to llama.cpp. It applies each model's own chat template (Jinja), streams
    tokens to Kotlin, reuses the KV cache across turns, and computes embeddings.
  - The CPU backends are built in every instruction-set variant (`GGML_CPU_ALL_VARIANTS`), so one APK picks the
    fastest one at runtime on old and new phones alike. The arm64 build also links Arm's KleidiAI kernels, which
    is why the catalog's chat models are Q4_0: that is the weight format those kernels (and llama.cpp's own ARM
    repacking) accelerate on any phone with dotprod/i8mm; Q4_K only gains on SME2 chips.
  - The model runs on the fast cores only. `outpost_llm.cpp` reads each core's top clock from cpufreq, keeps the
    cluster(s) within 80% of the fastest, and pins a ggml threadpool to them: ggml syncs threads at every op, so one
    thread on an efficiency core would hold the whole model to that core's pace.
  - Prompt size is budgeted (`Assistant.passageBudget`): two 700-character passages for short answers, four
    1,000-character ones for Explain mode, and the ACTION instructions only when the question could need one.
    Prompt processing on a phone runs at tens of tokens per second, so these are most of the time to first word.
  - If on-device AI once crashed while starting, the engine stays in safe mode (baseline CPU code, several times
    slower) until Settings → Leave safe mode; Settings says so.
  - Retrieval: Kiwix full-text search finds articles, the best passage of each is chosen with BM25, and notes and
    documents are searched by hybrid semantic (nomic-embed-text, the model NOMAD uses) and keyword ranking.
  - Answers cite numbered sources that open the article, note, or document.
- **Medical.** `DrugIngestWorker` streams openFDA's 14-part bulk export (~1.8 GB) through `JsonReader` into a
  resumable SQLite build, then swaps the finished database in. Search covers name or "what it treats". The
  interaction check highlights where one drug's label mentions the others.

- **Grid-down (`griddown/`, `ui/griddown/`).** Built for a 90-day outage on one phone with a solar panel.
  - **Today:** the outage day and phase, a daily checklist (water, food, health, security, communication, morale)
    tuned to the household, low-supply warnings, and the battery estimate.
  - **Power:** Survival mode (red-on-black theme, lowest refresh rate, downloads and indexing paused, AI unloaded
    when idle, map loads on tap), measured drain with Survival mode on vs. off, a solar/power-bank/car budget,
    the battery cost of an assistant answer measured by the benchmark, and a wakelock/alarm audit. Below 15 % a
    banner offers the printable "if this phone dies" summary.
  - **Supplies and household:** days of water, food, fuel, and batteries left at the family's needs (FEMA water,
    USDA calories, pets), and how long each medication will last, with a pharmacist caveat and a link to its FDA label.
  - **Calculators and first aid:** EPA bleach dosing, CDC boiling time by altitude, USDA fridge/freezer limits, and
    yes/no first-aid checklists citing Army manual pages that open the PDF at that page.
  - **Weather and sky:** barometer trend, plus sunrise, sunset, and moon phase calculated offline.
  - **Assistant:** knows the household context, searches PDFs page by page (PDFBox), proposes actions you
    confirm, sends medical and water questions to the checklists first, and has Short and Explain modes.
  - **Communications:** radio frequencies, a log, and a listening schedule; Nearby Connections sharing between
    phones (notes, pins, messages, supplies, files); Meshtastic over BLE (experimental).
  - **Paper and backup:** PDF cards (family plan, medications, quick reference, map with pins), a backup and
    restore .zip for an SD card or USB drive, and a check that downloads aren't damaged.
  - **Before the outage:** a checklist of what to download while there's still internet.
  - **Privacy:** no analytics; everything stays on the phone or SD card.

## Build

```bash
# JDK 17+, Android SDK platform 36
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:testDebugUnitTest    # catalogs, tiers, map style, PMTiles, ZIM URLs, retrieval, drug FTS
./gradlew :app:assembleRelease      # app/build/outputs/apk/release/app-{universal,arm64-v8a,...}-release.apk
```

The llama.cpp native libraries are prebuilt into `app/src/main/jniLibs/` (arm64-v8a, x86_64), so a normal
Gradle build needs no NDK. To rebuild them (NDK r27+, CMake 3.22+):

```bash
ANDROID_HOME=/path/to/sdk scripts/build-native.sh            # fetches the pinned llama.cpp commit
ANDROID_HOME=/path/to/sdk scripts/build-native.sh ~/llama.cpp  # or use a local checkout
```

The arm64 configure step also downloads the KleidiAI source tarball (llama.cpp's CMake does it; see
`app/src/main/cpp/CMakeLists.txt`). The prebuilt libraries in `jniLibs/` must be rebuilt after any change to
`outpost_llm.cpp` or that CMake file; a Gradle build alone will not pick those changes up.

`tools/llm-harness/` runs the same JNI bridge on a desktop JVM for testing without a phone.

Release APKs are signed with the debug key for sideloading. Add a real keystore before distributing.
32-bit builds include everything except on-device AI; they can still use a remote server.

Pinned toolchain: AGP 8.13.2, Kotlin 2.3.21, Compose BOM 2026.01.01, MapLibre 13.7.1, libkiwix 2.7.0,
llama.cpp `0ec4027`. Newer Compose BOMs need AGP 9.1 and compileSdk 37.

## Layout

```
catalog/   NOMAD + Outpost catalogs, parser, repository
data/      Room: installed content, notes, chats, retrieval chunks, map pins
download/  Resumable download worker (+ tool-pack unzip)
storage/   Volumes, file signatures, PMTiles header
map/       MapStyleBuilder (port of NOMAD's style generator)
library/   libzim access, ZIM URL scheme, WebView client
ai/        LocalLlm (JNI), RemoteLlm (Ollama/OpenAI), RetrievalIndex, Assistant pipeline, TextTools
medical/   Drug database + ingest worker, conditions/remedies data
bench/     Benchmark
ui/        Compose screens
cpp/       llama.cpp JNI bridge + CMake
```

## Credits & licenses

Catalogs, regional maps, and medical datasets: Project NOMAD, © Crosstalk Solutions (Apache-2.0; maps repo MIT).
Map data © OpenStreetMap contributors (ODbL) via Protomaps. MapLibre Native (BSD-2). Noto Sans (SIL OFL 1.1).
libzim/java-libkiwix (GPLv3). llama.cpp (MIT). commonmark-java (BSD-2). openFDA labels, CDC, and NCCIH content
are US government works in the public domain. AI models and tool packs are downloaded separately and keep their own licenses.
Because Outpost links GPLv3 components, Outpost as distributed is GPLv3.
Outpost is independent and is not affiliated with or endorsed by Project NOMAD or Crosstalk Solutions.

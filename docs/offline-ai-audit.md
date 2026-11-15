# Outpost offline assistant audit

Date: 2026-11-15. This audit covers the Android source in this working tree.
The last APK directly inspected for the earlier baseline was v1.2.2. Repository
main subsequently advanced to source v1.3.1, which this branch incorporates.
Android CI assembled debug APKs and passed unit tests on this branch. These
changes have **not** been released or deployed as an APK. No phone, Android
SDK, or Gradle distribution was available locally for a real latency/battery
measurement.

## What is available offline

| Component | On a fresh installation | After optional setup |
|---|---|---|
| Bundled field guide | 21 short, sourced cards in the APK | Same cards |
| Chat model | None | A downloaded GGUF runs through the app's on-device llama.cpp backend; local mode needs no server |
| PDF manuals | Catalog listings only | Downloaded PDFs open in the reader; extractable pages are indexed locally |
| Plain-text reading | Catalog listing only | Downloaded UTF-8 text opens in Library and is indexed locally |
| Kiwix libraries | Catalog listings only | Downloaded ZIMs are searched on the phone where the ZIM supports full text |
| Embedding model | None | Optional for deeper semantic ranking; keyword search works without it |

The default assistant backend is local. A remote backend remains a user setting
and cannot work during a network outage. Download a chat model and reference
files while online, keep the backend set to **On this phone**, and test a
question plus a source tap in airplane mode. A model download is roughly
0.4–2.3 GB in the current catalog. Model suitability and speed depend on phone
RAM, CPU, available storage, thermal limits, and battery state.

## Findings and changes

1. **Slow first word.** The old sourced path could search every ZIM, transfer
   all indexed passage embeddings from SQLite, load/query a separate embedding
   model, and feed four long passages to the chat model before writing a word.
   Short mode now searches text-only SQLite rows with keyword ranking, uses at
   most a small passage budget, and searches ZIMs only if local passages are
   insufficient. Background PDF/text and embedding indexing pauses while the
   assistant is open. Explain mode still does deeper retrieval and can be slow.
   These are code-path improvements, not measured phone benchmarks.
2. **Link-only answers.** The bundled guide now supplies actual steps as
   answer passages. For urgent questions about water, food preservation, wild
   foods, severe injury, exposure, and civilian attack hazards, the assistant
   returns the relevant reviewed card directly, with an openable citation. It
   does not wait for a model or invent a procedure. Ordinary questions still
   use the installed model to synthesize an answer grounded in retrieved text.
3. **Catalog is not knowledge.** Downloaded PDF text is indexed, while scanned
   or image-only PDFs are visibly marked unsearchable. The new plain-text path
   downloads, validates, reads, indexes, retrieves, and opens cited passages.
   A Project Gutenberg edition of *The Art of War* is offered as historical
   reading, not present-day emergency guidance. CIA and ODNI public analysis
   documents are optional PDFs; the CIA Psychology book has a noncommercial
   educational copying notice, so the app links to its publisher rather than
   bundling it.
4. **Provenance and integrity.** Catalog entries now show publisher, rights,
   and whether a SHA-256 is provided. The downloader verifies a full-file hash
   when a catalog item has one. The newly added items currently have **no
   pinned hashes**; their PDF magic or text check does not prove publication
   identity or completeness. Source URLs and publication rights need release
   review. Sources in a prompt are evidence, not a guarantee that a small model
   will reason correctly.

## Practical offline setup

1. Open Content → AI models and download a chat GGUF that fits the phone.
   Qwen3 1.7B is the current default recommendation; the 0.5B option uses less
   storage and RAM but will generally give simpler answers.
2. In Content → Field manuals, download the three-manual Field basics pack and
   any relevant current civilian guidance. Download the compact survival ZIM
   tier or a medical reference as storage allows. Optional videos are separate.
3. Download the historical plain-text reading only if desired. It is useful for
   broad planning ideas, not incident-specific advice.
4. Open Library and let PDF/text indexing finish. A PDF can be readable yet
   unsearchable if its pages are scanned images.
5. In Settings, choose the on-device backend. Turn on airplane mode, ask a
   non-urgent question, and open a cited downloaded source. Also test a safety
   question with the model unloaded: a bundled card should still answer.
6. Run the app's battery benchmark unplugged on the actual phone. Compare Short
   and Explain modes. Keep a paper plan and essential references independent of
   the phone.

## Verification still required before release

The [merged-source CI run](https://github.com/TPSWizard/outpost/actions/runs/43734169985)
passed `:app:testDebugUnitTest` and `:app:assembleDebug`. Its debug APKs are
test artifacts, not signed public updates.

- Build and run an APK on a real Android phone with the selected GGUF, in
  airplane mode. Confirm first-token latency, answer relevance, RAM use,
  crashes, thermal behavior, and battery cost on representative devices.
- Download the new plain-text and PDF entries on a clean install; verify exact
  document identity, extraction quality, citations, and source opening.
- Test source-missing and source-present answers for water near chemicals,
  lost person, lightning, unknown mushroom, and canning. Check that the card
  takes priority and does not imply unsafe treatment or identification.
- Pin verified SHA-256 values for release-critical downloads and verify any
  publisher version changes before updating the catalog.
- Build a release-signed APK. The current Gradle release configuration still
  uses a development signing key; no published APK contains this branch yet.

Primary guidance used for the safety core: [CDC emergency water](https://www.cdc.gov/water-emergency/about/),
[NPS outdoor emergency planning](https://www.nps.gov/articles/gtgemergencyplan.htm),
[NWS lightning safety](https://www.weather.gov/safety/lightning-tips),
[FEMA nuclear explosion sheet](https://www.ready.gov/sites/default/files/2024-03/ready.gov_nuclear-explosion-hazard-info-sheet.pdf),
and [CDC chemical emergency response](https://www.cdc.gov/chemical-emergencies/response/index.html).

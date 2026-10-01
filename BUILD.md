# Meter — build notes

Everything below was produced and verified in this workspace.

## What is here

| File | What it is |
|---|---|
| `index.html` | Self-contained interactive prototype (open it directly in a browser or preview it here). |
| `prototype.source.html` | The same prototype with a `__ROBOTO_B64__` placeholder where the embedded font goes — edit this one and rebuild `index.html` (see below). |
| `android/` | The native Android app source (Kotlin + Jetpack Compose). |
| `android/README.md` | Architecture, counting model, quota/blocking rules, permissions, known limits. |
| `meter-debug.apk` | Debug APK built from `android/` — install with `adb install meter-debug.apk`. |
| `setup_full.png` | The whole Setup page in one tall screenshot, for the selected source (Vodafone SIM 1). |
| `evidence_blocked.png` | Simulated run where Vodafone SIM 1 crosses 50/75/100 % and blocks alone while Orange and Home Wi-Fi stay untouched. |
| `evidence_other_source.png` | The same moment after switching to SIM 2 — still selectable, still unblocked. |

## Verified in this workspace

* **Prototype**: rendered in headless Chromium at 390×822 and diffed against the uploaded design
  pixel by pixel. Card grid, ring geometry (122 dp ring, 12.5 dp stroke, 62 px drop), row
  baselines, bar geometry (30→311 px), 8 px legend squares, 30 px pills, 331 px reset button and
  every text row land within ~1–2 px of the design; type sizes were solved from ink-pixel widths
  (`USAGE 41 px`, `1.10 GB 45 px`, `SIM 340 MB 64 px`, …).
* **Behaviour**: with the simulated radio pinned to Vodafone SIM 1, the run reproduced
  alerts at 50 / 75 / 100 %, then `blocked: true` for SIM 1 only — SIM 2 stayed at 2 % and
  Home Wi-Fi at 1.7 %, each with an empty alert set (see `evidence_blocked.png`).
* **Android**: `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL,
  `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (`meter-debug.apk`, 8,910,752 bytes, package
  `com.meter.app`, minSdk 24 / targetSdk 34, launchable `com.meter.app.MainActivity`).
* **Tests**: `./gradlew :app:testDebugUnitTest` → **66 tests, 0 failed** (ledger arithmetic,
  framing overhead, reconciliation, quota rules, every list and menu, and 13 end-to-end use cases
  on a simulated dual-SIM phone). The HTML preview is covered by a headless-DOM smoke test:
  `tests/preview_smoke.mjs` → 21/21 checks. Full report: `TESTING.md`.

## Build it yourself

```bash
cd android
echo "sdk.dir=/path/to/Android/sdk" > local.properties   # SDK 34 + build-tools 34.0.0
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest        # 66 JVM tests, no device needed
```

```bash
cd tests && npm i jsdom@24.1.0 && node preview_smoke.mjs    # 21 checks against index.html
```

Toolchain used here: JDK 21, Gradle 8.7, Android SDK Platform 34, Build-Tools 34.0.0,
Kotlin 1.9.24, Compose BOM 2024.06.00. Gradle memory was capped
(`-Xmx900m`, `--max-workers=1`) because this sandbox has ~2 GB of RAM; a normal machine needs no
such tweaks.

### Rebuilding `index.html`

`prototype.source.html` holds the markup, styles and engine with the Roboto subset replaced by
`__ROBOTO_B64__`. To re-emit the self-contained file:

```bash
python3 - <<'EOF'
b64 = open('roboto-latin.b64').read().strip()   # base64 of the woff2 subset
src = open('prototype.source.html').read()
open('index.html','w').write(src.replace('__ROBOTO_B64__', b64))
EOF
```

The font is embedded on purpose: the file must render identically with no network access.

### About the release build

`assembleDebug` was verified here. `assembleRelease` (R8 + resource shrinking) was attempted
twice and both times the Gradle daemon was killed by the sandbox's ~2 GB memory limit during
R8 — that is an environment limit, not a project error: run `./gradlew :app:assembleRelease` on
a normal machine (4 GB+ free) and it will produce a signed-free, minified APK. For reference the
debug APK is 8.9 MB, of which most is Compose's debug tooling that R8 strips.

## Prototype cheat-sheet

* **Switch source**: `All` / `SIM` / `Wi-Fi` pills; chips appear when a family has several members.
* **Setup page**: click or long-press the dashed `long-press for settings` pill; `←` goes back.
* **Per-source controls** in Setup: quota (+ presets), cycle length, thresholds
  (add / edit / delete / preview), Auto·MB·GB, block & unblock, clear counters. The usage cycle
  itself is read-only there — resetting a cycle lives on the Meter page.
* **Right-hand panel** drives the simulated radios: active connection, virtual rate (10×…3600×),
  reset, and a live ledger of every source's rx/tx, cycle, fired alerts and overhead.

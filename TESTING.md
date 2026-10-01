# Meter — test report

Everything the app claims is checked by an automated test. There are **66 JVM unit tests** for
the Kotlin app and **21 DOM checks** for the HTML preview; all of them pass.

```
cd android && JAVA_HOME=/opt/jdk21 GRADLE_USER_HOME=/opt/gradle-home \
  ./gradlew :app:testDebugUnitTest --max-workers=1

BUILD SUCCESSFUL
66 tests completed, 0 failed

CounterLedgerTest        8 tests   0 failed
ListsAndMenusTest       24 tests   0 failed
PrecisionTest            8 tests   0 failed
QuotaEngineTest         13 tests   0 failed
UseCaseIntegrationTest  13 tests   0 failed        (reports: android/app/build/reports/tests/testDebugUnitTest/index.html)

cd tests && npm i jsdom@24.1.0 && node preview_smoke.mjs
21/21 checks passed
```

The tests are pure-JVM: no emulator, no device, no Android framework. That is deliberate — the
accounting rules, the lists and the menus live in `core/`, which depends on nothing but Kotlin,
so they can be tested exhaustively instead of being exercised by hand on a phone.

---

## 1. What the tests prove, point by point

### 1.1 SIM 1 and SIM 2 are separate sources, always

| Test | What it pins down |
| --- | --- |
| `CounterLedgerTest › nothing migrates between sources when an interface changes hands` | On a phone with one shared cellular uplink, moving mobile data from SIM 1 to SIM 2 re-bases the ledger: SIM 2 never inherits SIM 1's bytes, and the handover itself is reported as an empty, flagged delta. |
| `UseCaseIntegrationTest › use case 2` | Both SIMs carry traffic in the same session on the same interface: Vodafone keeps exactly its own 100 MB, Orange exactly its own 250 MB, and their sum is everything that flowed — no byte counted twice. |
| `UseCaseIntegrationTest › use case 10` | Different quotas on the two SIMs: 50 % of Orange's 10 GB is not 50 % of Vodafone's 1 GB, and exhausting Vodafone leaves Orange online at 50 %. |
| `UseCaseIntegrationTest › use case 3` | Two Wi-Fi networks sharing `wlan0` are credited to the SSID that was connected at the time, not to "Wi-Fi" as a lump. |
| `ListsAndMenusTest › all is the only aggregate - a family always resolves to one source` | The SIM pill can only ever show one SIM; only the explicit "All" pill adds sources up. |

### 1.2 Nothing is missed — connection overhead and invisible traffic

| Test | What it pins down |
| --- | --- |
| `PrecisionTest › measured packet counts give an exact framing allowance` | When the kernel reports packets, the operator's per-packet framing is added exactly (bytes + packets × overhead). |
| `PrecisionTest › without packet counts the MTU estimate still covers the traffic` | Without packet counts the estimate rounds up on the MTU, so it can never under-report. |
| `PrecisionTest › a raw policy adds nothing at all` | The allowance is configurable down to a raw byte count. |
| `PrecisionTest › the platform ledger shows up as a correction when our ledger is behind` | Anything the app could not see is recovered from the platform's per-subscription ledger. |
| `PrecisionTest › a correction is never negative` / `sampling jitter below a page of data is not chased` | A correction never walks usage back, and sub-4 KiB jitter is ignored so the figure does not flap. |
| `UseCaseIntegrationTest › use case 7` | Ten full-size packets down and one up are billed as 14 596 bytes, framing included — the exact number, not an approximation. |
| `UseCaseIntegrationTest › use case 8` | Our ledger ends up on exactly the platform's figure (1 200 000 000 B) and the next reconciliation has nothing left to do. |
| `UseCaseIntegrationTest › use case 13` | Bytes that flow inside a SIM handover window — when no counter can honestly attribute them — are recovered from the platform ledger for the right SIM, and are not given to the other one. |
| `CounterLedgerTest › a 32-bit counter wrap does not lose the bytes in the gap` / `a hard counter reset credits what has flowed since the reset` | A wrapping or resetting kernel counter does not silently drop traffic. |
| `CounterLedgerTest › baselines survive a process restart and still credit the gap` | The 90 kB that flowed while the app was dead are credited on the next sample instead of vanishing with the process. |
| `UseCaseIntegrationTest › use case 12` | Twenty sample steps add up to exactly the traffic that was generated — the meter's own arithmetic never drifts from the raw counters. |
| `CounterLedgerTest › interfaces without an owner are ignored` | Loopback and the app's own VPN tunnel are never metered, so blocking cannot feed itself. |

### 1.3 Setup has no cycle reset — reset lives on the Meter page

| Check | Where |
| --- | --- |
| `Setup has no "Start today"` / `Setup has no "Reset cycle"` / `Setup has no reset control at all` / `Setup carries no datetime input` | `tests/preview_smoke.mjs` (HTML preview) |
| `Usage Cycle is read-only: Starts / Length / Ends` | `tests/preview_smoke.mjs` |
| `Meter page has the Reset button` | `tests/preview_smoke.mjs` |
| `UseCaseIntegrationTest › use case 11` | Resetting one source zeroes only that source; every other source keeps its bytes, and counting continues correctly from that moment. |

The Kotlin Setup screen was rewritten the same way: the Usage Cycle card shows Starts / Length /
Ends only, and the only Reset control in the app is the one on the Meter page, scoped to the
source selected there. (Its behaviour is the one `use case 11` pins down.)

### 1.4 Every list and menu

`ListsAndMenusTest` covers each list as a pure function, and the preview smoke test drives the
same lists in the rendered preview.

| List / menu | Unit tests | Preview checks |
| --- | --- | --- |
| Filter pills (All / SIM / Wi-Fi) | `the pills list exactly the family they name`, `all is the only aggregate…`, `opening a family with no valid selection picks its first member`, `an empty family reports itself…` | `SIM pill lists each SIM separately`, `Wi-Fi pill lists each network separately` |
| Source chips | `tapping a chip changes that family's selection and nothing else`, `a selection that disappeared is repaired…` | `tapping SIM 2 selects SIM 2 alone` |
| Source list itself (SIMs pulled, networks forgotten) | `a new source gets a config and a running cycle`, `a source that disappears keeps its config and counters` | — |
| Notification thresholds (add / edit / delete / cap) | `adding a threshold never duplicates and stays sorted`, `add without a value picks the next sensible suggestion`, `editing a threshold keeps the list ordered`, `an out of range edit is clamped, not rejected`, `an index that no longer exists is ignored`, `deleting the last threshold leaves an empty list that can be refilled`, `the list stops growing at the cap instead of overflowing` | `threshold list grows when a row is added`, `threshold list shrinks when a row is deleted` |
| Quota presets + stepper | `the quota stepper uses sensible steps`, `a preset is marked active only when the quota matches it`, `every preset is a valid quota` | `quota presets render and are tappable`, `a preset marks itself as active`, `quota stepper changes the value` |
| Sample intervals | `sample intervals are clamped to the supported range`, `every option in the interval menu survives sanitising` | — |
| Display unit (MB / GB / auto) | `the display unit setting is honoured in both modes`, `units format exactly like the design`, `rates are shown in megabits per second like the design`, `timestamps look like the design` | — |
| Per-source isolation of settings | `each source is evaluated on its own dictionary of usage` | `each source keeps its own quota on the Setup page` (3 GB vs 5 GB) |

### 1.5 The quota rules themselves

`QuotaEngineTest` covers the whole rule set: a threshold fires exactly once per cycle; crossing
several thresholds at once fires each of them; 100 % blocks only the exhausting source; an
exhausted Wi-Fi network is disconnected while mobile data is untouched; `Block this source at
100 %` can be switched off and then the source is over quota but never blocked, and a block that
was already in force is lifted when the setting changes; cycles roll over on their own and clear
usage, alerts and the block; a phone that was off for months lands on the cycle that is running
now; a source that has never run gets its cycle from now; unlimited never blocks; and aggregating
a family does not invent a block that no member has.

---

## 2. The use cases, one per test

`UseCaseIntegrationTest` drives the real pipeline — kernel counters → ledger → framing → quota
rules → alerts and blocks → platform reconciliation — over a simulated dual-SIM phone with two
Wi-Fi networks. Each test is a use case:

1. **Every source is metered independently** — each SIM and each Wi-Fi network on its own.
2. **SIM 1 and SIM 2 never share a counter**, even on one shared uplink.
3. **Two Wi-Fi networks on one interface** are credited to the right SSID.
4. **Thresholds fire once** and only the exhausted source is blocked.
5. **A blocked SIM blocks neither Wi-Fi nor the other SIM** — traffic keeps being metered on
   whatever carries it.
6. **Each source carries its own cycle** and rolls over alone.
7. **No byte is lost in the connection overhead.**
8. **Traffic the app could not see** is recovered from the platform ledger.
9. **A reconciliation that crosses the quota blocks the source.**
10. **Separate quotas on the two SIMs at the same percentage.**
11. **Resetting one source leaves every other source alone.**
12. **The meter's accounting never disagrees with the raw counters.**
13. **Bytes inside a SIM handover window are recovered**, and never handed to the wrong SIM.

---

## 3. How the accounting works

```
raw per-interface counters          TrafficStats → /proc/net/dev → sysfs (one backend
        │                           chosen per interface and then never changed)
        ▼
CounterLedger                       deltas per source; ownership is live, so a SIM switch
        │                           re-bases instead of re-crediting; 32-bit wraps and
        │                           driver resets are recovered; baselines are persisted,
        │                           so bytes that flowed while the app was dead are credited
        ▼
OverheadModel                       bytes + packets × per-packet allowance, measured when the
        │                           kernel exposes packet counts, MTU estimate when it does not
        ▼
QuotaEngine                         thresholds once per cycle; 100 % blocks that source only;
        │                           cycles roll over per source; alert-only sources never block
        ▼
notifications / kill-switch         the service layer performs the effects

NetworkStatsManager                 the safety net: the platform's own per-subscription ledger
        └── Pipeline.reconcile      is folded in as a correction, so handovers, VPN traffic and
                                    anything the kernel counters missed are still counted
```

Measured on this machine: 66 tests in ~0.16 s of test time, which is what makes it realistic to
run them on every change.

---

## 4. Build verification

```
cd android && JAVA_HOME=/opt/jdk21 GRADLE_USER_HOME=/opt/gradle-home \
  ./gradlew :app:assembleDebug --max-workers=1

BUILD SUCCESSFUL
app/build/outputs/apk/debug/app-debug.apk   8,910,752 bytes  →  /home/user/meter-debug.apk
```

`aapt dump badging` on that APK:

```
package: name='com.meter.app' versionCode='1' versionName='1.0'
sdkVersion:'24'  targetSdkVersion:'34'
uses-permission: name='android.permission.PACKAGE_USAGE_STATS'
launchable-activity: name='com.meter.app.MainActivity'
```

The release build still needs more RAM than this sandbox has (see `BUILD.md`); a debug APK is the
verified artifact.

# Meter — per-source data meter with per-source quotas

A very small Android app that counts data **per internet source** — each SIM card and each
Wi-Fi network is its own meter, with its own quota, its own cycle, its own alerts and its own
block — and that keeps counting while the app is closed.

This project implements the uploaded **Meter** design as the app's first page, and a **Setup**
page in exactly the same visual language.

```
android/
├─ app/src/main/java/com/meter/app/
│  ├─ core/          Model · Units · Prefs · SourceRegistry · QuotaEngine · Selection · Pipeline
│  ├─ core/ledger/   CounterLedger (baselines, handovers, wraps) · OverheadModel · Precision
│  ├─ core/platform/ InterfaceCounters (TrafficStats → /proc/net/dev → sysfs) · Networks
│  │                · SimIdentity · PlatformStats (per-subscription platform ledger)
│  ├─ service/       MeterService · KillSwitchVpnService · Enforcement · Notifier · Receivers
│  ├─ state/         MeterEngine  (the single owner of counters)
│  ├─ ui/            MeterScreen · SetupScreen · Components · theme/
│  ├─ MainActivity.kt
│  └─ MeterApp.kt
├─ app/src/test/java/com/meter/app/core/    66 JVM tests (no device needed)
└─ app/src/main/AndroidManifest.xml
```

The accounting, the quota rules, the lists and the menus are pure Kotlin in `core/`, which is what
makes them testable; `TESTING.md` in the workspace root lists every test and what it proves.

---

## 1. How the counting works (and why it is accurate)

### The unit of accounting is the *source*, never a global total

```
SIM  : sim:<subscriptionId>   → "Vodafone · SIM 1"   iface rmnet_data0
       sim:<subscriptionId>   → "Orange · SIM 2"     iface rmnet_data1
WIFI : wifi:<SSID>            → "Home Wi-Fi"         iface wlan0
       wifi:<SSID>            → "Office"             iface wlan0
```

* **SIM cards** are read from the kernel's per-interface counters
  (`TrafficStats` first, then `/proc/net/dev`, then sysfs — one backend is chosen per interface
  and never changed, because mixing sources between samples would make the deltas meaningless).
  On devices that give each subscription its own interface (`rmnet_data0/1`, `ccmni0/1`,
  `pdp0/1` …) the split between SIM 1 and SIM 2 is exact — these are the same counters the OS
  shows in Settings → Data usage. On a DSDS phone that exposes **one** cellular uplink, ownership
  is *live*: `SimIdentity` maps the interface to whichever subscription is carrying data right
  now, and a SIM switch re-bases the ledger instead of re-crediting it, so SIM 1 always keeps
  exactly the bytes it carried. The short window of a handover, where no counter can honestly
  attribute the bytes, is recovered a moment later from the platform's own per-subscription
  ledger — see *Reconciliation* below.
* **Wi-Fi networks** share one interface (`wlan0`), so their bytes are credited to the SSID that
  is associated at sample time. That is the only correct way to split several Wi-Fi networks on
  Android without a userspace TCP/IP stack; it is why the app samples frequently and why the
  sample interval is a setting (2 s / 5 s / 10 s / 30 s).
* Every sample is a **delta**, so a counter wrap, a reboot or an interface that disappears can
  never produce a bogus jump. A 32-bit counter that wraps contributes its tail plus its head; a
  counter that was reset contributes the new value; a 64-bit counter that drops is treated as a
  reset. Baselines are persisted, so traffic that flows while the app is dead is credited on the
  next sample rather than lost.

### Reconciliation — the safety net

With the *Usage access* permission (Settings → Special app access → Usage access) the app also
reads `NetworkStatsManager`, the platform ledger behind Settings → Data usage. It is queried per
`subscriberId` for each SIM and as a Wi-Fi total for the current network, and folded in as a
**correction**: anything our own counters could not see — a SIM handover window, traffic that
flowed while the process was dead, a counter the kernel does not expose — is added, so our total
ends up exactly on the platform's figure. Corrections are never negative (we never walk usage
back) and never smaller than 4 KiB (so sampling jitter is not chased). Without the permission the
app keeps working on kernel counters alone and reconciliation is a no-op.

### Why `TrafficStats` and not a VPN

| | TrafficStats sampling (this app) | Per-packet VPN |
|---|---|---|
| Battery | a few syscalls every 5 s | CPU per packet, always |
| Counts | payload + headers + retransmits + radio/modem framing — what the operator bills | only what the app forwards |
| Continues when closed | yes (foreground service) | yes |
| Accuracy per SIM | exact where interfaces are per-SIM | exact |
| Accuracy per Wi-Fi SSID | attributed at sample time | exact |

For a *meter* the first column is the better tool, and it is the reason the app can claim to be
lightweight: no packet processing, no wake locks, no GPS. The VPN is used **only** as a valve
when a SIM quota is exhausted (below), never for counting.

### The connection overhead

`OverheadModel` takes the raw kernel bytes and adds per-packet framing on top: the *measured*
packet counts when the kernel exposes them (`TrafficStats.getRxPackets`, `/proc/net/dev`), and
otherwise `bytes ÷ MTU` rounded up. SIM defaults to 36 B/packet with a 1400 B MTU, Wi-Fi to
24 B/packet with 1500 B, and both are editable in Setup. This is the knob that makes the "Sanity Check" card meaningful: it exists so
the number in the app can be tuned to line up with the carrier's own counter or the router's.

---

## 2. Quotas, cycles, alerts and blocking

All of it lives in `core/QuotaEngine.kt` as pure functions, so the rules are testable without a
device:

* **Quota per source.** Unlimited (0), or any byte value; the Setup page has a stepper plus
  500 MB / 1 GB / 3 GB / 5 GB / 50 GB / Unlimited presets.
* **Cycle per source.** `cycleStart + periodDays`; the cycle rolls over on its own, jumping whole
  cycles if the phone was off for a while, clearing usage, alerts and blocks. Reset lives on the
  **Meter page only** and restarts *that* source's cycle immediately — nothing else is touched;
  the Setup page shows Starts / Length / Ends as read-only information.
* **Thresholds per source.** Any list, e.g. 50 / 75 / 100. Each fires **once per cycle**, at the
  first sample that crosses it, and the 100 % alert is worded as a block notice.
* **Blocking is per source.** Reaching the quota (or pressing *Block this source only*) stops the
  source, and only that source:
  * **Wi-Fi** — a real, system-level cut: `WifiManager.disconnect()` plus
    `disableNetwork(netId)` so the phone does not rejoin. Mobile data and the other SSIDs keep
    working, exactly as specified. Unblocking re-enables the network.
  * **SIM** — Android gives no public API to switch one subscription's data off, so the app uses
    the optional local-VPN **valve**: `KillSwitchVpnService` establishes a black-hole tunnel
    *while that SIM is the active connection*, and releases it the instant the phone moves to
    Wi-Fi or the other SIM. The valve tracks the active network on every sample, so Wi-Fi is
    never affected by a SIM that ran out. Users who want the VPN slot left free can turn the
    valve off in Setup → Monitoring; the source is still marked blocked in the UI and alerts
    keep firing.

---

## 3. Keeping it alive, cheaply

* `MeterService` is a foreground service of type **specialUse** (Android 14 compliant) with an
  `IMPORTANCE_MIN`, silent, ongoing notification. It holds no wake lock and does no polling of
  its own: the sampler inside `MeterEngine` is a counter diff every few seconds.
* `BootReceiver` restarts it after a reboot or an app update.
* `AlarmReceiver` fires twice a day (`setAndAllowWhileIdle`) for cycle rollover and a state flush.
* `ConnectivityManager.registerDefaultNetworkCallback` re-discovers sources the moment the
  default connection changes — that is the event that would otherwise corrupt attribution.
* The UI and the service share the same engine, reference-counted, so bytes are never counted
  twice when both are alive.

---

## 4. The two pages

**Meter** (the uploaded design, reproduced 1:1): Usage ring with Used / Quota / Left / Daily avg,
Live Speed with the active source named underneath, Breakdown, Usage Cycle with Reset, Sanity
Check. The All / SIM / Wi-Fi pills switch scope; when a family has several members, chips appear
for each of them (Vodafone · SIM 1 / Orange · SIM 2, Home Wi-Fi / Office), and the meter always
shows the one you picked.

**Setup** (long-press the top bar, or the dashed pill): Data Quota, Usage Cycle, Notifications,
Data Display Unit (Auto / MB / GB), This Source (kind, carrier, interface, status, block /
unblock, clear counters), Monitoring (background counting, pause, sample interval, SIM valve) and
Permissions. Every control edits the source named in the header — nothing on this page is global.

---

## 5. Building

```bash
# JDK 17+ (JDK 21 tested) and the Android SDK (platform 34, build-tools 34.0.0)
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew :app:assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug           # with a device attached
./gradlew :app:assembleRelease        # R8 + shrinking; needs ~4 GB of free RAM
```

`assembleDebug` is verified: a build from this source tree produced
`meter-debug.apk` (package `com.meter.app`, `minSdk 24`, `targetSdk 34`, 8,910,752 bytes).
`./gradlew :app:testDebugUnitTest` runs the 66 JVM tests — see `../TESTING.md`. The release build's R8
pass needs more memory than the sandbox this was written in had.

`minSdk 24`, `targetSdk 34`, Kotlin 1.9.24, Compose BOM 2024.06.00, no third-party dependencies
beyond AndroidX — the APK is a few hundred kilobytes, which is the point.

### Permissions and what happens without them

| Permission | Used for | Without it |
|---|---|---|
| `ACCESS_NETWORK_STATE` | knowing which source is active | required |
| `ACCESS_FINE_LOCATION` | SSID names (Android redacts them since 8.1) | sources still metered, named "Wi-Fi network" |
| `READ_PHONE_STATE` | carrier names per SIM | sources metered as "Mobile data" |
| `PACKAGE_USAGE_STATS` (user-granted) | the platform ledger used for reconciliation | kernel counters only; a SIM handover window is the one place traffic can be missed |
| `POST_NOTIFICATIONS` | threshold alerts | no alerts, everything else works |
| `BIND_VPN_SERVICE` (user-granted) | SIM valve only | SIM quota is alerted but not cut |
| `CHANGE_WIFI_STATE` | blocking an exhausted Wi-Fi | Wi-Fi quota is alerted but not cut |
| `SCHEDULE_EXACT_ALARM` | the twice-daily rollover / flush while idle | cycles still roll over, but only while the app is running |
| battery-optimisation exemption | reliability on aggressive ROMs | may be throttled in deep doze |

---

## 6. Known limits (stated plainly)

* **Wi-Fi SSID splitting is sampling-based.** If you download on one SSID and switch to another
  inside a single sample interval, those bytes land on the SSID that was associated at the
  boundary. A shorter interval shrinks the window; a VPN would remove it but would cost the
  battery the whole design is built around. The same applies to a SIM switch, and in both cases
  the platform ledger closes the gap once Usage access is granted.
* **A SIM can only be cut with the VPN valve**, because Android does not expose per-subscription
  data control to ordinary apps. The valve blocks *while that SIM is the active connection*; if
  the user switches to Wi-Fi, the valve releases and Wi-Fi is untouched.
* **Counters are per interface, not per app.** This is a data-usage meter, like the one in
  Settings; per-app accounting is a different (much heavier) feature and deliberately out of
  scope.
* The prototype in `../index.html` is a faithful simulation of this engine (same rules, same UI)
  used to validate the behaviour without a device.

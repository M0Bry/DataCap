# Meter — what every control on the Setup page does

Setup is **page 2 of 2**. The rule that governs the whole page: **the name in the header is the
source you are editing**, and every control below it belongs to that source only. Nothing on this
page is a global setting except the Monitoring and Permissions cards.

Companion pages: `index.html` (the design preview), `TESTING.md` (proof the rules behave),
`android/README.md` (architecture).

---

## 1. Header and source chips

| Element | What it is |
| --- | --- |
| `←` | Back to the Meter page. |
| `SETUP` + coloured dot + name | The source being edited. The dot is **green = live**, **red = this source is blocked** (quota spent, valve closed, or blocked by hand). |
| `wlan0` (right) | The kernel interface whose counters this source is metered from. `rmnet_data0/1` = a SIM uplink, `wlan0` = the Wi-Fi uplink. |
| Chips row | Every source the app knows: **one chip per SIM** (carrier + slot) and **one chip per Wi-Fi network** (name + band). The selected chip carries the green dot; a blocked chip turns red. Tapping a chip moves the whole page *and* the Meter page to that source. |

On the phone in the screenshots: Orange SIM 1, vodafone SIM 2, and one Wi-Fi network. The Wi-Fi
chip reads **"Wi-Fi network · 2.4 GHz"** instead of the real SSID because Android is withholding
the network name — see §8.

---

## 2. Data Quota (per source)

* **Big number + `−` / `+`** — this source's allowance. The step adapts to the magnitude
  (100 MB below 1 GB, 500 MB between 1–10 GB, 1 GB above 10 GB) and snaps to that grid, so
  stepping away from a preset and back lands exactly on it again. `0` / `∞` = **Unlimited**.
* **Preset chips** — 500 MB · 1 GB · 3 GB · 5 GB · 20 GB · 50 GB · Unlimited. The one whose value
  matches exactly is highlighted. Purely a shortcut for the stepper.
* **Used this cycle** — live usage for this source in its current cycle, plus the same figure as a
  percentage of its quota (74 MB of a 500 MB quota ≈ 14 %). Read-only.
* **Block this source at 100 %** — the switch that decides what "quota spent" means for this
  source:
  * **on** → at 100 % this source *and only this source* is cut (Wi-Fi: the network is disabled
    and disconnected; SIM: the local-VPN valve closes while that SIM is the active connection);
  * **off** → alerts only. The source keeps working and is shown as over quota, never cut.
* **Per-packet overhead** — the framing the operator bills on top of the IP payload (radio and
  transport headers, retransmits). The meter adds `measured packets × this value` when the kernel
  reports packet counts, and `bytes ÷ MTU` rounded up when it does not. Defaults are **36 B mobile,
  24 B Wi-Fi**; `−`/`+` move it 4 B at a time (0–200 B). Raise it if your carrier's figure is
  higher than the app's, lower it if it is lower. It affects *this source's totals only*.

## 3. Usage Cycle (read-only except the length)

* **Starts** — when this cycle began (set when the source was first seen, and by Reset).
* **Length** — cycle length in days, 1–365, the only editable field here.
* **Ends** — `Starts + Length`, computed live. When it passes, the cycle **rolls over on its own**:
  usage, alerts and the block are cleared and the next cycle begins (whole cycles are skipped if
  the phone was off for a while).
* The paragraph underneath states the rule you asked for: **the cycle is started and restarted
  from the Meter page**, where Reset belongs to the source selected there. Setup never resets a
  cycle, and resetting one source never touches another.

## 4. Notifications (per source)

* Each row is one **threshold**: an editable percentage, a tappable **preview** that fires that
  exact notification on the spot so you can see the wording, and a delete button. The last
  remaining row cannot be deleted.
* **Add threshold** appends the next sensible value (10, 20, 25, 30, 40, 50 …), up to 12 rows;
  duplicates are ignored and the list stays sorted.
* Each threshold fires **once per cycle**, at the first sample that crosses it. The 100 % row is
  worded as a block notice and, when "Block this source at 100 %" is on, crossing it cuts the
  source. Alerts are posted by the background service, so they arrive with the app closed.

## 5. Data Display Unit (per source)

How this source's numbers are *rendered* everywhere (Meter and Setup) — it never changes the
accounting, only the text:

| Mode | Behaviour |
| --- | --- |
| **Auto** | the design's own rule: MiB below 1 GiB, GiB above (1024-based). |
| **MB** | always MiB (`1536 MB`). |
| **GB** | always GiB; below 1 GiB it shows MB with one decimal (`500.0 MB`). |

**Preview** shows the current usage and quota in the chosen unit. The screenshot is set to GB,
which is why the 500 MB preset displays as **0.49 GB** (500 MiB = 0.49 GiB) — same number, GiB.

## 6. This Source (identity, accuracy and the two buttons)

| Row | Meaning |
| --- | --- |
| **Kind** | Wi-Fi network, or Mobile data · SIM 1 / SIM 2. |
| **Carrier** | the name reported by the SIM (shown when available). |
| **Interface** | the kernel interface behind it — the actual source of every byte counted. |
| **Cycle usage** | this source's total for the cycle, in the selected unit. |
| **Recovered by the system ledger** | bytes the app had **not** seen with its own counters and pulled back from Android's per-subscription ledger (needs *Usage access*). The "not one kilobyte missed" mechanism, working: **+0.2 MB**. |
| **Platform counter** | comparison with that ledger: **in step** = our total equals the platform's; otherwise "we are X behind" while it catches up. Shown only once a platform figure exists. |
| **Status** | `Active`, `Blocked — quota spent`, `Blocked — quota spent (valve closed)` or `Blocked manually`, in green or red. |
| **Block this source only** | a manual cut of this source, immediately, regardless of quota. The button flips to **Unblock this source** while it is blocked. |
| **Clear counters & alerts** | zeroes this source's counters from now on. Quota, cycle and thresholds are kept; other sources are untouched. |

## 7. Monitoring (device-wide)

* **Count while the app is closed** — starts/stops the small foreground service. No wake locks, no
  GPS, no polling loop beyond one counter read per sample interval.
* **Pause metering** — stops counting without losing what has already been measured; the kernel
  baselines are kept, so nothing double counts when you resume.
* **Block an exhausted SIM with a local VPN** — the optional valve used *only* to cut a SIM that
  has spent its quota, and only while that SIM is the active connection; it releases the moment
  the phone moves to Wi-Fi or the other SIM. Turning it off means an exhausted SIM is alerted but
  not cut.
* **Sample interval** — 2 s / 5 s / 10 s / 30 s: how often the counters are read. It is also the
  attribution window: all Wi-Fi traffic passes through one interface, so bytes are credited to the
  SSID associated at sample time, and the same applies to a SIM switch. Shorter = tighter
  attribution and more samples; longer = lighter on the battery. The gap that remains is closed by
  the platform ledger (§6).
* The paragraph under it is generated from what the app found on **your** phone. Yours says *"This
  phone shares one interface between the SIMs, so bytes are credited to the subscription that is
  the default data SIM at sample time — switching SIM re-bases the counters, so nothing is ever
  mixed."* That is the dual-SIM-with-one-uplink case: the app never merges the two SIMs; it
  re-bases at every switch and recovers the switch window from the platform ledger.

## 8. Permissions

| Row | Used for | Without it |
| --- | --- | --- |
| **Notifications** | threshold alerts | no alerts; everything else keeps working |
| **Usage access** | the platform per-subscription ledger behind "Recovered…" and "Platform counter" | kernel counters only; the SIM-switch window is the one place traffic can be missed |
| **Wi-Fi names** | splitting the Wi-Fi interface per SSID | sources are metered but named "Wi-Fi network" |
| **SIM names** | carrier and slot per SIM card | "SIM 1" / "SIM 2" without the carrier |
| **Block SIM over quota** | the local-VPN valve | a spent SIM is alerted but not cut |
| **Ignore battery optimisation** (**Open**) | keeps counting on aggressive ROMs | the service may be throttled in deep doze |

**Why your Wi-Fi source is called "Wi-Fi network" even though the row says Granted.** Android only
returns a real SSID when *both* the Location permission **and** the system Location toggle are on.
The permission is granted on your phone, so switch **Location on**, then reopen the app (or
reconnect to the network) and the chip will show the SSID. Metering is unaffected either way — the
name is cosmetic.

The note at the bottom is the promise that matters: **without a permission the app shows less
detail; it never stops counting.**

---

## 9. Defaults, if you want to change them

| Setting | Default | Where in the code |
| --- | --- | --- |
| Overhead | 36 B/packet mobile, 24 B/packet Wi-Fi, MTU 1400/1500 | `core/ledger/Precision.kt` (`OverheadPolicy`) |
| Thresholds | 50 / 75 / 100 % | `core/Selection.kt` (`ThresholdList.DEFAULT`) |
| Cycle | 30 days | `core/Model.kt` (`SourceConfig.periodDays`) |
| Sample interval | 5 s | `core/Selection.kt` (`SampleIntervals.DEFAULT`) |
| Quota | Unlimited until you set one | `core/Model.kt` (`SourceConfig.quotaBytes`) |
| Block at 100 % | on | `core/Model.kt` (`SourceConfig.blockAtQuota`) |

Per-source values live in one place per source: quota, cycle length, unit, thresholds, overhead
band and the block switch are all part of `SourceConfig(id)`, which is why two SIMs can never end
up sharing a setting.

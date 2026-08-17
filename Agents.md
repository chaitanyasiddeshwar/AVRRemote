# AVRRemote — Project Plan & Implementation Guide

> **Status: APPROVED & IMPLEMENTED.** Scope finalized on review: Dynamic EQ
> toggle, Dynamic Volume toggle, Audyssey MultEQ curve selector, Speaker
> preset switcher — plus discovery (§5.1) and saved-device registry (§5.2).
> All §7 extension candidates deferred.
>
> Android app to control a Denon/Marantz AV receiver (AVR) on the LAN over its
> telnet control protocol (port 23). Protocol knowledge is ported from the two
> sibling reference projects:
>
> - `../AcoustiX3` — Electron calibration app; owns the SSDP discovery workflow
>   (`electron/discovery_workflow.js`), the full telnet command vocabulary
>   (`electron/avr_reset.js` TELNET_WRITES/GATES), config persistence
>   (`receiver_config.avr`), and a **mock AVR test server** (`mockavr/`).
> - `../Axiom` — C++20 room-correction app; owns the hardened ports of the same:
>   `src/avr/discovery.h` (SSDP), `src/avr/registry.h` (serial-keyed device
>   registry = our "saved AVR" model), `src/avr/client.h` (telnet helper,
>   `SPPR` preset switching with power-state preconditions, `PSDYNEQ`/`PSDYNVOL`
>   writes), and `docs/dynamic-eq-volume.md` (DynEQ/DynVol semantics).

---

## 1. Product definition

### 1.1 Goal
A native Android app (installable APK) that, on the same LAN as the AVR, can:

1. **Discover** the AVR automatically (SSDP/UPnP — same mechanism as the
   reference projects).
2. **Remember** the AVR across launches, including re-finding it when its IP
   address has changed (DHCP), and recover when it is not currently visible.
3. **Control**, over telnet:
   - Dynamic EQ — on/off (`PSDYNEQ`)
   - Dynamic Volume — on/off (`PSDYNVOL`)
   - Speaker Presets — list which presets the AVR supports, show the active
     one, and switch between them (`SPPR`)

Plus a candidate set of extra telnet-controlled features (§7) to approve/deny
during review.

### 1.2 Non-goals (v1)
- No Audyssey binary protocol (port 1256): no filter transfer, no calibration,
  no measurement. Telnet only.
- No multi-AVR simultaneous control (registry supports several saved units, but
  v1 drives one at a time).
- No control over WAN / outside the local subnet (AVR telnet is LAN-only by
  design; keep it that way).

### 1.3 Target devices
- Denon/Marantz HEOS-era AVRs with the standard telnet protocol (e.g. Marantz
  CINEMA 50 — the unit both reference projects validated against).
- App: Android 8.0+ (minSdk 26), phone-first UI, portrait.

---

## 2. Reference material — what we reuse (and where)

| Need | Reference | Location | What to port |
|---|---|---|---|
| SSDP discovery | Axiom | `src/avr/discovery.h` | 4 search targets, MX:3, ~8 s window, per-interface send, LOCATION fetch + XML parse, serial identity, denon/marantz probe |
| SSDP discovery (original) | AcoustiX3 | `electron/discovery_workflow.js` | Same protocol; AcoustiX3 is the source Axiom cites |
| Saved-device registry | Axiom | `src/avr/registry.h` | Key on **serial** (fallback `UDN:<uuid>`), never on IP; upsert semantics; cached layout fields |
| Telnet session semantics | Axiom | `src/avr/client.h` (`class Telnet`) | `cmd + "\r"`, line-buffered, reply matched by prefix regex, timeouts |
| Telnet session semantics | AcoustiX3 | `electron/avr_reset.js` (`TelnetSession`) | bare-`\r` line endings, settle ~1 s between write and read-back, query-echo prefix matching (unsolicited broadcasts!) |
| DynEQ / DynVol commands | AcoustiX3 | `electron/avr_reset.js` TELNET_WRITES | `PSDYNEQ OFF` / `PSDYNVOL OFF` + queries `PSDYNEQ ?` / `PSDYNVOL ?`, confirm regexes |
| DynEQ/DynVol semantics | Axiom | `docs/dynamic-eq-volume.md` | DynVol ON engages DynEQ; Reference Level Offset interaction |
| Preset switching | Axiom | `src/avr/client.h` `setPreset()` | `SPPR ?` → `SPPR1|SPPR2`; **writes are silently ignored while main zone is OFF** → check `ZM?`, power on (`ZMON`), wait ~10 s boot, then switch |
| Preset query | AcoustiX3 | `electron/discovery_workflow.js` `queryTelnetPreset()` | `SPPR ?` regex `/^SPPR\s*(1|2)/i`; some models don't support presets at all (optional gate) |
| Power gating | both | `ZM?` / `ZMON` / `ZMOFF` | Main-zone power; reads work when off, most writes don't |
| Test harness | AcoustiX3 | `mockavr/` | Node mock of a Marantz CINEMA 50: SSDP + UPnP XML + telnet vocabulary + error toggles |

---

## 3. Technology choice

**Recommendation: native Kotlin + Jetpack Compose.**

Rationale:
- Everything the app needs is plain sockets (UDP multicast send, TCP telnet).
  Android exposes both directly to Kotlin with no bridging layer; a JS/flutter
  bridge would add a dependency (and a risk) for zero benefit here.
- Single target platform (Android phone), so no cross-platform tax.
- Compose gives a small, polished control UI cheaply.
- APK output is trivially sideloadable ("an app I can install on my phone").

Acceptable alternative if you prefer it: **Flutter** (dart:io has raw
`Socket`/`RawDatagramSocket`, so discovery still works). Nothing in this plan
is Compose-specific except §5.3 UI notes.

Build system: Gradle (Kotlin DSL), single `app` module, `minSdk 26`,
`targetSdk 34`. No NDK. No third-party networking libs — `java.net` only.

### 3.1 Repository layout

```
AVRRemote/
├── Agents.md                  ← this file
├── settings.gradle.kts
├── build.gradle.kts
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml      (permissions §6.1)
│       │   ├── kotlin/com/avrremote/app/
│       │   │   ├── MainActivity.kt      (single-activity Compose shell)
│       │   │   ├── discovery/           SsdpDiscoverer.kt, DeviceInfo.kt, XmlDescParser.kt
│       │   │   ├── registry/            AvrRegistry.kt, AvrRecord.kt (JSON in filesDir)
│       │   │   ├── telnet/              TelnetConnection.kt, AvrCommand.kt, CommandSequencer.kt
│       │   │   ├── state/               AvrState.kt, AvrRepository.kt (StateFlow hub)
│       │   │   ├── features/            DynEqViewModel / DynVolViewModel / PresetViewModel
│       │   │   └── ui/                  screens: DeviceScreen, ControlScreen, SettingsScreen
│       │   └── res/ …
│       └── test/                        (unit tests: parsers, registry, command matching)
└── tools/
    └── mockavr -> ../../../AcoustiX3/mockavr   (reference or copy, for dev testing)
```

---

## 4. Protocol reference (what the app speaks)

### 4.1 Discovery (SSDP/UPnP) — port of `discovery.h` / `discovery_workflow.js`

1. Send UDP `M-SEARCH` to `239.255.255.250:1900` for **four search targets**:
   ```
   M-SEARCH * HTTP/1.1
   HOST: 239.255.255.250:1900
   MAN: "ssdp:discover"
   MX: 3
   ST: <target>
   ```
   Targets: `urn:schemas-denon-com:device:Receiver:1`,
   `urn:schemas-upnp-org:device:MediaRenderer:1`, `upnp:rootdevice`, `ssdp:all`.
   (Send once per ST, 2–3 repeats staggered over the window for reliability on
   Wi-Fi.)
2. Collect responses for a ~8 s window; extract the `LOCATION:` header from each.
3. HTTP GET each LOCATION URL (5 s timeout) → UPnP device description XML.
4. Parse (simple tag extraction is enough — both references do exactly this,
   no full DOM needed): `friendlyName`, `manufacturer`, `modelName`,
   `serialNumber`, `UDN`.
5. **Identity** = `serialNumber`; fall back to `"UDN:" + uuid` if no serial.
   Never identify a device by IP (see Axiom `registry.h` header comment: IP is
   the one field that moves).
6. **AVR-likeness probe** (reference heuristic): flag as candidate if any of
   manufacturer/model/friendlyName contains `denon|marantz|receiver|avr`
   (case-insensitive). Sort flagged devices first; show everything else as
   "other UPnP devices" so the user can still pick an oddly-named AVR.
7. Dedupe by IP (receivers expose several UPnP sub-devices on different
   ports — prefer the AVR-flagged description per IP, exactly as Axiom does).

Android note: receiving multicast replies requires holding a
`WifiManager.MulticastLock` during the scan (§6.1).

### 4.2 Telnet control protocol (port 23)

Wire rules distilled from both references (they agree, and were validated
against a real Marantz CINEMA 50):

- **One line = one command**, terminated by a bare `\r` (not `\r\n`).
- Reply to `<CMD> ?` is a line beginning with the command prefix, e.g.
  `PSDYNEQ ?` → `PSDYNEQON`. Writes echo back the same way (`PSDYNEQ OFF`
  → `PSDYNEQ OFF` line) — treat the echo as the confirmation.
- The AVR also emits **unsolicited broadcast lines** (e.g. volume updates
  `MVxx`, `MVMAX nn`). Every parser must therefore match on the *command
  prefix* of a line, not on "the next line" — this exact bug (taking `MVMAX 70`
  as the reply to `MV?`) is documented in Axiom `client.h`.
- **Only one telnet client at a time**: the AVR accepts a single connection
  and may refuse a new one right after a close (Axiom `client.h`, reset
  comment). The app must hold ONE shared connection, not per-feature
  connections, and close it cleanly.
- Reads (queries) work while the main zone is powered off; **most writes are
  silently ignored while off** (no NAK, just silence — the `setPreset`
  discovery in Axiom). Writes must therefore be preceded by a `ZM?` check and,
  if `ZMOFF`, a `ZMON` + boot wait (~10 s).
- The AVR drops idle connections; the client must detect EOF and reconnect.
- Settle time: after a write, wait ~1.0–1.2 s before a read-back if you intend
  to verify (GSonic pacing observed in `avr_reset.js`).

### 4.3 Command map — core features

| Feature | Query | Set | Reply/confirm regex | Notes |
|---|---|---|---|---|
| Main zone power | `ZM?` | `ZMON` / `ZMOFF` | `^ZM(ON\|OFF)` | Precondition for writes |
| Dynamic EQ | `PSDYNEQ ?` | `PSDYNEQ ON` / `PSDYNEQ OFF` | `^PSDYNEQ\s*(ON\|OFF)` | `ON`/`OFF` only |
| Dynamic Volume | `PSDYNVOL ?` | `PSDYNVOL ON` / `PSDYNVOL OFF` | `^PSDYNVOL\s*(ON\|OFF)` | Enabling DynVol also engages DynEQ (Axiom doc) |
| Speaker preset (read) | `SPPR ?` | — | `^SPPR\s*(1\|2)` | **No reply at all on models without preset support** → feature must degrade to "not supported" |
| Speaker preset (switch) | — | `SPPR 1` / `SPPR 2` | `^SPPR\s*<n>` | Write ignored while ZMOFF → power on first (§4.2) |

### 4.4 Command map — extension candidates (§7)

`MV?`/`MVnn`/`MUON|MUOFF`, `SI?`/`SI<src>`, `MS?`/`MS<mode>`,
`PSMULTEQ: AUDYSSEY|FLAT|OFF`, `PSREFLEV <0|5|10|15>`, `PSLFE nn`, `PSSWL nn`,
`PSCINEMA EQ.ON|OFF`, `PSDEH OFF|ON|…`, `ECOON|ECOOFF`, `CV<CH> nn` / `CVZRL`,
`SSLFL <40..250>`, `SSSWO LFE|LFE+MAIN` — all appear in the reference
codebases (`avr_reset.js` TELNET_WRITES, `client.h`, `mockavr/index.js`).

---

## 5. Feature specifications

### 5.1 F1 — Discovery

**Acceptance criteria**
- "Scan" button runs an SSDP scan (§4.1) and lists candidates within ~8–10 s,
  showing friendly name, model, manufacturer, IP; AVR-flagged entries first.
- Selecting a device attempts a telnet probe (`ZM?`, 3 s) to confirm it is a
  reachable, responding AVR before offering "Save & connect".
- Empty-state and error-state UI: no responses ("Is the AVR on the same
  network? Is 'Network Control' enabled in the AVR setup?").
- Scan runs off the main thread; cancellable.

**Implementation notes**
- One UDP socket bound to `0.0.0.0` (ephemeral port), `reuseAddr`. Enumerate
  interfaces via `NetworkInterface.getNetworkInterfaces()`; if the phone has
  more than one IPv4 interface (rare: Wi-Fi + hotspot), send per-interface
  with `IP_MULTICAST_IF` equivalent (`DatagramSocket.setOption`, or simply one
  socket on the Wi-Fi interface — v1 may assume the single Wi-Fi interface and
  log the others; multi-NIC handling is a §M5 hardening item since Axiom found
  it load-bearing on desktops).
- Hold `MulticastLock` + `WifiLock` only for the scan duration, then release.
- Fetch LOCATION with `HttpURLConnection` (plain HTTP on LAN is fine; raw
  sockets are unaffected by Android's cleartext-HTTP policy).

### 5.2 F2 — Saved AVR registry & re-discoverability

Direct port of Axiom `registry.h` semantics onto Android storage:

- **Storage**: JSON file `filesDir/avr_registry.json` (or Jetpack DataStore;
  JSON keeps parity with the reference format and is trivially debuggable).
  Schema mirrors `avr.json` from Axiom:
  ```json
  {
    "active": "<serial>",
    "avrs": [
      { "serial": "...", "ip": "...", "name": "...", "model": "...",
        "lastSeen": "<ISO ts>", "lastConnectedOk": true }
    ]
  }
  ```
- **Upsert by serial**: a known serial updates `ip`/`name`/`model` in place —
  a DHCP lease change never orphans the record (this is the entire point of
  keying on serial).
- Devices with neither serial nor UDN are **not saved** (same rule as Axiom:
  saving keyed on IP defeats the registry).

**Connection ladder on app start** (this is the "re-discoverability" flow):

```
1. Load active record (serial + last known IP).
2. Try direct telnet connect to last known IP (fast path, ~1–2 s timeout).
   Verify with ZM? → CONNECTED.
3. If that fails → run SSDP scan; match responders by SERIAL (name/model as
   tie-breakers) → new IP → connect → UPDATE stored IP → CONNECTED.
4. If scan found nothing with our serial:
   a. Offer "Retry scan",
   b. Offer manual IP entry (user may know it; validate with ZM?),
   c. Show troubleshooting (AVR powered? same subnet? Network Control on?).
5. Every successful connect: upsert record, set `active`, update `lastSeen`.
```

- "Forget device" deletes the record (parity with AcoustiX3 `avr:forget`).
- Device list screen shows all saved records with status (last-seen IP, last
  connected), tap to activate, long-press to forget.

### 5.3 F3 — DynEQ / DynVol / Presets control screen

One live control screen for the connected AVR:

**Dynamic EQ** — toggle switch.
- On open: `PSDYNEQ ?` → paint state. Toggle: send `PSDYNEQ ON|OFF`; confirm
  from the echo/broadcast line `^PSDYNEQ\s*(ON|OFF)`; revert the toggle on
  timeout with a toast.

**Dynamic Volume** — toggle switch (v1: simple on/off; DynVol levels
Light/Medium/Heavy exist on some models → §7 extension).
- Same pattern with `PSDYNVOL`.
- UI note from Axiom docs: enabling DynVol also engages DynEQ — reflect the
  resulting DynEQ state from the broadcast rather than assuming.

**Presets** — the reference protocol gives us `SPPR` with values 1/2 only.
- On connect: `SPPR ?` (4 s timeout).
  - Reply `SPPR1`/`SPPR2` → feature supported: show two preset slots
    (radio/chip group), highlight active.
  - **No reply** → feature unsupported on this model: hide the section with an
    explanatory note (mirrors AcoustiX3's `optional` gate and Axiom's
    `preset == ""` handling).
- "List presets": v1 the list is protocol-fixed (Preset 1 / Preset 2) — the
  protocol has no enumeration command beyond `SPPR ?`; we list what the unit
  acknowledges. Preset **names** are not retrievable over telnet; allow the
  user to rename locally ("Speakers A", "Night mode"…) stored in the registry
  record — cheap and useful.
- Switch: the **power precondition sequence** (port of Axiom `setPreset`):
  1. `ZM?` → if `ZMOFF`: send `ZMON`, show "Powering on…" progress, wait the
     real boot (~10 s), re-check `ZM?`.
  2. Send `SPPR <n>`, confirm `^SPPR\s*<n>` (4 s). No confirm → error state
     ("AVR did not confirm the switch").
- Switching presets reloads the AVR's speaker config; show a transient banner.

**Connection status bar** on the control screen: connected IP, power state
(from `ZM?`), reconnect-in-progress indicator, tap-to-reconnect.

### 5.4 State/connection architecture

```
                 ┌──────────────────────────────┐
  Compose UI ◄──►│  AvrRepository (StateFlow)   │
                 │  - power, dynEq, dynVol,     │
                 │    preset, connState         │
                 └──────┬───────────────────────┘
                        │ single owner of
                        ▼
                 ┌──────────────────────────────┐
                 │ TelnetConnection (actor/     │
                 │ coroutine)                   │
                 │ - one socket, write queue    │
                 │ - line buffer, prefix match  │
                 │ - broadcast fan-out          │
                 │ - auto-reconnect w/ backoff  │
                 └──────────────────────────────┘
```

- **Connection policy (v1)**: connect on entering the control screen, hold
  while the app is foregrounded on that screen, disconnect on background
  beyond a grace period (the AVR allows only one client and the user may want
  the official Denon app open too). A "persistent connection" foreground-
  service mode is a possible §7 extension — default OFF.
- **Request/response correlation**: outgoing command → expected-prefix →
  suspend continuation with 3–4 s timeout. Unsolicited lines that match no
  pending request go to the broadcast parser (updates power/volume/state
  flows). This is exactly Axiom's `exec(cmd, confirmRegex)` + AcoustiX3's
  listener-set design, expressed as a Kotlin channel.
- Every UI write follows: *optimistic UI → send → confirm from echo →
  revert + toast on timeout*. Never trust that a silent AVR applied a write
  (lesson from the `setPreset` incident in Axiom).

---

## 6. Android specifics

### 6.1 Permissions & manifest

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE"/>
```
- `CHANGE_WIFI_MULTICAST_STATE` + `WifiManager.createMulticastLock("ssdp")`
  acquired **only during scans**: Android filters non-broadcast multicast by
  default; without the lock, M-SEARCH goes out but replies never arrive.
  (This is the single most likely "discovery finds nothing" failure — call it
  out in code comments like Axiom does for its multi-NIC trap.)
- No wake lock in v1; socket I/O on `Dispatchers.IO`.

### 6.2 Lifecycle & robustness
- Wi-Fi drops / airplane mode: `ConnectivityManager` callback → mark
  `connState=DISCONNECTED`, stop retry storms (exponential backoff, max 3).
- Process death while connected: nothing to clean up server-side (AVR drops
  the dead socket on its own timeout).
- Clock: use timeouts, never `Thread.sleep` on the UI path.

### 6.3 Build / install
- `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`,
  sideload over USB/Wi-Fi. Optionally signed release build later.
- Dev convenience: `adb reverse` is useless here (phone must be on the real
  LAN); instead use `mockavr` on the PC (§8).

---

## 7. Candidate extra features (from the reference projects, all telnet)

Suggested additions — **each is verified vocabulary from AcoustiX3/Axiom**,
please mark keep/drop:

| # | Feature | Command(s) | Provenance in references | Effort |
|---|---|---|---|---|
| E1 | **Main zone power button** (On/Off) | `ZM?`/`ZMON`/`ZMOFF` | Already required by F3 preset path; UI is free | S |
| E2 | **Master volume slider + mute** (`MV62` = −18 dB scale, offset-from-80, 3rd digit = half step; `MUON`/`MUOFF` is standard Denon, not in refs — flagged) | `MV?`, `MV<nn>`, `MU*` | `client.h mvToDb()` documents the encoding + the MVMAX parsing trap | M |
| E3 | **Audyssey MultEQ curve selector** (Reference / Flat / Off) | `PSMULTEQ: AUDYSSEY|FLAT|OFF` | Confirmed working on real CINEMA 50 (Axiom `setMultEqCurve`) | S |
| E4 | **Reference Level Offset** (0/5/10/15 dB — changes DynEQ behavior; the two are documented together in Axiom `docs/dynamic-eq-volume.md`) | `PSREFLEV <n>` | mockavr models it; Axiom doc explains semantics | S |
| E5 | **Input selector** (list + switch) | `SI?` / `SI<src>` | `avr_reset.js` TELNET_GATES (`SI?`); source list from the reply | M |
| E6 | **Sound/decode mode selector** (Dolby Atmos, DTS, Stereo, Multi Ch…) | `MS?` / `MS<mode>` | TELNET_GATES + mockavr `MSDOLBY ATMOS` | M |
| E7 | **Subwoofer level + LPF for LFE** | `PSSWL <38..62>` (= −12..+12 dB), `SSLFL <40..250>` | TELNET_WRITES (essential-class entries) | M |
| E8 | **LFE level / Cinema EQ / Dialogue Enhancer / ECO** | `PSLFE 00-10`, `PSCINEMA EQ.ON/OFF`, `PSDEH …`, `ECOON/ECOOFF` | TELNET_WRITES | M |
| E9 | **Status strip**: current input, decode mode, input format, active preset | `SI?`, `MS?`, `SSINFAISFOR ?`, `SPPR ?` | TELNET_GATES is exactly this set | S |
| E10 | **Channel level trims view + zero-all** | `CV<CH> nn`, `CVZRL` | TELNET_WRITES (`CVZRL` essential) | L |
| E11 | **"Movie night" macro**: one tap = power on → input → DynEQ on → DynVol on | composition of F3/E1/E5 | new (uses only proven primitives) | M |

Recommended v1 scope if you want more than the core three: **E1, E3, E4, E9**
(all small, all directly corroborated by the references, and E4 pairs naturally
with the DynEQ toggle you already asked for).

Explicitly **not** proposed (present in the protocol family but not
corroborated by either reference project): radio/tuner preset memory, favorites
(`FAV*`), zone 2/3, HEOS/net-radio commands. Happy to add as experimental if
you want.

---

## 8. Testing strategy

1. **Unit tests (JVM)** — no Android/sockets needed:
   - line-buffer + prefix-matcher (incl. the `MV` vs `MVMAX` trap),
   - XML tag extraction & URL parsing (port Axiom's testable `detail::` fns),
   - registry upsert-by-serial logic (DHCP-change case),
   - `MVxx` ↔ dB conversion (port `mvToDb` cases incl. half steps).
2. **mock AVR integration** — reuse `../AcoustiX3/mockavr` as-is: it answers
   SSDP M-SEARCH, serves the UPnP XML, and implements the telnet vocabulary
   (power, `SPPR`, `PSDYNEQ`/`PSDYNVOL` incl. query forms, plus error toggles
   like `MOCK_POWER_OFF`, `MOCK_PRESET_TIMEOUT`). Run it on the PC
   (`node mockavr/index.js`, telnet on 8023 or 23), point the phone app at the
   PC's LAN IP. Port note: mockavr defaults telnet to **8023**; the app needs
   a debug-only port override (build-config flag), production stays on 23.
   May need small extensions to mockavr (broadcast echo on writes) — allowed,
   it's a dev tool.
3. **Real hardware pass** (final gate, user-run): discovery finds the actual
   AVR; kill the DHCP lease / reboot router → ladder step 3 recovers by
   serial; DynEQ/DynVol toggles match the AVR front-panel/OSD state; preset
   switch from powered-off state works end-to-end.

---

## 9. Milestones

| Milestone | Deliverable | Est. |
|---|---|---|
| **M0 Scaffold** | Gradle/Compose project boots; manifest perms; empty screens; CI-less `assembleDebug` produces installable APK | 0.5 d |
| **M1 Discovery** | F1 complete: scan → device list → telnet probe confirm. Tested vs mockavr SSDP | 1 d |
| **M2 Registry** | F2 complete: save by serial, connect ladder (last-IP → SSDP-by-serial → manual IP), forget | 1 d |
| **M3 Telnet core** | `TelnetConnection` actor: queue, prefix-match, broadcast fan-out, reconnect; status bar live | 1 d |
| **M4 Core controls** | F3 complete: DynEQ, DynVol, presets incl. power-precondition flow | 1 d |
| **M5 Hardening** | multi-NIC scan, backoff, error states, edge cases (unsupported presets, AVR mid-boot), polish | 1 d |
| **M6 Extras** | Whatever §7 items are approved | ~0.25 d each |
| **M7 Release** | signed APK, README with install + AVR-setup notes ("Network Control" must be ON) | 0.5 d |

Total core: ~5–6 working days to a usable APK; extras on top.

---

## 10. Open questions for review

1. **Tech stack**: Kotlin+Compose OK, or do you want Flutter (share code with
   future iOS)?
2. **§7 extras**: which of E1–E11 for v1? (Recommendation: E1, E3, E4, E9.)
3. **Preset naming**: local-only rename acceptable (protocol can't fetch
   names)?
4. **Connection policy**: connect-on-screen-enter + disconnect-on-background
   OK, or do you want a persistent foreground-service connection (keeps live
   broadcasts but blocks other controllers, e.g. the Denon app)?
5. **DynVol granularity**: simple on/off (v1 as specced) or Light/Medium/Heavy
   selector where the model supports it?
6. App name/icon: keep "AVRRemote", or something else before M7 signing?

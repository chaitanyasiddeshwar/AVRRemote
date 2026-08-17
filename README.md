# AVR Remote

A small Android app to control a Denon/Marantz AV receiver from your phone,
over your local network. No cloud, no accounts, no bridge — the app talks to
the receiver directly.

Verified against a Marantz CINEMA 50; should work with any Denon/Marantz
receiver exposing the standard network control interface.

## Features

- **Discovery** — scan the LAN and find the receiver automatically; no need
  to know its IP address.
- **Saved receivers** — the receiver is remembered between app launches. If
  its IP address changes (DHCP), the app re-identifies it by hardware serial
  and reconnects.
- **Dynamic EQ** — on/off, plus Reference Level Offset (0 / 5 / 10 / 15 dB).
- **Dynamic Volume** — Off / Light / Medium / Heavy.
- **Audyssey MultEQ** — select the correction curve: Audyssey / Flat / Off.
- **Speaker Preset** — switch between the receiver's two speaker presets.
  If the receiver is in standby, the app powers it on first and waits for it
  to boot.

## Requirements

- Android 8.0+ phone, connected to the same network as the receiver.
- Receiver with **Network Control enabled** (see the receiver's manual;
  usually under Setup → Network).
- Note: the receiver accepts a single network-control client at a time. If
  the official Denon/Marantz app is connected, disconnect it (or disconnect
  here) before using the other.

## Building

Requires **JDK 17** and an Android SDK with platform 35 (Android Studio
installs both; point `JAVA_HOME` at Android Studio's bundled JBR if you have
no other JDK 17).

```sh
gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

On Windows two convenience scripts are provided:

| Script | What it does |
|---|---|
| `build.bat` | Builds the debug APK |
| `install.bat` | Connects to the phone over USB or wireless adb, installs the APK and launches the app |

## Installing on the phone

- **USB:** enable USB debugging, plug in, run `install.bat`
  (or `adb install -r app/build/outputs/apk/debug/app-debug.apk`).
- **Wireless:** enable *Wireless debugging* in the phone's developer options;
  `install.bat` discovers and connects automatically. Pairing is needed once —
  `adb pair` with the code shown on the phone.

## Project notes

- `Agents.md` — the full project plan and protocol notes gathered from the
  reference projects this app was derived from.
- `gradle wrapper` (Gradle 8.11.1), Android Gradle Plugin 8.7, Kotlin +
  Jetpack Compose, minSdk 26 / target+compileSdk 35.

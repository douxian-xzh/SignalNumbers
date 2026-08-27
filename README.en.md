# SignalNumbers

SignalNumbers is an Android SystemUI module for Vector/Xposed. It replaces cellular and Wi-Fi signal icons with live dBm values at runtime. It does not modify, resign, or replace the vendor `SystemUI.apk`, and it does not use floating windows, persistent notifications, or high-frequency polling.

[中文说明](README.md)

## Compatibility status

- **Xiaomi / Redmi / POCO running HyperOS 3 on Android 16:** the current Xiaomi profile has been verified on a real device. Install the module APK, enable it in Vector/Xposed, select only `System UI` (`com.android.systemui`), and reload SystemUI.
- **PJZ110 / LineageOS / Android 16:** an adaptation path is included for dual-SIM and Compose-based SystemUI layouts, but this target is still under adaptation and continued testing.
- **Other systems:** the generic AOSP fallback is available, but layout, colors, dual-SIM ordering, and signal View detection are not guaranteed.

The Xiaomi and PJZ110 profiles are isolated by runtime device detection. PJZ110-specific hiding and layout rules are not enabled on Xiaomi devices if a newer module is installed by mistake.

## Features

- Displays cellular signal strength using the best available NR, LTE, WCDMA, or GSM reading.
- Displays Wi-Fi RSSI as a compact dBm value.
- Tracks each active subscription independently using its subscription ID and SIM slot index.
- Supports dual-SIM rendering in LineageOS Compose containers.
- Synchronizes injected text with SystemUI light/dark appearance, lock screen state, and HyperOS control-center behavior where supported.
- Avoids duplicate signal rows and keeps vendor data-activity indicators from overlapping the numeric display on the verified profiles.
- Uses event-driven signal updates and pauses visual updates while the screen is off.
- Includes a safe mode after repeated injection failures and a setting to restore the original icons.
- Requests only the `com.android.systemui` module scope.

## Installation

1. Download the APK from the [Releases](https://github.com/douxian-xzh/SignalNumbers/releases) page.
2. Install it as a module APK. This project is used through Vector/Xposed; it does not patch the system partition.
3. In Vector/Xposed, enable SignalNumbers and select only `com.android.systemui` / `System UI` as the scope.
4. Reload SystemUI or reboot the phone.
5. Open the module settings to adjust the display and maintenance options.

The release APK is signed with the Gradle debug certificate for local sideloading and device testing. Use your own release keystore for redistribution; never commit signing material to this repository.

## Build

Requirements: JDK 17, Android SDK Platform 36, and Android Build Tools 36.0.0.

```powershell
./gradlew.bat :app:lintRelease :app:assembleRelease
```

The APK is generated at `app/build/outputs/apk/release/app-release.apk`. Build caches and generated APKs are excluded by `.gitignore`.

## Project layout

```text
app/src/main/java/com/xinsu/signalnumbers/
├─ compatibility/   # AOSP, PJZ110/LineageOS, and Xiaomi/HyperOS profiles
├─ config/           # Cross-process configuration and safe mode
├─ injection/       # SystemUI View discovery and numeric View injection
├─ signal/          # Cellular, dual-SIM, and Wi-Fi tracking
├─ ui/              # Module settings screen
└─ xposed/          # Entry point, hooks, and runtime coordination
```

`xposed-stubs` contains compile-time Xposed API signatures only. The real framework API is supplied by Vector/Xposed at runtime.

## Compatibility notes

SystemUI internals vary between Android versions and vendors. `CompatibilityRegistry` selects `xiaomi-hyperos3`, `pjz110-lineage`, or the generic `aosp` mode from the device identity. Vendor class names, resources, and hook points are kept inside the `compatibility` package.

The repository documentation intentionally excludes device addresses, ADB serials, subscription identifiers, live signal snapshots, debug logs, screenshots, and local APK/SystemUI hashes.

See [docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md) for the current maintenance state and [docs/DEVICE_COMPATIBILITY.md](docs/DEVICE_COMPATIBILITY.md) for the sanitized compatibility notes.

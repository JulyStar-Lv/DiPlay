# DiPlay for Changan Mazda EZ60

The `:mazda` application targets Android 14 or newer. It reuses the current DiPlay connection,
settings, session service, video renderer, touch mapping and wheel-key support. The application
name and CarPlay icon remain DiPlay. The settings interface is unchanged in this first iteration,
except the About page replaces the update controls with instructions for installing a compatible
Mazda update manually. It hides the background-check toggle, and the Activity skips background
update scheduling. The shared scheduler, worker, update lookup, download, installation and cache
handling remain unchanged. Previously queued WorkManager update tasks are not cancelled by
this Activity guard.

## Builds

The release application ID is `com.shihab.diplay`, identical to `:mobile`. The version code/name
come from `:mobile`. Both applications use the existing signing environment variables and
`DIPLAY_AUTH_ASSETS_DIR`; there is no second signing configuration to provision. A release
signed with the same certificate can replace the mobile installation and retain its preferences.
These two release packages cannot be installed side by side.

Source-only debug build (no CarPlay authentication):

```sh
./gradlew :mazda:lintDebug :mazda:assembleDebug
```

The debug application ID is `com.shihab.diplay.mazdatest`. For a standalone car-test debug
package, set the existing authentication environment and use `:mazda:assembleStandaloneDebug`.

The local `.private` folder contains these release wrappers:

- macOS: `bash .private/build-mazda-release-macos.sh`
- Linux: `bash .private/build-mazda-release-linux.sh`
- Windows: `.private\build-mazda-release-windows.cmd` (or the PowerShell script of the same name).

All wrappers load the existing `release-env.sh` / `release-env.ps1`, verify the signing and
runtime authentication files, then run `:mazda:lintRelease :mazda:assembleRelease`.
The output is `mazda/build/outputs/apk/release/mazda-release.apk`. Existing mobile wrappers
continue to build `:mobile`.

## Isolation and integration points

`common/src/main/java/com/shilapi/xcertplay/mazda/` holds the new home and waiting views,
shared presentation, cockpit container and adapter. `config_mazda` defaults to false in
`:common` and is overridden only by `:mazda`. The two existing Activities select the Mazda
views and container through this flag; their settings, actions and session state are reused.
There are no copied Activities, duplicated background-session singletons or activity aliases.

The Mazda manifest alone registers `MzdApplication`. Before Activities or services start,
`MzdDefaults` initializes missing preferences once: hide status/navigation bars off, 60 FPS,
two-finger settings gesture and automatic application appearance. It marks the existing setup
guide as seen, so the original launch logic opens the home page; the guide remains available
from Settings. Existing saved preferences, including those retained when replacing `:mobile`,
are preserved. Clearing application data initializes the defaults again. Both classes live in
`common/src/main/java/com/shilapi/xcertplay/mazda/`; other applications retain their defaults.

The Mazda application overrides `raw/ic_car_home.png` with the Mazda logo from the reference
project. The original CarPlay icon loader uses this resource without modification; custom icons
still take precedence. The OEM label reads the build's `config_oem_label` resource as its default:
BYD in the shared module, overridden to Mazda only by `:mazda`. There is no OEM initialization
or migration, and saved nonblank names (including BYD) are preserved. The Android application
name remains DiPlay and its launcher icon retains the CarPlay style.

The EZ60 physical panel is `(64, 192)–(2560, 1272)` normally (2496×1080), or
`(832, 192)–(2560, 1272)` when either `key_screen_show` or `key_vpa_cui_show_left` requests
its sidebar (1728×1080). These physical OEM coordinates do not scale with Android density.
Other window sizes use their available bounds. Observers and the navigation broadcast receiver
are removed when the container detaches. The recognized OEM car-home broadcast closes both
transparent Activity windows while retaining the shared CarPlay session.

Both cockpit shapes are declared as existing CarPlay view areas on the same full-size canvas.
Starting in the narrow shape declares both shapes too. Sidebar changes use `showViewArea`,
with the existing renderer and touch coordinate mapping; they do not require reconnection.
Dock and resolution scaling are preserved. Screen rotation's square canvas is excluded for
this fixed OEM panel; ordinary mobile builds retain the current rotation behavior.

## System hotspot

The Mazda application requests `NETWORK_SETTINGS` and `TETHER_PRIVILEGED`. Actual grants
are checked at runtime; sharing a signing key alone does not establish permission availability
on every firmware. Automatic system-hotspot handling is selected only when both permissions
are granted and the saved connection mode is Car hotspot (`MANUAL`). USB, Wi-Fi Direct and
existing Wi-Fi modes are preserved. Without these grants, the existing manual hotspot setup
and reminders remain available.

The bounded startup algorithm in `shared/.../network/mazda/` reads the complete system AP
configuration, preserves SSID/password/security, requests 5 GHz, and verifies the running AP's
reported frequency. It can restart an existing 2.4 GHz AP to apply that band, which briefly
interrupts its clients. Cancellation or startup failure attempts to restore the original AP
configuration without overwriting external changes. Closing a successful CarPlay session only
releases observers; it leaves the car-owned hotspot running. Credentials are not logged.

The Mazda manager reuses the unchanged `ManualHotspotManager` for interface attachment and
the original frequency/channel inference. System AP callbacks still verify that the running
hotspot is ready on 5 GHz. `MzdHotspotConnection` prepares the hotspot off the UI thread before
creating the CarPlay runtime configuration. It supplies the current SSID/password/security to
the original configuration fields in memory, then the unchanged controller starts its normal
manual-hotspot connection flow. `CarPlayRuntimeConfig` and `CarPlayController` have no Mazda
parameter or branches. Duplicate preparation requests are ignored; cancellation releases the
temporary observers, and a preparation failure keeps the existing manual retry button available.

## Verification

Run the original repository CI command in `AGENTS.md`, plus `:mazda:lintDebug`, a standalone
Mazda release build, and the Mazda tests in `:common` / `:shared`. Native widget rendering tests
write home/waiting screenshots for 1728 and 2496 pixel widths, light/dark appearance, large
fonts and Arabic RTL to `common/build/reports/mazda-ui/`. Emulator screenshots and UI dumps
are under `mazda/build/reports/emulator/` when captured locally.

Real EZ60 validation must cover firmware permission grants, AP startup/readback, iPhone pairing,
CarPlay video/touch, sidebar changes in both directions, and returning to the car UI with the
session retained. An ordinary Android emulator cannot establish these OEM-specific behaviors.

After merging upstream `main` at `24ae1fa5`, local verification on 2026-10-11 passed the full
original CI command and the Mazda lint/debug tasks: 2535 tests, no failures, one skipped test.
The macOS wrapper also passed release lint and assembly. The resulting release has version
0.2.18 (37), matches the existing mobile
signing certificate and contains the two explicitly provisioned authentication assets, with no
keystore bundled. Both Bash wrappers passed fixture checks for paths with spaces, missing
credentials, SDK selection and Gradle exit-code propagation. Windows scripts were inspected
but have not been executed on Windows.

Android 14 emulator checks covered both sidebar signals, physical panel placement, retained
foreground service after closing the windows, reopening, permission-denied fallback, and
the existing settings appearance control. During repeated font/window changes while builds
were running, opening the original settings page produced one input-dispatch ANR; its trace
was in the existing settings rendering path. A fresh settings launch after the builds completed
succeeded, but cold rendering was slow on this emulator. Settings launch and configuration-change
performance remain part of the real-car checks; this iteration does not claim an ANR-free
settings stress test.

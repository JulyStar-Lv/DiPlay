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

The release application ID is `com.thunder.carplay`. The version code/name come from `:mobile`,
whose application ID remains `com.shihab.diplay`. Both applications use the existing signing
environment variables and `DIPLAY_AUTH_ASSETS_DIR`; there is no second signing configuration
to provision. The Mazda release installs as a separate application alongside the mobile release
or a previous Mazda release with the old ID. It does not upgrade the old package or automatically
migrate its preferences. The head unit's Launcher lists `com.thunder.carplay` among the packages
that do not show its floating close button.

Source-only debug build (no CarPlay authentication):

```sh
./gradlew :mazda:lintDebug :mazda:assembleDebug
```

The debug application ID is `com.thunder.carplay.mazdatest`. For a standalone car-test debug
package, set the existing authentication environment and use `:mazda:assembleStandaloneDebug`.

The local `.private` folder contains these release wrappers:

- macOS: `bash .private/build-mazda-release-macos.sh`
- Linux: `bash .private/build-mazda-release-linux.sh`
- Windows: `.private\build-mazda-release-windows.cmd` (or the PowerShell script of the same name).

All wrappers load the existing `release-env.sh` / `release-env.ps1`, verify the signing and
runtime authentication files, then run `:mazda:lintRelease :mazda:assembleRelease`.
The output is `mazda/build/outputs/apk/release/DiPlay-Mazda-release-<version>-<yyyyMMdd>.apk`,
using the version from `:mobile` and the local build date (for example,
`DiPlay-Mazda-release-0.2.19-20261012.apk`). Existing mobile wrappers
continue to build `:mobile`.

## Isolation and integration points

`common/src/main/java/com/shilapi/xcertplay/mazda/` holds the new home and waiting views,
shared presentation, cockpit container and adapter. `config_mazda` defaults to false in
`:common` and is overridden only by `:mazda`. The two existing Activities select the Mazda
views and container through this flag; their settings, actions and session state are reused.
There are no copied Activities, duplicated background-session singletons or activity aliases.

The cockpit container anchors each page to its current OEM task area: the driver half starts
at physical x=0, and the passenger half starts at x=2560 on the shared 5120px display. It checks
the window position before drawing, so moving an existing task between equal-sized areas also
updates its content bounds. Android decor insets are compensated within that area. The driver's
sidebar signals only shrink pages in the driver area; passenger pages retain the full panel.
The passenger panel matches the OEM apps' 32px margins on both sides, occupying physical
`[2592,192][5088,1272]`. Its content remains 2496x1080, the same size as the full driver panel.
System-bar navigation broadcasts are matched to the window's current area.

The home and connection pages share their typography, artwork and control dimensions. The
connection page applies the saved interface-size preference to its own UI context, including
retry and Wi-Fi recovery controls, while the host keeps system metrics for video and touch.
Connection failure guidance replaces the preparation instructions on this same scaled page,
then restores those instructions when the next connection stage starts.
Both pages align their 64px close control and title group with the OEM title bar: a 40px start
inset, a centre 64px below the panel top and a 32px gap before the title group. The group keeps
the green CarPlay icon at 40px, followed by the 40px DiPlay title with a 12px gap. These anchors
stay independent of interface scaling, and the title truncates to fit the available width.

Page artwork and app launcher icons use the vector converted from `docs/assets/WT_CarPlay_icon.svg`,
retaining its original green gradient, white C/play paths and continuous rounded outline.
On Android 8 and newer the Mazda launcher wrapper removes the source's 3-unit outer margin
from its 126-unit viewport to fill the OEM app-centre slot. Android 7 uses the same vector
with its original margin. No background frame overrides the supplied outline or gradient.
The Mazda launcher drawable variants live only in `mazda/src/main/res/`; their rendering
test lives in `mazda/src/test/` and runs with `:mazda:testDebugUnitTest` in CI.
Disconnecting from Home suppresses the retiring CarPlay window's exit animation; session
teardown still completes asynchronously before Home re-enables device selection.

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
Visible sidebar changes animate only the panel's left edge using the OEM file manager's
400 ms accelerate/decelerate transition. Top, bottom and right edges stay fixed, so the
underlying AppList is not uncovered immediately before the sidebar arrives. Opening directly
in either shape places the panel immediately; reversing a transition starts at its current edge.
During that transition the current CarPlay view area uniformly covers the panel, clipping
overflow rather than shrinking the old wide frame into top/bottom black bars. Video and touch
use the same transform. At either exact OEM endpoint, the host requests the matching view
area immediately, without the normal 500 ms resize debounce; intermediate sizes still debounce.
A blurred, opaque mask fills only the CarPlay viewport from the first sidebar signal, before
the first animated resize. It remains across the entire window animation and rapid reversals.
After the endpoint's area command, it waits 450 ms for the iPhone's 300 ms animation and then
requests a fresh picture. A subsequent texture presentation (or a new rendered SurfaceView
frame) fades the mask out over 150 ms. Bounded timeouts prevent a stalled frame or missing
endpoint from leaving the mask stuck. CarPlay touches are paused while it is visible; the OEM
instrument panel and bottom controls remain outside the mask. Its low-resolution snapshot
uses centre cropping, with additional RenderEffect blur on API 31 and newer, so a sharp old
picture is never stretched into the new shape. Missing snapshots use an opaque colour gradient.
The existing CarPlay resize debounce waits for the final dimensions before changing view area.
The CarPlay OEM icon uses the same window-closing behavior after its `requestUI` command opens
the head-unit home screen. Mazda's launcher root and projection share the application task
affinity, and the projection uses `singleTop` with reorder-to-front inside that task. An OEM
screen transfer therefore moves both pages together instead of leaving a second task registered
on the other half. Returning with a live session keeps the launcher root under the projection.
Closing finishes every UI activity while retaining the background connection. It does not call
`finishAndRemoveTask`, which would notify the foreground service to stop the session.
The next launcher click opens the shared task in the requested area. Other variants retain their separate
`singleTask` projection. Before closing,
the owning Mazda host keeps a bounded, in-memory picture in that session. The reopened host
shows it while its video surface attaches, then fades it away when the new surface presents a
frame. A different or cleared session discards the picture. Mazda's two Activity themes disable
the Android starting preview so the launcher handoff does not insert a black window.

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

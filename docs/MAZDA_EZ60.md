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
instrument panel and bottom controls remain outside the mask. The snapshot crops the active
stream area before downsampling, excluding the unused right strip of a narrow CarPlay frame.
The complete picture stretches to each animated viewport size, with a tiny filtered blur image
above it. Both layers fill the window without a RenderEffect layer retaining the old width.
A two-stream-pixel right-edge sampling guard keeps a black encoder boundary out of the live
picture and snapshot. Live video retains uniform scale and the same touch transform; only the
temporary masked picture stretches. Missing snapshots use an opaque colour gradient.
The pixel regression fixture puts a narrow picture and a black right strip in one encoded
canvas, then renders every animated width. Large-font fixture screenshots show the
[compact mask](screenshots/mazda-transition-compact-large-font.png) and
[full mask](screenshots/mazda-transition-full-large-font.png); these are synthetic renderer
checks, not screenshots from an attached iPhone.
The existing CarPlay resize debounce waits for the final dimensions before changing view area.
The CarPlay OEM icon and both page close buttons use the same `exitApp` window-closing path.
Their `requestUI`/close action reveals the previous head-unit page, including an open AppList,
without launching HOME in the current area. Mazda's launcher root and projection share the application task
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

### OEM return and reopening from AppList

Current behavior: Mazda consumes OEM `requestUI` and calls the same `exitApp` as the home
page's top-left close button. It closes all DiPlay UI windows while keeping the background
CarPlay session. It does not start `AppContainerActivity`, MainHome or a generic HOME.
Opposite-area stale DiPlay records and package-update recovery retain their guarded refresh.
The following logs describe the earlier routing/transport fixes and subsequent corrections.

The 2026-10-14 09:09:55 recording and companion log show a launcher routing failure rather
than a DiPlay crash. At 09:10:04 the controller launched generic HOME without
`launcher_display_area`, then finished the UI while retaining the session. At 09:10:05 the
driver AppList click found DiPlay in WT Launcher's opposite-area top-activity cache and chose
`flyScreen`; that operation returned because the actual opposite task was the launcher/AppList.
No DiPlay Activity START was issued. The passenger click at 09:10:08 did issue a START and
successfully adopted the background session.

The supplied `WT_NewLauncher.apk` establishes the OEM contract: `CommonUtil.goHome` starts
`com.tinnove.launcher/.AppContainerActivity` with MAIN/HOME, NEW_TASK/RESET_TASK_IF_NEEDED
and `launcher_display_area=area_left|area_right`. Its `onResume` starts MainHome/CopilotHome
in the specified TaskView. `wt.intent.action.gohome` is also handled by its exported dynamic
receiver. Finishing an ordinary app alone does not reliably replace WT's cached top activity.

The initial Mazda fix handled the OEM request before the controller's default HOME. It routed the OEM
HOME by the window's live physical screen position (both halves use Android display 0), with
the launch-area hint only before layout. If the opposite area's published top record still
names this application's package, it requests that area HOME via WT's package-scoped gohome
broadcast as well. It does not write the OEM's status fields. A record naming another passenger
application does not trigger this refresh. A broadcast is used for the old area because two
consecutive AppContainer starts can coalesce their extras before onResume. Missing OEM home
falls back to ordinary HOME. All Mazda UI windows still finish without removing the task,
so the foreground service and phone session are retained; stale owner callbacks do not navigate.

Regression tests cover driver/passenger routes, same-size task moves, opposite-area stale
records, another passenger app, duplicate/stale callbacks, controller UI reattachment and
missing OEM home. These verify DiPlay's requests and session retention. The actual WT Launcher
cache refresh, AppList relaunch and repeated driver/passenger transfers require EZ60 validation.

Windows verification for this OEM return change on 2026-10-14 passed the full Android workflow
command (including Mazda): 2816 unit tests, zero failures/errors, all Debug lint/assembly tasks,
and Mazda Release lint/assembly. The focused OEM return, controller routing and task adoption
suites passed all 16 tests. The release package remains `com.thunder.carplay`, version 0.2.19.

The later 11:37 log records repeated OEM `requestUI` commands, each answered with RTSP 200,
without any HOME launch. The log did not record which UI guard rejected the command. A real
RTSP regression reproduced the same no-HOME result when a current wireless controller's control
connection differs from the Activity's cached video-session object. The previous UI handler
consumed the request before dispatch but then rejected that object mismatch on the main thread.
The OEM action is now controller-wide: the controller rejects closed controllers and obsolete
wireless runs; the Mazda UI checks its generation, lifecycle and background-session ownership
without requiring the command connection to be the cached video session. Routing and rejected
requests are logged explicitly. The regression sends a binary-plist `/command` on a background
thread through the AirPlay handler and wireless listener, verifies RTSP 200 plus one
area-aware HOME, and checks that closing the UI retains the session. Main/video callbacks keep
their existing per-session checks. Vehicle verification of the new package is still required.

The 12:29:45 recording and 12:30 log still show repeated OEM clicks and RTSP commands with no
return to HOME. Inspection of the shipped APK established the missing transport step: the
AirPlay handler calls `onHostUiRequested(AirPlaySession): Boolean`, but the incrementally built
VPN service wrapper retained its former `Unit` delegate. Android therefore selected the
interface's default Boolean implementation, which does nothing. The earlier controller/UI
tests bypassed this wrapper and did not establish that the packaged transport forwarded OEM.
The service now forwards this callback explicitly. A regression sends a binary-plist command
over an accepted TCP socket and reproduced the no-op before this fix (RTSP 200, no callback).
The Mazda regression now uses that same service/socket path through the controller and UI,
checking the area-aware HOME and retained session. Verification includes rebuilding shared
outputs and inspecting the Release APK's transport callback signature and forwarding call.

The final Windows check for this transport fix passed the full Android workflow: 2820 tests,
zero failures/errors/skips, plus all Debug lint and assembly tasks. The focused transport,
OEM-home and task-routing suites passed 22 tests. An initial full run could not generate the
common test report (`EOFException` reading Gradle's binary results); retaining that output
directory under a backup name and rerunning with fresh test outputs resolved the report error.
Mazda Release lint/assembly and APK v2 signature verification passed. The APK generated at
13:37 on 2026-10-14 has SHA-256
`621475717419F43CFA3EF6AD457A0B657DBDD0FC027D0ED03487AA053E3B8B5B`.
Its service wrapper has the Boolean callback and invokes the listener's Boolean callback;
the former Unit forwarding method is absent. These checks establish the packaged forwarding
path; OEM desktop behavior and same-area AppList reopening still require vehicle validation.

The 14:03 installation log identifies a separate driver AppList failure. Package replacement
force-stopped the old process and WT received a vanished task with no activities, yet retained
task 1225 as the passenger area's top DiPlay task. Driver AppList clicks then selected
`flyScreen(false)` instead of starting DiPlay; the passenger click finally issued a real START.
This failure occurs before any DiPlay Activity can run, so Activity-only recovery cannot fix
the first driver click after updating. Mazda now registers a non-exported
`MY_PACKAGE_REPLACED` receiver that asks WT's package-scoped `wt.intent.action.gohome` receiver
to refresh an area only when its published visible record still names this package. It does
not write WT's status fields or open DiPlay/connect CarPlay during the update. Android's
package update itself still ends the old process/session.

The same log shows that ordinary close can leave the opposite area's DiPlay record after a
task move. Home/connection close now refreshes a stale opposite record before closing only
the DiPlay UI, revealing the current area's existing Launcher page, including AppList. It
does not start HOME or broadcast gohome for the current area. The OEM system-bar close refreshes the opposite record
while retaining the system bar's own current-area navigation. These ordinary exits retain
the background CarPlay session. Another package's area is not refreshed. Receiver regressions
cover both areas, unrelated/hidden/malformed records, and non-Mazda variants; a Mazda merged
manifest test checks that the receiver is registered and non-exported. Windows verification
passed all 2827 unit tests with zero failures/errors/skips and every workflow Debug lint/build
task. Actual WT cache recovery on APK update and first driver AppList launch still require
vehicle validation.

The update-recovery release passed Mazda Release lint/assembly and APK v2 signature
verification. The APK built at 14:40 on 2026-10-14 remains `com.thunder.carplay` 0.2.19 (38),
with SHA-256 `8FC3FE06487C8C6F49AC3397BE3687ACE6FAC540CB8A9D90891B8D10B48B7941`.
Inspection of its actual manifest and DEX confirms the non-exported update receiver/action,
the Mazda recovery call, and the Boolean OEM service delegate retained from the previous fix.

Vehicle feedback on that package corrected the ordinary close behavior: the previous
home/connection close called HOME, replacing an already-open AppList with the desktop.
Those close buttons now finish DiPlay's windows without starting an Activity or sending a
current-area gohome broadcast. A stale opposite DiPlay record can still be refreshed, and
the background session remains alive. Regressions cover driver close with a stale opposite
record and passenger close with another driver's app; both verify no HOME Activity starts.
At this stage the explicit CarPlay OEM return and package-update recovery retained their separate behavior.
Windows verification of this correction passed all 2828 unit tests (zero failures/errors/skips)
and every Android workflow Debug lint/assembly task. The focused exit/OEM/update suites passed
20 tests.
Mazda Release lint/assembly and APK v2 signature verification also passed. The corrected APK
built at 15:32 on 2026-10-14 has SHA-256
`8686B24954C00A98ED53C7971E972E8DF909126B700722AD70C6440E27F051AC`.
Its `exitApp` bytecode calls only opposite-record refresh and window close, with no HOME
Activity launch. Revealing the actual WT AppList after close still needs vehicle verification.

The 17:48 log identifies the remaining difference: the OEM `requestUI` dispatch at 17:48:31.706
still started MAIN/HOME at 17:48:31.716, followed by WT's desktop/MainHome launches. Only the
page close buttons had been corrected. OEM now calls their same `exitApp`; the unused Mazda
HOME launcher and fallback were removed. The real service/socket regression now verifies a
successful OEM command closes both windows with no started Activity and keeps the session.
All Mazda exit regressions reject any HOME start and cover both areas, duplicate/stale callbacks,
and guarded opposite-area refresh. Non-Mazda controller routing remains unchanged.

Mazda now seeds MTK decoder low latency on by default when its preference is absent, for
both new installations and already-initialized Mazda upgrades. A saved on/off choice is
preserved, and other builds retain their original off fallback. The existing setting remains
in Advanced with its existing experimental label, decoder fallback and next-connection
description. Initialization does not reconnect a running CarPlay session. Regression checks
cover first startup, upgrade without this key, saved on/off values and later user changes.
Windows verification of the combined OEM exit and MTK default changes passed the complete
Android workflow with 2829 unit tests and zero failures/errors/skips, plus all Debug lint and
assembly tasks. The focused OEM/exit/update/controller tests passed 25 tests before the MTK
change; the full workflow includes the new default/upgrade regression.
Mazda Release lint/assembly and APK v2 signature verification passed. The combined release
built at 18:24 on 2026-10-14 has SHA-256
`4AAEADC2A58BDB2B5772CFD37213E1F67A5DD8C4F842EE22B7D103671B23D81B`.
Actual APK DEX inspection confirms the OEM listener calls `exitApp`, the Mazda HOME method
and category are absent, and missing MTK tuning is saved with Boolean true. The release still
uses `com.thunder.carplay` 0.2.19 (38). Actual AppList preservation after OEM exit and MTK
decoder behavior still require vehicle validation.

Run the original repository CI command in `AGENTS.md`, plus `:mazda:lintDebug`, a standalone
Mazda release build, and the Mazda tests in `:common` / `:shared`. Native widget rendering tests
write home/waiting screenshots for 1728 and 2496 pixel widths, light/dark appearance, large
fonts and Arabic RTL to `common/build/reports/mazda-ui/`. Emulator screenshots and UI dumps
are under `mazda/build/reports/emulator/` when captured locally.

Real EZ60 validation must cover firmware permission grants, AP startup/readback, iPhone pairing,
CarPlay video/touch, sidebar changes in both directions, and returning to the car UI with the
session retained. An ordinary Android emulator cannot establish these OEM-specific behaviors.

On 2026-10-14, transition regression verification on Windows passed the complete Android
workflow command including Mazda: 2807 tests with no failures or errors, Debug lint/builds,
and Mazda Release lint/assembly with APK v2 signature verification. The supplied 1920×544
recording shows a two-column black strip at the panel's right edge near 4.0 seconds during
the masked transition; sampled settled narrow frames do not show that strip. The recording's
scaling/compression cannot establish a one-physical-pixel border on the 5120×1440 head unit.
The synthetic pixel checks cover a black last active column, unused canvas pixels, both stream
sizes, all animated panel widths, and the host's capture before expanding a narrow view area.
The new package still needs validation of the complete animation on the actual EZ60.

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

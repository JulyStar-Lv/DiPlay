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
four-finger swipe-down settings gesture and automatic application appearance. It marks the existing setup
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
Visible sidebar changes animate only the panel's left edge using the OEM Launcher AppList's
400 ms accelerate/decelerate transition. Like its MotionLayout, the panel advances a persistent
linear 0..1 progress and applies easing to that progress before positioning the edge. Reversing
mid-transition retraces the same curve for the remaining progress; it does not start a new
easing curve timed from an already-eased pixel distance. Top, bottom and right edges stay fixed.
Equal timing alone cannot synchronize separate foreground, AppList and sidebar windows.
During visible motion an opaque neutral colour covers the exposed strip between the full
panel's left edge and its moving left edge; it is removed one animation-scaled transition
interval after the foreground settles. This prevents the moving edge from exposing a lagging
AppList without drawing old CarPlay controls outside the video viewport. Coverage stays inside
the application bounds and below the OEM sidebar window; hiding/detaching or switching task
area clears it. No CarPlay screenshot is captured or drawn for this backdrop.
Opening directly
in either shape places the panel immediately; reversing a transition starts at its current edge.
The reference is Launcher AppList's `applist_bg_scene.xml` (full_screen/half_left),
whose unspecified timing uses its bundled MotionScene defaults: 400 ms and interpolator 0
(accelerate/decelerate). `BaseAppListActivity` confirms visible changes use
`transitionToState`; first entry and background changes use `jumpToState` instead.
Hidden DiPlay windows cancel pending motion and restore the current endpoint without replay.
The AppList's separate 500 ms entry fade and 800 ms grid delay do not control panel resizing.
During that transition the current CarPlay view area uniformly covers the panel, clipping
overflow rather than shrinking the old wide frame into top/bottom black bars. Video and touch
use the same transform. At either exact OEM endpoint, the host requests the matching view
area immediately, without the normal 500 ms resize debounce; intermediate sizes still debounce.
A solid, opaque mask fills only the CarPlay viewport from the first sidebar signal, before
the first animated resize. It remains across the entire window animation and rapid reversals.
After the endpoint's area command, it waits 450 ms for the iPhone's 300 ms animation and then
requests a fresh picture. Each new texture presentation (or rendered SurfaceView frame) allows
an asynchronous PixelCopy of the decoder Surface's raw canvas, before the view transform crops
it. Two successive checks must match the settled view area's active picture and unused black
padding before the mask fades out over 150 ms. An old frame, failed copy or ambiguous entirely
black picture keeps the mask visible during the normal guard; timeouts retry the layout and
request a fresh picture. An eight-second maximum guard restores video and touch if the pixel
heuristic never succeeds. This pixel heuristic checks geometry, not an iPhone
acknowledgement of the area command. CarPlay touches are paused while the mask is visible;
the quick-menu gesture, OEM instrument panel and bottom controls remain available.
A two-stream-pixel guard remains for the full area. The narrow area excludes twelve source
pixels at 1080-high resolution (six at 540), covering its measured coloured edge plus filtering
margin in live video and the target-area pixel check. Live video retains uniform scale and
the same touch transform. Transition coverage contains no stretched or blurred old picture.
The pixel regression fixture puts a narrow picture and a black right strip in one encoded
canvas, then renders every animated width. Large-font compact/full mask fixtures are generated
under `common/build/reports/mazda-ui/`; these are synthetic renderer checks, not screenshots
from an attached iPhone. Generated screenshots are no longer stored in the source tree.
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

## Mazda source boundaries (2026-10-17)

The supplied green CarPlay artwork is an application resource override in
`mazda/src/main/res/drawable/ic_carplay.xml`. The common module retains upstream's PNG,
so mobile and other applications keep their original icons. The launcher wrappers remain
in the Mazda application module.

`MzdHomeView` owns the combined phone-selection/disconnect action and reads the live session
on every click. The common home retains upstream's separate selection and disconnect buttons.
`MzdConnectionPreflight` limits the immediate Wi-Fi Direct warning to Mazda; other builds
retain upstream's connection entry behavior.

`MzdVideoCoordinator` owns transition-mask scheduling, raw decoder-frame inspection and
return-picture capture. The host only supplies current video state and lifecycle/resize hooks.
Normally two verified target presentations release the mask. An eight-second maximum guard
requests layout/picture recovery and fades the mask even when a valid dark picture or failed
pixel copy cannot pass the heuristic. This preserves access to CarPlay touch and settings
without stopping or reconnecting a session. A persistent underlying video failure can remain
visible after this recovery and still requires diagnosis; the guard is not a video-frame acknowledgement.

## System hotspot behavior

Mazda uses the upstream hotspot flow, aligned with upstream `main` at `1b1ac5f2`.
There is no Mazda preparation worker, system-credential injection, forced 5 GHz configuration,
SoftAP observer, or hotspot-specific foreground toast. The Mazda manifest no longer requests
`NETWORK_SETTINGS` or `TETHER_PRIVILEGED`. Hotspot status, validation, failure guidance and
manual retries use the upstream Activity/controller behavior inside the Mazda page shell.

For Car hotspot (`MANUAL`):

1. Turn on the car's hotspot in its own settings. Choose 5 GHz there if available.
2. In DiPlay, open **Settings → Connection**, select **Car hotspot**, and save the exact hotspot
   name and password. Check the saved security/band choices against the car's settings.
3. Pair the iPhone in the car's Bluetooth settings, select it in DiPlay and connect. Keep the
   iPhone's Bluetooth and Wi-Fi on.

The home instructions describe these steps in every supported language. Upgrades retain saved
preferences, but earlier automatic preparation only supplied credentials in memory; an existing
installation may still need its hotspot details saved before it can connect. No automatic
credential migration is performed. If the car changes its hotspot name or password, update the
saved details in DiPlay.

`ManualHotspotManager` attaches to the already-running system-owned hotspot and uses the
upstream interface/address, BSSID, security and channel discovery. Available system configuration
is read for validation; it does not replace the saved name/password. If the firmware hides the
channel, the upstream flow advertises channel 0 (auto). DiPlay does not change the system hotspot's
band or stop it when the CarPlay session ends.

Optional automatic startup follows the upstream opt-in `CarHotspotSettings` /
`CarHotspotTethering` flow. For non-BYD units, its settings entry is offered only on Android 8–10
and requires Modify system settings access. On Android 11+ non-BYD Mazda firmware, use the car's
own hotspot controls; the removed privileged startup is not replaced by a Mazda-specific ADB path.
USB, Wi-Fi Direct and existing Wi-Fi continue to use their shared connection managers.

## Verification

### Upstream hotspot alignment (2026-10-16)

`MzdHotspotFlowTest` checks that a Mazda head unit with both former platform hotspot grants
still opens connection setup when saved credentials are missing, and shows the upstream hotspot-off
dialog when a saved hotspot is off and automatic startup is disabled. The preparation and
configuration-copy tests were removed along with their implementation. Hotspot failure no longer
uses the Mazda foreground toast; the generic toast remains available for other page hints.

The existing `MzdPageViewTest` generates the updated home instructions at compact/full panel
widths (1728/2496), font scale 1.5, and Arabic RTL. Existing cockpit, OEM-return, task-routing,
video-transition and panel-coverage suites continue to cover the retained Mazda presentation.

The full Windows workflow attempt on 2026-10-17 did not pass: an asynchronous audio wait timed
out, and common tests stopped at a test-class loading failure while other builds were using
the same output tree. That attempt was stopped after exceeding the workflow's 30-minute limit
during lint analysis. Follow-up hotspot/Mazda checks use separate build directories and a
separate Gradle project cache; their scoped results must not be read as a full-workflow pass.

The isolated unit-test selection passed 419 tests with zero failures/errors/skips: 172 shared
hotspot/audio tests, 233 common hotspot/Mazda/host-settings tests, 6 Home tests and 8 Mazda tests.
This includes both new hotspot-flow regressions and all 18 tests in the audio class that
previously timed out.

The isolated follow-up on 2026-10-17 passed Debug lint and assembly for Mobile, Home, Maphost
and Mazda. All four lint reports contain zero errors/fatal issues. The merged Mazda manifest
omits both former platform hotspot permissions, and the Debug APK's DEX files contain none
of the removed Mazda hotspot preparation/manager classes.
After the latest animation edits, a final run of `MzdHotspotFlowTest`,
`MzdTransitionMaskTest` and `MzdVideoTransitionTest` passed all 22 tests with zero
failures/errors/skips against the current sources.

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

On 2026-10-15, FileManager animation alignment passed 20 focused cockpit/video/mask tests
and the complete Android workflow including Mazda: 2829 tests with zero failures/errors/skips,
plus Debug lint and assembly. Fresh narrow/full home renders at font scale 1.5 were inspected.
Mazda Release lint/assembly and APK v2 signature verification passed. Actual APK DEX inspection
confirms float progress, linear progress timing and accelerate/decelerate edge interpolation.
The 0.2.19 (38) `com.thunder.carplay` release built at 08:52:26 (Asia/Shanghai) has SHA-256
`37D1CDBB416AFA8015A5893BB2070329294AE981763C5A4FE1B296AA57AA65DD`.
Comparison of the complete transition against FileManager on the vehicle remains required.

The subsequent Launcher AppList alignment verifies its bundled MotionScene defaults,
matches both nonzero sidebar signals, and jumps hidden windows to their current state.
That build also added a localized Launcher-style hotspot failure hint, removed by the
subsequent upstream hotspot alignment described above.
Windows verification passed the complete Android workflow including Mazda: 2834 unit tests
with zero failures/errors/skips, plus every Debug lint and assembly task. The combined
focused suites passed 20 tests; four toast tests also passed after aligning the native-render
fixture's RTL support with the Mazda manifest. Compact/full large-font and Arabic RTL toast
renders were inspected. Native renders and the retained full CI report are under
`common/build/reports/mazda-ui/` and `common/build/reports/tests/launcher-applist-toast-full-ci/`.
Actual AppList/DiPlay frame synchronization and vehicle hotspot failure behavior still
require verification on the EZ60.
Mazda Release lint/assembly and APK v2 signature verification passed. The combined
`com.thunder.carplay` 0.2.19 (38) APK written at 13:08:28 (Asia/Shanghai) on 2026-10-15
has SHA-256 `B51B97C954679D3F3259647FEB90F48AC4E7A1C564C33403853D4B637D503BA0`.

The afternoon vehicle recording `record_20261015_140112.mp4` still shows AppList
through the moving left edge. Equal motion parameters do not synchronize the
independent windows. The shell now retains an opaque blurred backdrop across
the swept application region and a bounded, animation-scaled settling interval.
SurfaceView-style clear pixels are flattened after capture. A native composite
test deliberately leaves the background window at its old bounds and checks
coverage, OEM margins, reversal, slow animation and lifecycle cleanup.
The complete Android workflow including Mazda passed again: 2837 tests with
zero failures/errors/skips, all Debug lint and assembly tasks. A final cleanup
for reversal before the first progress tick was then verified by 14 focused
cockpit/video/coverage tests. Release lint and assembly passed on that final source.
The full report is retained at `common/build/reports/tests/panel-coverage-full-ci/`;
its build log is `common/build/reports/mazda-ui/panel-coverage-full-ci.log`.
Compact/full large-font renders were inspected and match the final CI renders.
The new 0.2.19 (38) `com.thunder.carplay` APK was written at 14:49:21
(Asia/Shanghai) on 2026-10-15, with SHA-256
`EF9B8F1D4A26F8805883A121C36EF2FB5A8D6CE9AAEBEAC795F32001162552E6`.
APK v2 verification passed and its signing certificate matches the earlier package.
Actual OEM window compositing must still be checked on the EZ60, including
continuous switching and reversal during entry/exit; no vehicle was connected locally.

The 2026-10-15 upstream integration advances from `c30d3f0d` to `1b1ac5f2`, retaining
all 100 upstream commits (52 excluding merge commits) and the Mazda-specific history.
The application version follows upstream 0.2.20 (39); the Mazda package ID, launcher icon,
OEM/AppList exit behavior, task-area routing, MTK default and transition coverage remain intact.
That merge retained Mazda hotspot cancellation (removed by the subsequent upstream hotspot
alignment) and upstream temporary-bar cleanup; waiting-page appearance refresh now applies to
every build. The new black-corner overlay adds
a layer below video gestures, so the Mazda waiting page replaces the original preparation
page at its existing index rather than at a fixed child index. A touch-dispatch regression
checks that its close button remains reachable above the gesture layer.
Historical tracked screenshots were removed as requested. CI generates swipe-menu, corner,
wheel-media and Mazda UI screenshots under build reports and uploads them with test reports.
Native swipe-menu, corner-setting and Mazda waiting-page renders were inspected at font scale
1.5 in compact/full windows, including Arabic RTL. The touch-dispatch regression also checks
the incorrect layer order as a negative control, without relying on an unattached button's
posted click callback.
The final complete Android workflow on Windows, including Mazda, passed with 3082 tests
(1528 shared, 1540 common, 6 Home and 8 Mazda), zero failures/errors/skips, and every Debug
lint/assembly task successful. The focused touch-dispatch regression also passed separately.
Public-tree and diff-format checks passed. Debug output metadata confirms version 0.2.20 (39)
and `com.thunder.carplay.mazdatest`; release package identity remains `com.thunder.carplay`.

Real EZ60 validation must cover manual hotspot setup, missing/stale saved credentials, hotspot
off/on, iPhone pairing, CarPlay video/touch, sidebar changes in both directions, and returning
to the car UI with the session retained. An ordinary Android emulator cannot establish these
OEM-specific behaviors.

On 2026-10-14, transition regression verification on Windows passed the complete Android
workflow command including Mazda: 2807 tests with no failures or errors, Debug lint/builds,
and Mazda Release lint/assembly with APK v2 signature verification. The supplied 1920×544
recording shows a two-column black strip at the panel's right edge near 4.0 seconds during
the masked transition; sampled settled narrow frames do not show that strip. The recording's
scaling/compression cannot establish a one-physical-pixel border on the 5120×1440 head unit.
The synthetic pixel checks cover a black last active column, unused canvas pixels, both stream
sizes, all animated panel widths, and the host's capture before expanding a narrow view area.
The new package still needs validation of the complete animation on the actual EZ60.

The supplied `20261017T051839Z` capture rules out the proposed eight-pixel parent offset:
geometry id 9 places both TextureView and its parent at (832,192)–(2560,1272), with identity
view matrices, no padding/scroll, and a global translation of (832,192). Its content rectangle
is (0,-0.6257324), 2498.892×1081.2515, and the TextureView matrix scales by
(1.4461181,1.0011587). The transformed full canvas extends past the viewport; this is expected
when selecting 1728 pixels from the 2496-wide canvas. In the screenshot, the straight picture
edge changes near x=2553 while the blue/bright band continues through x=2559. With the
source scale 1728/1726, that edge is near source x=1719, inside the advertised narrow area.
The narrow sampling guard therefore increases from two to twelve source pixels and scales
with stream height, while the physical panel bounds and full-area guard remain unchanged.
Live video, touch inversion and transition captures use the same geometry policy. This is
a targeted crop of the observed video-content edge, not evidence of a particular encoder or
decoder defect: no raw decoded frame was included. The last geometry sample precedes the
screenshot by about 43 seconds, so this capture establishes stable layout, not identical frames.
Native renderer regression fixtures model a ten-source-pixel coloured band, both stream sizes,
nonzero area origins and the animated widths; they are not real iPhone output.

The old two-pixel policy failed the new native edge fixture at local x=1720. After the crop
change, all 14 focused geometry/edge/transition tests passed. The complete Android workflow,
including Mazda Debug and Release lint/assembly, then passed with 3149 tests and zero
failures/errors/skips. Compact/full large-font native renders were inspected; APK v2 signing
and the existing certificate were verified. The 0.2.20 (39) edge-fix APK has SHA-256
`6D3532E5DCB00ADBE8171701D94FE399E0C9B747F5F5604212BB090FCE3C5C41`.

Vehicle follow-up `20261017T054306Z` confirms the crop is active: all 14 geometry groups
from the new process are complete; narrow samples consistently report (0,-3.776245),
2513.4546×1087.5525, and a TextureView matrix of (1.4545455,1.006993) with translation
(0,-3.776245). Narrow/full switches retain the fixed right edge at 2560; settled full samples
retain the original geometry. The screenshot shows continuous picture colours at x=2553–2559
and the visible right-side controls are intact. The last geometry group precedes the screenshot
by about ten seconds, so it is a settled-layout check rather than a frame-atomic capture.
Window and SurfaceFlinger dumps show the expected 2560×1440 surface, alpha 1, identity
composition transform and no queued frames. UI gfxinfo reports 100/1591 janky frames (6.29%);
this cumulative mixed-UI sample is not a controlled video frame-rate measurement. No new
layout/composition problem is evident, and the user reports the seam is resolved visually.
The temporary geometry listener, logger, logger-only tests and acquisition script are removed
after this confirmation; the edge crop and native video/touch/transition regressions remain.
The clean 0.2.20 (39) release retains package `com.thunder.carplay` and the existing signing
certificate. APK v2 verification passes, and its DEX files retain the edge guard without the
temporary diagnostic class or log markers. Its SHA-256 is
`FF18339B4F73337DA7B6684161A35F80E23C28CE33BC78FFB0DD7565AC8F7604`.
After cleanup, the complete Android workflow plus Mazda Release lint/assembly passed in
12m 52s: 3147 tests (1554 shared, 1579 common, 6 Home, 8 Mazda), zero failures/errors/skips.
Public-tree and diff-format checks passed.

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

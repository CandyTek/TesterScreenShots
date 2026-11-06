# Tester Screenshot

An Android screenshot tool for QA testers. It has a "Screenshot" button in the notification shade, groups screenshots into workspaces, and has a LAN web page (opened from a QR code) for downloading everything at once.

Java + ViewBinding only. No Kotlin, no AppCompat/Material/RecyclerView, and no third-party libraries. The only dependency is `androidx.documentfile`. minSdk 21, targetSdk 29 (on purpose: legacy external storage, and no Android 12+ notification-trampoline restrictions), compileSdk 35.

## Build

```
gradlew assembleDebug --offline      # app/build/outputs/apk/debug/TesterScreenShot-debug.apk
gradlew testDebugUnitTest --offline
```
AGP 8.7.3 / Gradle 8.9, with the same mirrors as ApkToolHua. `gradle.properties` caps the daemon at `-Xmx1024m` because this machine's page file is nearly full.

## How a capture works (`capture/CaptureEngine`)

1. **Trigger:** a notification button, the Quick Settings tile, or the timer button in the app.
2. **Choose a method.** In *Automatic* mode:
   - **root** (`su -c screencap`)
   - **network ADB:** the port is read through `SystemProperties` reflection (`service.adb.tcp.port`, falling back to 5555). The app connects to its own `adbd` on 127.0.0.1 with the ADB wire protocol and RSA auth (`capture/adb/`) and runs `exec:screencap`.
   - Optional fallbacks:
     - **accessibility service** (`takeScreenshot`, Android 11+)
     - **screen-recording permission** (MediaProjection; the virtual display stays alive, so a capture just copies the latest frame)
3. **Close the notification shade.** With root/ADB this runs `cmd statusbar collapse` (`service call statusbar 2` before Android 7). The accessibility service uses its own global action. Below Android 12 the app also sends `ACTION_CLOSE_SYSTEM_DIALOGS`. On Android 12+ with no shell or accessibility, the notification buttons go through a transparent trampoline activity, because launching an activity always closes the shade.
4. **Wait** for the shade animation (configurable, default 450 ms), plus the optional 3 s delay.
5. **Grab.** The raw `screencap` output is decoded directly (`RawScreencap`), with no PNG round trip. The header length is worked out from the total size, so the 12-byte (≤ Android 11) and 16-byte (12+) headers both work.
6. **Thumbnail.** It appears straight away from memory in the bottom-left corner. Tapping it opens the editor; swiping it away dismisses it.
7. **Save.** A background thread encodes the image (JPEG 95 by default; PNG and WebP are settings) into `Pictures/TesterScreenShot/<workspace>/`, then calls the media scanner. Optional side files:
   - `.txt` with device, build, foreground app and timing
   - `.log` with recent logcat

If the grab fails, nothing else happens, as the spec requires. The reason shows under Settings → Status.

## Other pieces
- `ui/MainActivity` is the workspace grid. It has delete with confirmation, add from gallery, and switch/new/rename/delete workspace (long-press a workspace). The LAN QR code panel sits on the right half in landscape and the lower half in portrait, with an interface picker (Wi-Fi / Ethernet / hotspot / USB) and copy/open link. It is hidden when the server is disabled.
- `ui/PreviewActivity` + `ui/EditorView` + `ui/editor/*` form the preview and editor. Tools: pen, line, arrow, rectangle, oval, text, mosaic, crop. It also has colours, three stroke widths, undo/redo, overwrite or save-as-copy, share and delete.
- `server/LanServer` is a plain `ServerSocket` on 0.0.0.0, so it serves every interface at once. Every request needs the random token from the QR link. Endpoints:
  - `/` serves the page, `assets/web/index.html`. It offers a workspace picker and a grid with every image ticked by default, plus a lightbox viewer. "Download selected" saves each selected image individually by default. The "include logs/metadata" checkbox (off by default) adds the .txt/.log side files. Settings → LAN server → "Download as one zip file" switches it to a single zip.
  - `/api/workspaces`, `/api/list`
  - `/img/...` (supports Range requests), `/thumb/...` (cached)
  - `/zip` streams the zip as it is built
- `service/ShotService` is the foreground service. It holds the notification, the LAN server (running until disabled in Settings) and the screen-recording session.
- `util/QrCode` is a self-written QR encoder (byte mode, versions 1–40, all ECC levels).

## Setting up a test device
- **Root:** Magisk/KernelSU-style `su` that allows apps. AOSP `userdebug` su only allows the shell user, so it won't work for the app.
- **Network ADB:** run `adb tcpip 5555` once (or set `persist.adb.tcp.port`). On first use the device asks to allow USB debugging for the app; tick "Always allow".
- **Accessibility:** Settings → Accessibility → Tester Screenshot capture (Android 11+).
- **Screen recording:** Settings → Grant screen recording.
- **Overlay permission** (for the thumbnail): `adb shell appops set com.rc.testershot SYSTEM_ALERT_WINDOW allow`, or grant it from the banner in the app.
- **Logcat side file without root/ADB:** `adb shell pm grant com.rc.testershot android.permission.READ_LOGS`.

## Status (2026-11-06)
Verified on rk3566 boards running Android 11 and 13 via network ADB:
- capture from the notification button, with the shade correctly gone from the image
- thumbnail overlay
- metadata side file and foreground-app detection
- grid, preview, and editor (pen and arrow, saved as a copy at full resolution)
- LAN server: token check, JSON API, zip with side files, path-traversal rejection, page layout

**Not yet exercised on a device:**
- the root method (the boards' `su` is shell-only)
- the ADB authorization dialog (these boards have `ro.adb.secure=0`)
- the accessibility and screen-recording methods
- the navigation-bar crop option
- Android 5–10 devices

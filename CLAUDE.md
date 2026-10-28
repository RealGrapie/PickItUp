# PickItUp! — Ozon pickup barcode on Pebble

Shows the Ozon order-pickup barcode on every Pebble: one `.pbw` for all 7 SDK platforms —
aplite (Pebble/Steel, 144x168 B/W, 24 KB RAM), basalt (Time, 144x168 colour), chalk (Time Round,
180x180 round), diorite (Pebble 2, 144x168 B/W), emery (Time 2, 200x228), flint (Pebble 2 Duo,
144x168 B/W), gabbro (Round 2, 260x260 round).  An Android companion logs in to Ozon, fetches the code and pushes it
to the watch.

```
watch/src/c/   Pebble C watchapp (SDK 4.33.1)
  main.c       UI (barcode / rotated / QR test view, setup screens), AppMessage, persist, timers
  code128.c    Code 128 encoder (set B/C switching)
  qr.c         QR encoder: byte mode, level M v1-2 (26 bytes), level L v1-5 (106 bytes)
android/       Kotlin/Compose companion, package com.grapie.pickitup, PebbleKit2 (io.rebble.pebblekit2)
  OzonClient.kt         hidden-WebView fetch of the barcode, login/cookie checks
  BarcodeSync.kt        fetch + push to the watch, WatchKeys (AppMessage keys, watch UUID)
  WatchListenerService  bound by the Pebble app when the watchapp sends a request
  LoginActivity.kt      Ozon site in a WebView: login (auto-closes) or any page (orders)
  BaseActivity.kt       theme/strings wrapper, battery helpers, shared screen parts (top bar, rows)
  MainActivity.kt       main screen (onboarding / barcode), splash;  SettingsActivity, DebugActivity
  Theme.kt              M3 scheme from a seed (OKLCH tonal palettes), SeedColor, PickItUpTheme
  Components.kt         shared M3 bits (pill buttons with shape morph, section cards, BarcodeView)
  Splash.kt             animated splash after the system one;  Watches.kt  connected watch model
  Strings.kt            all UI texts, RU + EN;  AppSettings.kt  language, theme, flags
  BarcodeStore.kt       saved code, settings, journal;  Code128.kt  same encoder as the watch
  RefreshSchedule.kt    interval / daily refresh math;  RefreshJobService.kt  background job
  Updater.kt            update check (GitHub Releases API) + APK download -> system installer
```

## Identity

- Name **PickItUp!** with the "!" everywhere people see it (watch menu `displayName`, launcher
  label, `APP_NAME` in BaseActivity.kt for titles / splash / texts). Code names stay `PickItUp`
  (classes, theme, Gradle project, repo `RealGrapie/PickItUp`, folder). Android package `com.grapie.pickitup`,
  watch UUID `553a866c-3355-471c-8239-a8dd9c2925a1`. Keep both stable: changing them makes a new
  app (lost login, settings, battery exemption, a second app on the watch). The old app was
  `com.grapie.ozonbarcode` / UUID `ae8a8bea-...` ("Ozon Barcode"); it has been removed.
- Watch header over the barcode: "OZON pickup code" (content, not the app name).
- Repo: https://github.com/RealGrapie/PickItUp = `COMPANION_URL` in main.c (QR on the watch, QR v3-L;
  decodes on all 7 platforms), `SOURCE_URL` in BaseActivity.kt (opens in the browser) and
  `companionApp.url` in package.json (`/releases/latest`).
- Icons: Material vector drawables in `res/drawable` (`ic_settings`, `ic_arrow_back`); add new ones
  the same way, not the material-icons library.
- Phone launcher icon (variant D "parcel with a barcode label"): adaptive icon
  `res/mipmap-anydpi/ic_launcher.xml` = background `@color/ic_launcher_background` (#005BFF) +
  vector `drawable/ic_launcher_foreground.xml`, also used as the `monochrome` layer (Material You
  themed icons), so its cut-outs must stay transparent (even-odd path), not filled with blue.
- README logo: `docs/logo.svg` = the launcher icon (same paths on #005BFF). README images:
  `docs/screenshots/watch.png` (emulator shots in the Pebble appstore frames
  `apps.repebble.com/images/screenshot_slider_background_{original,pebble2,time,time_round,emery_v2,gabbro}.png`),
  `phone-*.png` (emulator shots in the SDK skin `skins/pixel_9_pro_xl`, demo mode + SystemUI demo mode).
- README is English (`README.md`, default) + Russian (`README.ru.md`), linked to each other at
  the top: keep both in sync. Install buttons `docs/badges/{github,pebble-appstore,rebble}-{en,ru}.svg`
  ("Get it on Google Play" style, text as paths) come from `docs/badges/badges.py`. GitHub button =
  `releases/latest/download/pickitup.apk`; stores: apps.repebble.com/a085bb50ce2e4a239f1a1870,
  apps.rebble.io/en_US/application/6adfa2c6d0da320009a522f3.
- Watch menu icon (variant 6 "bold barcode"): `watch/resources/images/menu_icon.png`, 25x25,
  black on transparent so the launcher can invert it on the selected row.
- These two icons are final (phone D, watch 6). There is no separate logo.
- `targetPlatforms` lists all 7 platforms; there are no per-watch builds any more.

## How it works

- **Ozon API**: `POST https://www.ozon.ru/api/composer-api.bx/_action/v2/prefetchBarcode`, empty body.
  Response `data.barcode.code` (e.g. `<userId>*NNNN`), `visibleCode`, `cacheLifetime` (~100 h).
  The code does not depend on orders and rotates about daily. The mobile-app variant on
  `api.ozon.ru` needs an `x-ad-id` signature from Ozon's native antibot SDK — do not use it.
- **Antibot**: the website endpoint passes only with a full real-browser cookie/header set and
  a browser TLS fingerprint, so the companion calls `fetch()` from an ozon.ru page inside a hidden
  WebView, using the cookies left by `LoginActivity`.
- **Login**: `LoginActivity` has no "done" button; it polls `OzonClient.isLoggedIn()` (every 1 s and
  on page load) and closes itself with RESULT_OK on the guest -> logged-in transition.
  Login page = `https://www.ozon.ru/ozonid-lite` (bare Ozon ID form, one tap shorter than /my/main).
  `isLoggedIn()` = `__Secure-user-id` cookie present and not 0. While logged out, refreshing is off
  everywhere: `BarcodeStore.loggedIn` gates `BarcodeSync.refresh` (UI, debug, watch requests, job) and the
  main screen shows only "Log in to Ozon" (no pull-to-refresh, no orders button).
- **Watch link**: watch opens -> sends `Request` (1 = cached, 2 = force refresh) -> the Pebble app
  binds `WatchListenerService` (works with the companion closed) -> `BarcodeSync.onWatchRequest`
  answers at once (always, even without a code) and re-fetches if the saved code is stale.
- **Refresh schedule** (`RefreshSchedule.kt`, settings section "Штрихкод"), two mutually exclusive
  modes (`BarcodeStore.refreshMode`): INTERVAL — every 1 / 3 / 6 / 12 / 24 h (`refreshHours`,
  6 if unset or not in this list); DAILY (**default**) — every day at a time chosen with a
  Material `TimePicker` dialog, 24 h, default 06:00 (`dailyMinutes`). Stale =
  older than the interval / fetched before the last passed daily time (`RefreshSchedule.isStale`).
- **Background refresh**: `RefreshJobService` (JobScheduler, job id 1001, network required,
  persisted): INTERVAL = periodic job; DAILY = one-shot job at the next daily time (extra
  `target`, deadline +30 min), re-armed after each run, retry in 30 min after an error. Switch
  "Обновлять в фоне, даже если watchapp не открывали" (`backgroundRefresh`, default on).
  Scheduled from `PickItUpApp.onCreate` and on every settings change; `schedule()` skips an
  identical pending job (re-scheduling resets the period) and never throws. Needs
  ACCESS_NETWORK_STATE (a missing one crashed the app at start) and RECEIVE_BOOT_COMPLETED.
  Test with `adb shell cmd jobscheduler run -f com.grapie.pickitup 1001`.
- AppMessage keys: `Code`, `Visible`, `Updated`, `Request`, `Status`, `Timeout` — defined in both
  `watch/package.json` and `WatchKeys`; keep in sync. Status strings `Log in on phone`,
  `Updating...`, `Update failed` are shared between BarcodeSync and main.c. An empty `Code` (sent
  while logged out, e.g. right after logout) makes the watch delete its stored code. After
  "Updating..." the watch waits 90 s for the result, then shows "No answer from phone".
- LoginActivity also closes itself when `__Secure-access-token` changes while the account id
  cookie stayed (a session Ozon ended on its side keeps the id: re-login must still close it).
- **Watch screens without a stored code**: "Phone not connected" / "Checking phone..." / after 5 s
  of silence a QR to the companion (= companion not installed) / "Log in to Ozon" / "Getting
  barcode..." / "No barcode yet". With a stored code the barcode is always shown, also offline.
  Footer: "upd today HH:MM" while the code is from today, "outdated" from yesterday on (prefix
  "offline, " without the phone); a pending "Updating..." becomes "Phone offline" / "No answer from
  phone" instead of hanging.
- **Watch views** share one `Layout` (header "OZON pickup code", content, code text, footer):
  barcode and QR upright; the rotated view is the same layout on a landscape frame turned 90 deg
  (header at the button side). Pebble can't rotate text, so `draw_rotated` draws each line upright in
  a scratch strip mid-screen, copies it out of the frame buffer as 1 bit/px and writes it back
  rotated (handles 1-bit, 8-bit and round 8-bit-circular buffers). All views decode on all 7 platforms.
- **Watch buttons**: SELECT cycles barcode -> QR (test; Ozon scans Code 128) -> rotated barcode
  (order via a `next[]` table; the persisted enum values are unchanged),
  long SELECT (0.6 s) opens the companion QR any time and only BACK closes it (SELECT does nothing
  there; BACK elsewhere exits the app), UP forces a refresh, DOWN only resets the
  auto-exit timer. View mode persists. The backlight is left to the system (user's choice).
- **Auto-exit**: the watchapp closes after N s without button presses (`Timeout`, 0 = never,
  default 120 = 2 min on both sides, persisted on the watch); N is chosen in companion settings and sent with every reply.
  Off on setup/QR screens.
- **Companion UI**: main screen = barcode card (tap = full screen at max brightness), status card
  with "Обновлён <today/yesterday, HH:MM>" and the watch row (model name + `WatchStatus` text +
  dot), "My Ozon orders" (opens ozon.ru/my/orderlist in LoginActivity), pull-to-refresh, FAB
  "Обновить". Logged out (no real account id) -> onboarding screen instead. Gear -> settings:
  appearance (theme, black background, seed colours), language (system / RU / EN; the phone's
  system language is English), "Штрихкод" (refresh schedule), "Watchapp" (auto-exit),
  background-work card (only while restricted), Ozon logout (red `dangerColor()`; disabled =
  onSurface 38%, no chevron), debug screen (demo mode, state, refresh/send, journal), about (bottom sheet from a row: "Made
  with ❤️ by Grapie & Claude", source link, libraries, version). All texts in `Strings.kt` — add new ones to RU and EN.
  Push result is the `WatchStatus` enum; success reads "Получено часами". Activities are portrait-only.
- **Battery**: after the first login (never over the splash / login screen) an explanation dialog, then the system "ignore battery
  optimizations" request (`AppSettings.batteryPromptShown`). Settings show a "Работа в фоне" card
  with the explanation and an "allow" button **only while** the app is restricted; it disappears
  once allowed (the user found a status card useless). The debug screen always shows the state.
- **Terminology** (user's rule): the app on the watch is the **"watchapp"** in both languages
  ("Закрывать watchapp…", "…даже если watchapp не открывали", logs "⌚ watchapp запросил…").
  "Pebble" / "часы" stay only where the device itself is meant ("Получено часами").

- **Updates** (`Updater.kt`): `releases/latest` of the public repo over plain HttpURLConnection
  (no WebView: no Chromium), every 12 h from `MainActivity.onStart` and by "Check for updates"
  in settings. A newer tag (`v1.2` vs `versionName`, numeric compare) shows `UpdateCard` (main
  screen, onboarding, settings); "Update" downloads the first `.apk` asset to `cache/update/`,
  checks package name + higher versionCode, opens it via FileProvider (`${applicationId}.files`)
  in the system installer (REQUEST_INSTALL_PACKAGES; Android asks once to allow PickItUp!).
  So every release needs: a `vX.Y` tag, an attached `pickitup.apk` signed with the release key,
  and a bumped `versionCode` / `versionName` (+ `watch/package.json` `version`). The release
  body is shown as "what's new" (6 lines): write it as short unwrapped lines.
  Tested on the emulator: 1.0 -> found v1.1 -> downloaded -> installer -> 1.1 installed.

## Hard-won rules

- Barcode modules must be a **whole number of pixels**. Tested with zxing on 303 Ozon-like codes:
  1.2–1.4 px/module decodes 0%, 1 px and 2+ px 100%. 2 px needs ~300 px: no Pebble has that,
  so modules stay 1 px everywhere. To fill wide screens the code is made *longer* instead (as
  the "Member Card" app does): `code128_encode(text, c_pairs, ...)` caps the digit pairs in set C;
  `draw_bars` picks the fewest pairs whose code fits (round: the chord at the band edge). Time 2 and
  Round 2 get the wider code (both views), 144 px screens and Time Round the dense one. All
  budgets 0..12 + dense decode on 157 codes with zxing.
- PebbleKit2 `DefaultPebbleSender.close()` throws "Service not registered" when no Pebble app is
  installed (the bind never happened) and crashed the app on every send: always `closeQuietly`.
- Login state = a real account id (`isLoggedIn()`, cached in `BarcodeStore.loggedIn`), never "some
  Ozon cookies": an antibot / proxy page leaves guest cookies without `__Secure-user-id` (the old
  `canFetch()` then fetched in the background while the UI said "log in").
- In the hidden WebView never use `web.post`/`web.postDelayed`: a View not attached to a window
  queues them forever. Use `Handler(Looper.getMainLooper())`.
- Ozon replaces the document right after load, killing an in-flight `fetch` silently. `OzonClient`
  polls and re-sends the fetch if no answer within 10 s; don't fire once from `onPageFinished`.
- A logged-out session gets `HTTP 500 {"error":"rpc error ..."}`, not 401. JSON 5xx is final (no
  retries); `isLoggedIn()` then decides "login needed" vs a real error. Antibot blocks are HTML 403
  and do retry.
- Pebble system fonts have no Cyrillic: all watch text is English (that's fine, the user doesn't
  want Cyrillic there). Keep watch strings ASCII (no "·" etc.) and short: Pebble 2 Duo lines are
  144 px — check every new text in the `flint` emulator.
- Layout has three shapes: rect 144x168, rect "big" (width >= 200: emery) and round
  (`#ifdef PBL_ROUND`: chalk, gabbro). Round screens put content inside the circle: bands given
  in % of the height (`band()`), the barcode in the middle band (its chord must fit the code +
  quiet zones), QRs in a centred square (`centered_square()`), wider text margins. Any new screen
  needs a round variant.
- aplite has only 24 KB of app RAM (the app uses ~13 KB, mostly static QR buffers): keep new
  static buffers small and re-check the "APLITE APP MEMORY USAGE" line of `pebble build`.
- The barcode stays pure black on white in every theme; colour (Ozon blue header) only on
  `PBL_COLOR`. QR buffers are static (Pebble app stacks are small).
- Core Devices caches whether the companion is available: after `pm disable-user` / `pm enable` of
  PickItUp it keeps NACKing the watchapp ("valid companion app is not running" in logcat, the watch
  then shows the download QR) until the Core Devices app is restarted. Disabling also drops the
  background job; `PickItUpApp.onCreate` re-schedules it on the next start.
- The emulator can't simulate a phone disconnect (`emu-bt-connection` has no effect) and has no
  companion; test those paths on the real watch.

## Build, deploy, test

- Android minSdk = 31 (Android 12): the Pebble / Core Devices app needs 12+ anyway, so no
  version checks below that (Material You is always available). Release = R8 with
  `proguard-rules.pro` keeping `io.rebble.pebblekit2.**` (it ships no consumer rules).
  RAM (emulator, PSS): debug ~130 MB; release ~25-45 MB. Any `CookieManager` call or WebView
  loads Chromium into the process (+~40 MB, stays until the process dies), so the UI, the job and
  `BarcodeSync` read `BarcodeStore.loggedIn` (persisted copy of `isLoggedIn()`), refreshed only
  where a WebView runs anyway: LoginActivity (`onPause`, auto-close), after each fetch, logout.
  Don't call `OzonClient.isLoggedIn()` from screens.

```bash
# Watch: Pebble SDK lives in WSL Ubuntu-24.04 under ROOT (/root/.local/bin; the default WSL user
# has no SDK). Write the commands into a script in watch/ with
# `export PATH=/root/.local/bin:$PATH` and run: MSYS_NO_PATHCONV=1 wsl -d Ubuntu-24.04 -u root -- bash _b.sh
pebble build                                  # -> build/watch.pbw, copy to ../pickitup.pbw
# CI (.github/workflows/watchapp.yml): uv tool install pebble-tool==5.0.40 --python 3.13, then
# `pebble sdk install 4.33.1 < /dev/null` - verified from an empty HOME in WSL; bump both together
DEMO=1 pebble build                           # emulator demo: sample code "12345678*9012", no auto-exit
pebble install --emulator flint               # some platforms need SELECT x2 to open the app
pebble screenshot --emulator flint --no-open x.png
# all platforms at once (Windows side, ozvenv python): every mode must decode on every watch
python watch/test_emulators.py <watch dir> <out dir> aplite,basalt,chalk,diorite,emery,flint,gabbro demo
python watch/test_emulators.py <watch dir> <out dir> chalk,gabbro,aplite setup   # first-run QR
pebble install --cloudpebble build/watch.pbw  # real watch via Dev Connection (CloudPebble proxy)
pebble screenshot --cloudpebble --no-open x.png

# Android, from android/ (JAVA_HOME = C:/Program Files/Java/jdk-21.0.10)
./gradlew --offline assembleRelease   # what gets installed: R8 + shrinkResources; signed with the key from
                                      # keystore.properties (not in git), else the debug key
./gradlew --offline testDebugUnitTest # Code128 round trip + RefreshSchedule (also run by .github/workflows)
adb -s $PHONE install -r --user 0 app/build/outputs/apk/release/app-release.apk
./gradlew --offline assembleDebug     # only for debugging: 32 MB of unshrunk dex, ~130 MB RAM
```

- Verify every barcode/QR change by decoding screenshots with `zxing-cpp` (venv with `zxing-cpp`,
  `pillow`, `curl_cffi`, `python-barcode` at `%TEMP%/claude/ozvenv`; recreate if missing). Test UI
  changes on the real phone (adb screenshots, `input tap/swipe`) and the watch (cloudpebble).
- Companion logs: `adb logcat | grep OzonClient`; the in-app debug screen shows the journal.
- Git Bash: prefix adb/wsl with `MSYS_NO_PATHCONV=1` (else `/sdcard`, `/tmp` become Windows
  paths); inline `wsl ... bash -c '...'` loses quoting — use script files. Don't patch C with
  Python from a bash heredoc (`'\0'` became a raw NUL byte); use the Edit tool for escapes.

## Design (M3 redesign, 2026-10-16)

- Colours: `CorePalettes` builds tonal palettes from `AppSettings.seed` (6 seeds + "Wallpaper" =
  Material You on Android 12+ + CUSTOM = `customColor` picked with hue/saturation sliders); theme SYSTEM / LIGHT / DARK + `amoled` switch (old AMOLED theme
  migrates to DARK + amoled). Every role animates on change. No colour-roles / palette previews in settings (user: superfluous).
- Main screen: fixed (non-collapsing) top bar — the user called the collapsing one "parasitic scroll".
  Watch row with status OTHER_APP_OPEN is tappable: `BarcodeSync.openWatchapp` (startAppOnTheWatch).
  Status rows are an own Row (`StatusItem`), not `ListItem`: a three-line ListItem pins the
  trailing dot to the top.
- Wording (user-checked): "Обновлён"/"Updated" (not "Получен"), "Вход в Ozon" instead of
  "cookies"/"сессия", watch statuses are full phrases ("Часы не подключены", "На часах открыто
  другое приложение"), no jargon like "seed" in UI texts.
- Nav: every screen is its own activity (Main / Settings / Debug / Login) so back and the predictive
  back gesture are the system's cross-activity animation (a hand-made Compose one was rejected); `enableOnBackInvokedCallback`;
  LoginActivity is Compose (top bar = page <title> minus the OZON suffix, host under it; login mode
  keeps "Ozon login" + bottom "closes by itself" hint), its back callback is enabled only while the
  WebView has history. Main-frame load errors show our own placeholder (offline / error + retry),
  auto-retried when the network comes back. Kinds: OFFLINE / NETWORK (timeouts, QUIC, resets ->
  "Ozon не отвечает") / OTHER (raw code). The placeholder stays during a reload (spinner instead of
  the button) and is removed only in `onPageCommitVisible` - `onPageFinished` also fires for the old
  error page and let WebView's grey page show through. Offline is testable on the emulator
  (`adb shell svc wifi disable; svc data disable`), not over Wi-Fi adb.
  Non-http(s) links (ozon://, intent://; "Войти через приложение") go to other apps via
  `openExternally` (intent:// fallback URL, else a toast); the page then sees the approval itself.
- Watch model: `Watches` reads PebbleKit2 `getConnectedWatches()` (name/platform/revision), maps
  codenames to names ("flint" -> "Pebble 2 Duo"), remembers the last watch. Verified on the phone.
- Demo mode (debug screen, `BarcodeStore.demo`, persisted): phone and watch show `shown` =
  "12345678*9012" / "1234 5678 * 9012" instead of the real code, refresh is fake (no Ozon call),
  the orders button only shows a toast. The real code stays saved and comes back when it's off.
- Main screen row "Обновлён" / "Updated" (not "Получен"): today/yesterday + time, older = date + time (`Strings.receivedWhen`).

## Privacy

The WebView cookies hold a live Ozon session, and the barcode lets anyone pick up the user's
orders. Don't print full codes/tokens in logs or output. Traffic captures (`*.har`, gitignored)
contain session tokens too; the original research captures were removed once the API was known.

## Open items

- Watchapp updates: none needed in the app - the Pebble app updates it from the Pebble App
  Store / Rebble. Real-life use (pickup point scan etc.) was tested by the user.
- Other marketplaces: needs research first (WB has one personal code like Ozon; Avito /
  AliExpress / CDEK have per-parcel codes - would need a provider interface and a list of codes
  on the watch).
- License: MIT (`LICENSE`, `README.md` with the "unofficial, not affiliated with Ozon" disclaimer).

# AppLock — `com.sabid.applock`

An offline app locker for Android. Native Kotlin + Jetpack Compose, no account, no ads, no
analytics, no server. A documented Screen Time scaffold for iOS lives under [ios/](ios/).

- **minSdk 26 · targetSdk 35 · compileSdk 35 · JDK 17 · AGP 8.5.2 / Kotlin 1.9.24**
- Everything the app knows stays in its own private storage. The only code in the project
  that opens a socket is the optional built-in incognito browser, loading pages you type.

---

## Build

The Gradle wrapper is committed, so a clone builds directly. The default JDK on many
machines is 21; this project wants 17.

```bash
export JAVA_HOME=/path/to/jdk-17        # AGP 8.5.2 / Kotlin 1.9.24 need 17, not 21
./gradlew assembleDebug                 # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug                  # onto a connected device
./gradlew test                          # unit tests (6 suites, no device needed)
python3 check.py                        # fast static wiring check — see docs/VALIDATION.md
```

> **First run needs three manual grants** — accessibility, display-over-other-apps and
> usage access. Onboarding walks through them, and [PermissionGate](app/src/main/java/com/sabid/applock/ui/permissions/PermissionGate.kt)
> re-asks on **every** app open for anything still missing. Without accessibility, nothing
> locks: the app says so instead of pretending.

### Release build

Release signing is read from `keystore.properties` at the repo root, which is gitignored.
Copy [keystore.properties.template](keystore.properties.template), create the key with the
`keytool` line in it, and point `storeFile` at the result:

```bash
./gradlew bundleRelease                 # app/build/outputs/bundle/release/app-release.aab
```

With no `keystore.properties` present the build still succeeds and produces an **unsigned**
bundle — useful for checking that R8 and resource shrinking have not broken anything, but
Play will not accept it. Release builds run with `isMinifyEnabled` and `isShrinkResources`.

> The upload key is the one file in this project that cannot be regenerated. Lose it and the
> app can never be updated under the same Play listing again. Back up the `.jks` and its
> passwords somewhere off this machine, and keep both out of any repo you push publicly.

---

## What it does

**Locking**

- Lock any launchable app behind a PIN or a pattern, with optional fingerprint/face unlock.
- Four relock policies: immediately on leaving, on screen off, after a brief exit
  (configurable minutes), or until the device reboots.
- Random keypad layout and invisible pattern, so a shoulder-surfer learns nothing from
  watching your thumb.
- Time rules — locked only during chosen hours/days, evaluated including the wrap-past-
  midnight case ([LockSchedule](app/src/main/java/com/sabid/applock/core/data/LockSchedule.kt)).
- Auto-lock newly installed apps.
- Lock the phone's own Settings and package installer, which is what makes uninstall
  protection real rather than decorative.

**Hiding**

- **Nothing readable in the app switcher.** A locked app is emptied out of the recents list
  when a challenge is declined, *and* when you unlock it and later leave it. See
  [Recents protection](#recents-protection-the-hard-part).
- `FLAG_SECURE` on the lock screen, vault, intruder gallery and settings: no screenshots, no
  thumbnail.
- Encrypted vault for photos, videos, audio and files — AES-256-GCM with a key that never
  leaves the Android Keystore, so copying the app's data off the device yields nothing.
  Imported files can be deleted from their original location; thumbnails are encrypted too.
- Incognito browser: no history, no cache, no cookies at exit, no form data.

**Catching**

- Intruder selfies from the front camera after N wrong attempts (default 2), and again on
  *every* attempt after that, so a long guessing session is a record rather than one frame.
- Captures live in internal storage, so no gallery app can index them, and are swept after
  30 days by default. Any single photo can be marked keep-forever.
- Exponential lockout back-off after repeated failures, capped so the real owner is never
  permanently locked out.
- Optional panic action after N wrong attempts: revokes all unlock grants and purges the
  vault's decrypted cache. It does **not** destroy user data.

**Staying alive**

- Foreground service + `START_STICKY` + boot receiver, and a usage-stats poller as a
  backstop for ROMs that kill accessibility services.
- Deep links into the vendor autostart / background-launch pages that actually govern this
  on Xiaomi, Oppo, Vivo, Huawei and friends ([OemSettings](app/src/main/java/com/sabid/applock/core/util/OemSettings.kt)).
- When the chain breaks anyway, [ProtectionAlert](app/src/main/java/com/sabid/applock/service/ProtectionAlert.kt)
  says so plainly instead of leaving a notification up claiming everything is fine.

**Elsewhere**

- Home screen widget and Quick Settings tile for one-tap protection on/off.
- Five themes, PIN length 4–8, haptics.
- The app locks itself ([LockGate](app/src/main/java/com/sabid/applock/ui/lock/LockGate.kt)),
  and its own launcher icon is deliberately not disguisable.

---

## Architecture

```
AppLockAccessibilityService ──┐
   (window state changed)     ├──►  LockEngine  ──►  LockRepository.decide()
UsageWatchdog ────────────────┘   (one decision       (locked set + time rules
   (polling backstop)              point)              + in-memory session)
                                        │
                        ┌───────────────┴───────────────┐
                        ▼                               ▼
                  LockOverlay                     LockPresenter
            (TYPE_APPLICATION_OVERLAY,          (LockActivity — hosts
             cannot be suppressed,               BiometricPrompt, but a
             can empty the task)                 background start can be
                        │                        silently refused)
                        ▼
                   TaskShield  ──►  ScreenCover
              (empties the app's task so recents keeps no picture)
```

**Why accessibility, not polling.** `UsageStatsManager` polling costs a wake-up every few
hundred milliseconds and still lets the protected UI paint before you can cover it.
`TYPE_WINDOW_STATE_CHANGED` fires as the window is created. The service declares
`canRetrieveWindowContent="false"`, so it reads *only* the package name of the window that
came to the front — it structurally cannot read the text on your screen. `UsageWatchdog` is
a slow backstop for ROMs that kill the service, not the main path; both feed
[LockEngine](app/src/main/java/com/sabid/applock/service/LockEngine.kt) so the two can never
drift apart.

**Two lock screens, and why.** An activity is the nicer host — `BiometricPrompt` needs a
`FragmentActivity`, the system manages its lifetime — but starting one from the background is
refused outright on many ROMs, and refused *silently*: `startActivity` reports success and no
window appears. An overlay needs no background-start exemption and so cannot be suppressed,
but it cannot host biometrics, and it is the only route that can empty the protected app's
task. So the overlay is the default (recents protection depends on it), the activity is
offered when the user asks for biometrics, and a refused activity start is learned once and
remembered rather than re-paid on every launch.

**Session state is never persisted.** [LockSession](app/src/main/java/com/sabid/applock/service/LockSession.kt)
lives in memory only, so a reboot, a process death or a killed service re-locks everything.
Failing open is not an option a lock app has.

---

## Recents protection, the hard part

The app switcher does not render a live view of an app — it renders a snapshot that
`system_server` captured. `FLAG_SECURE` only covers our own windows, and `REMOVE_TASKS` is
signature-level, so a normal app has exactly one lever over another app's recents card:
**empty its task**, by pressing back at it until nothing is left. Killing the process does
not help; the snapshot has already been taken.

That produces two cases, both handled in
[TaskShield](app/src/main/java/com/sabid/applock/service/TaskShield.kt):

1. **Challenge declined.** The overlay goes up *without focus* first, so injected back
   presses reach the app underneath rather than our own window. The task is emptied before
   the system ever snapshots it. Nothing visible happens.
2. **Unlocked, used, then left.** The snapshot exists the instant you press home, so the only
   way to remove it is to bring the app forward for a moment — behind an opaque
   `FLAG_SECURE` [ScreenCover](app/src/main/java/com/sabid/applock/service/ScreenCover.kt) —
   and press back at it there. The screen goes black for a few hundred milliseconds. This is
   `closeLockedOnLeave`, on by default, and switchable off for anyone who would rather keep
   their place in the app.

Two things in that second path are load-bearing and easy to get wrong:

- **The cover has to have painted before the app is fronted.** Android's background-activity-
  launch check treats a window as an exemption only once it is visible; front the app before
  the cover has drawn and the launch is refused with `result code=102` while the app's
  contents flash on screen. `ScreenCover.awaitDrawn()` exists for exactly this.
- **"The user left" has to be confirmed, not assumed.** MIUI raises a launcher/SystemUI
  window in the middle of a launch transition, which looks identical to a departure. Without
  the re-check against `UsageStatsManager`, AppLock would close an app half a second after
  the owner opened it.

Where the ROM refuses the re-front anyway, the shield gives up after three failures and tells
the user their switcher may still show locked apps, rather than silently doing nothing.

---

## Uninstall protection, honestly

A lock anyone can uninstall protects nothing, so AppLock registers a device admin with no
policies attached — Android's one legitimate lever for "this package cannot be uninstalled"
([UninstallGuard](app/src/main/java/com/sabid/applock/core/util/UninstallGuard.kt)). Three
things are worth saying out loud, because an oversold security feature is worse than an
absent one:

1. **Deactivation is a Settings page.** Anyone who reaches Settings → Security → Device admin
   apps can switch it off and uninstall normally. Turning on *Lock system pages*
   (`guardSystemPages`, off by default) is what closes that, and the two are meant to be used
   together.
2. **`adb uninstall` and safe mode bypass it.** Nothing an app can do reaches either.
3. **A factory reset always wins.** By design.

There is no stored "protected" flag; `DevicePolicyManager.isAdminActive` is the only truth,
because the user can change it from outside the app at any time and a cached copy would
eventually lie to them.

---

## Permissions, and what each is for

| Permission | Why |
|---|---|
| `BIND_ACCESSIBILITY_SERVICE` | Detect which app came to the front. Package name only — `canRetrieveWindowContent="false"`. |
| `SYSTEM_ALERT_WINDOW` | Draw the lock screen over the app being locked, and the opaque cover used by recents protection. |
| `PACKAGE_USAGE_STATS` | Backstop detector, and the authoritative "is the user still in this app?" check. |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | Keep detection alive. The `specialUse` subtype is declared in the manifest. |
| `RECEIVE_BOOT_COMPLETED` | Protection has to be on after a reboot without the user opening the app. |
| `POST_NOTIFICATIONS` | The ongoing status notification, and the honest warning when protection has broken. |
| `KILL_BACKGROUND_PROCESSES` | Evict a declined app rather than leave it running behind the lock screen. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Ask to be exempted from the killing that stops locks from working. |
| `USE_BIOMETRIC` / `USE_FINGERPRINT` | Optional fingerprint/face unlock. |
| `CAMERA` | Intruder selfie only, only after a wrong attempt, only if enabled. |
| `VIBRATE` | Keypad haptics. |
| `INTERNET` | The optional incognito browser, and nothing else. |
| `BIND_DEVICE_ADMIN` | Uninstall protection. No device policies are applied. |

**No `QUERY_ALL_PACKAGES`.** It is a Play-restricted permission granted to a short list of
app categories that an app locker is not on, and nothing here needs it: every
`PackageManager` call is either `queryIntentActivities(MAIN/LAUNCHER)`, a launch-intent
lookup, or a `resolveActivity` against a fixed list of vendor settings pages. All three are
covered by the explicit `<queries>` block in the manifest.

**No storage or media permissions.** Vault import goes through `ACTION_OPEN_DOCUMENT`, so the
system picker hands over exactly the files the user chose.

---

## Data and privacy

- The PIN/pattern is never stored. What is stored is PBKDF2-HMAC-SHA256 (120k iterations)
  over a per-install salt; the recovery answer is hashed the same way.
- Vault files and their thumbnails are AES-256-GCM, key held in the Android Keystore (TEE /
  StrongBox where available), never exported.
- Intruder photos, the vault, the locked-app list and all settings live in the app's private
  storage. `allowBackup="false"` and a restrictive `data_extraction_rules.xml` keep them out
  of cloud backup and device-to-device transfer.
- No ad SDK, no analytics, no crash reporter, no account, no server. There is nothing in the
  build that could send your data anywhere, which is why the Data Safety answer is "no data
  collected".

User-facing wording lives in [PRIVACY_POLICY.md](PRIVACY_POLICY.md).

---

## Project layout

```
app/src/main/java/com/sabid/applock/
  core/crypto/     PinCrypto (PBKDF2), VaultCrypto (AES-GCM + Keystore)
  core/data/       Room DB, DAOs, LockRepository (the decide() authority),
                   LockSchedule (time rules), SettingsStore (DataStore)
  core/util/       AppInfoLoader, Permissions, ProtectionStatus, UninstallGuard,
                   VaultManager, IntruderPhotos, OemSettings, LauncherIcon
  service/         AppLockAccessibilityService, UsageWatchdog  — detection
                   LockEngine                                  — decision
                   LockOverlay, LockPresenter, ScreenCover      — presentation
                   TaskShield                                  — recents
                   LockGuardService, BootReceiver, PackageInstallReceiver,
                   ProtectionAlert, LockSession, FailedAttempt, IntruderCamera,
                   LockWidgetProvider, QuickLockTileService, AppLockDeviceAdmin
  ui/              Compose screens: home (app list + filters), vault, intruder,
                   settings, schedule, browser, onboarding, permissions, lock
```

Tests: `app/src/test/` covers the schedule wrap-past-midnight branch, protection-status
ordering, uninstall-guard matching, vault filename sanitising, presenter state and session
expiry. `check.py` is a homegrown static wiring check (imports, brace balance, `R.*`
resolution, manifest classes) — a lint, not a compile.

---

## Shipping to Google Play

The build side is done: signing config wired, `bundleRelease` produces an AAB, targetSdk 35
meets the current requirement, `versionCode` starts at 1. What remains is Play Console work,
and for this app it is mostly declarations:

1. **Accessibility API** — needs a prominent in-app disclosure (onboarding has one) and a
   declaration explaining what the service reads. The `accessibility_description` string is
   written to be reusable verbatim, and `canRetrieveWindowContent="false"` is the strongest
   thing you can say here.
2. **Device admin / uninstall protection** — expect scrutiny, and answer it with the two
   facts that matter: no device policies are applied, and the user can deactivate it from
   Settings at any time. It is off unless the user turns it on.
3. **`FOREGROUND_SERVICE_SPECIAL_USE`** — justify with the subtype already in the manifest:
   the service exists to keep on-device app locking active.
4. **Data Safety** — "no data collected, no data shared", which is unusually easy to fill in
   truthfully here.
5. **Privacy policy URL** — host [PRIVACY_POLICY.md](PRIVACY_POLICY.md) somewhere public and
   link it.
6. A new personal developer account also needs closed testing (12 testers, 14 days) before
   production access opens up.

One listing note: do not market this as a way to hide activity from parents or partners. That
framing draws policy review and undersells what is a straightforward privacy utility.

---

## Not built

Honest list of what the settings screen does not yet cover, or what was dropped:

- **Icon disguise** — removed deliberately. `LauncherIcon.ensureVisible()` actively keeps the
  real icon in place.
- **Vault cloud sync** — dropped by design; there is no server.
- **Profiles** (different locked-app groups) — no storage, no UI.
- **Custom lock-screen background** — no picker.
- **Cover for a force-stopped app** — not built.
- **iOS** — [ios/](ios/) is a Screen Time / `ManagedSettings` scaffold with notes, not a
  shipping app. iOS has no equivalent of the detection model above; `ShieldConfiguration` is
  as far as the platform allows.

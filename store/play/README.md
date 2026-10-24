# Play Store assets

Everything here is generated. Edit the sources in [src/](src/) and re-run:

```sh
./src/render.sh
```

Headless Chrome is the renderer (no image toolchain needed). `CHROME=/path/to/chrome` overrides
the browser path.

## What goes in which Play Console slot

| Console field | File(s) | Size |
| --- | --- | --- |
| App icon | `icon-512.png` | 512 × 512 |
| Feature graphic | `feature-graphic-1024x500.png` | 1024 × 500 |
| Phone screenshots | `phone/01…06` | 1080 × 1920 (9:16) |
| 7-inch tablet screenshots | `phone/01…06` (same files) | 1080 × 1920 |
| 10-inch tablet screenshots | `tablet-10/01…06` | 1440 × 2560 |

Six screenshots clears the 2–8 range and the "at least 4 screenshots at a minimum of 1080 px on
each side" bar for promotional eligibility. The 10-inch slot requires each side ≥ 1080 px, which
is why it gets its own pass at a 1.3333 device scale factor rather than reusing the phone files.

Upload order matters — the console shows them in the order listed, and `01` → `06` is a
deliberate narrative: the lock itself, then locking apps, then tamper protection, intruders, the
vault, and settings.

## The screenshots are recreations, not device captures

Every sensitive screen in the app sets `FLAG_SECURE`, so `adb shell screencap` returns black
frames — and a real capture would also publish the developer's own locked-app list, vault
contents and intruder photos. So each screenshot is rebuilt from the Compose sources instead:

- colours from `ui/theme/Theme.kt` (MIDNIGHT, the default palette)
- type scale from `ui/theme/Type.kt`
- padding, radii and sizes copied dp-for-dp from the composable being depicted
- all UI strings verbatim from the Kotlin

`shots/shared.css` authors the phone viewport at 411 × 657 **dp** and then applies `zoom`, so the
numbers in each screen file can be diffed against the Kotlin line for line.

Two deliberate departures from the real UI:

- **App names and icons are generic** ("Gallery", "Messages", "Wallet", "Settings", "Browser")
  with hand-drawn glyphs, because shipping real third-party launcher icons in a store listing is
  a trademark problem.
- **Intruder captures are anonymous silhouettes.** Publishing an invented photo of a person as if
  it were a real intruder capture would be a fabrication.

Before publishing, check each screenshot against the app running on a device. If a screen has
drifted from its source, fix the HTML and re-render — or drop `FLAG_SECURE` in a debug build and
replace these with genuine captures from a test device holding test data.

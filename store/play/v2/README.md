# Play Store listing, v2 (liquid-glass redesign)

Text and graphics for the redesigned app. Everything image-shaped is generated: edit
[src/gen.py](src/gen.py) (screens) or [src/feature.html](src/feature.html) /
[src/icon.html](src/icon.html), then run:

```sh
./src/render.sh
```

Headless Chrome renders the PNGs (`CHROME=/path/to/chrome` overrides the browser path).
`render.sh` regenerates the screen HTML from `gen.py` first.

## What goes in which Play Console slot

| Console field | File(s) | Size |
| --- | --- | --- |
| Short description | `short_description.txt` | 74 / 80 characters |
| Full description | `full_description.txt` | 3,309 / 4,000 characters |
| App icon | `icon-512.png` | 512 × 512 (same shield as the installed app icon) |
| Feature graphic | `feature-graphic-1024x500.png` | 1024 × 500 |
| Phone screenshots | `phone/01…08` | 1080 × 1920 |
| 7-inch tablet screenshots | `tablet-7/01…08` | 1080 × 1920 (9:16) |
| 10-inch tablet screenshots | `tablet-10/01…08` | 1440 × 2560 |
| Promo video | `video/applock-how-to-use-1920x1080.mp4` | 1920 × 1080, 66 s, 30 fps, no audio |
| Video thumbnail | `video/thumbnail-1280x720.png` | 1280 × 720 (YouTube custom thumbnail) |

Upload `01` → `08` in order: lock screen, apps dashboard, timed unlock, settings, intruders,
lock history, vault, pattern lock.

## The screenshots are recreations, not device captures

Same approach and same reasons as v1: the app sets `FLAG_SECURE` on release builds (so a capture
is black) and a real capture would publish a real locked-app list, vault contents and intruder
photos. Colours, spacing and every UI string are taken from the Kotlin sources.

- App names and glyphs are generic ("Gallery", "Messages", "Wallet", "Browser").
- Intruder captures are anonymous silhouettes, not photos of a person.

**Check before publishing:** compare each screenshot with the app running on a device and fix
`gen.py` if a screen has drifted. The description's permission section must also stay in line
with what the app actually requests and with your Data safety form and accessibility declaration.

## How-to video and thumbnail

`video/applock-how-to-use-1920x1080.mp4` is a 66-second walkthrough, not a slideshow of the
listing images. Each step shows the screen, a caption on the left, and an animated finger
(tap, press-and-hold, swipe) on the control to use:

1. Allow the permissions (each one asks for your agreement first)
2. Pick the apps to lock
3. Create your PIN
4. Open a locked app and unlock it
5. Unlock for a while (press and hold, choose 30 minutes)
6. Save a profile
7. See who tried to get in (swipe through intruder photos)
8. A closing look at the settings

`video/thumbnail-1280x720.png` is the matching YouTube thumbnail.

```sh
./src/render.sh          # refresh the screenshots and shared components first
./src/render_video.sh    # builds the video and the thumbnail
```

No ffmpeg is needed: Chrome renders each screen state, it locates every tapped control itself so
the finger lands on the real element, and Swift/AVFoundation encodes the MP4. Edit the steps,
captions and timings in [src/video/howto.py](src/video/howto.py).

Play does not accept a video upload. The **Promo video** field takes a **YouTube URL**, so upload
the MP4 to YouTube (public or unlisted, not age-restricted, monetisation off), set the PNG as its
custom thumbnail, and paste the link into Store listing → Graphics. The video has no sound. It is
built from the same recreated screens as the screenshots, so check it against the running app
before you publish.

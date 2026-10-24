# Play submission checklist — AccessibilityService policy

Three rejections so far, and only the first was purely about code:

| Version | Outcome | Cause |
|---|---|---|
| 1 | Rejected | Declared `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` |
| 5 | Rejected | Prominent disclosure present, but only reachable by tapping a permission row |
| 6 | — | Disclosure is now a step of first run; listing + video still needed |

Version 5 **did** ship a disclosure with two buttons, non-dismissible, shown before the
request. It was still rejected, which points at the two requirements below that a code
change cannot satisfy on its own. Do not resubmit without both.

## 1. Store listing must document the Accessibility API use

The policy email: *"The use of the Accessibility API must be documented in the Play Store
listing."* Paste this into the full description:

> **Why AppLock uses Accessibility Services**
>
> AppLock uses Android's Accessibility Service to detect the moment a protected app is
> opened, so it can show your lock screen in front of it. This is the only API Android
> provides for that.
>
> The service reads one thing: the package name of the app currently in the foreground
> (for example, "com.whatsapp"). It is configured without window-content access, so it
> cannot read the contents of your screen, your messages, or anything you type.
>
> Nothing is recorded, shared, sold, or transmitted off your device. AppLock has no
> account system and no server. You can turn the service off at any time in
> Settings > Accessibility, and AppLock will keep working using a slower backup method.

## 2. Demo video link (required on every submission)

The email: *"Please remember to include in the Play Console, a link to an updated video
showcasing the core functionality feature that uses the AccessibilityService API with
each new app submission."*

Upload an unlisted YouTube video and paste the link into Play Console under
**App content > Accessibility (or the Sensitive app permissions declaration)**. It should
show, in one unbroken take:

1. First launch of the app, arriving at the disclosure screen.
2. The disclosure being read and **"I agree"** tapped.
3. Android's Accessibility settings opening, and "AppLock protection" being switched on.
4. Returning to AppLock, locking an app.
5. Opening that app and being challenged by the lock screen.

Step 5 is the point of the video: it demonstrates that the Accessibility grant is used for
the core advertised feature and nothing else.

## 3. What the app now does (for the reviewer's benefit)

The disclosure is at `app/src/main/java/com/sabid/applock/ui/permissions/AccessibilityDisclosure.kt`
and is reached two ways:

- **First run**: Welcome → **disclosure screen** → permissions. No optional tap required,
  which is the policy's *"displayed in the normal usage of the app and not require the user
  to navigate into a menu or settings"*. "I agree" opens the Accessibility page directly, so
  the request immediately follows the disclosure.
- **Later requests** (Settings row, protection banner, permission gate): the same disclosure
  as a dialog that cannot be dismissed by back-press or an outside tap, so navigating away
  is never read as consent.

Both surfaces share one copy of the wording and offer two equally weighted buttons,
"No thanks" and "I agree", with decline first.

`check.py` rule 8 fails the build if any file outside the disclosure opens the Accessibility
settings page directly, so an undisclosed shortcut cannot creep back in.

## 4. Before uploading

- [ ] `versionCode` is higher than 5 (currently 6) — Play will not reuse a consumed code.
- [ ] Signed bundle built (Android Studio *Generate Signed Bundle*, or create
      `keystore.properties` from the template and run `./gradlew bundleRelease`).
- [ ] Store listing updated with section 1.
- [ ] Demo video uploaded and linked in Play Console.
- [ ] `IsAccessibilityTool` is **not** set — AppLock is not an accessibility tool, so it must
      meet prominent disclosure instead. Confirmed absent from
      `res/xml/accessibility_service_config.xml`.

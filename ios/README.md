# iOS: what is actually possible

Read this before promising anyone an iOS AppLock.

## The hard limit

iOS has no API that lets a third-party app:

- learn which app is in the foreground,
- draw a window above another app,
- or run continuously in the background to watch for app launches.

These are not gaps Apple has left open. They are the sandbox working as designed.
Any "AppLock for iOS" on the App Store is doing one of three things: locking only its
own in-app vault, using a Screen Time shield (below), or it is an MDM profile aimed at
managed devices.

## The one sanctioned path: Screen Time / FamilyControls

Since iOS 16, `FamilyControls` + `ManagedSettings` + `DeviceActivity` let an app place a
**shield** over other apps. This is what Opal, Jomo and one sec use. Constraints you
must design around:

| Constraint | Consequence |
|---|---|
| Requires the **Family Controls** distribution entitlement, requested from Apple by form | Cannot ship without approval; expect weeks, and a rejection if your use case reads as circumvention |
| App identities arrive as opaque `ApplicationToken`s | You cannot read bundle IDs, show your own app names, or build a searchable list |
| The user picks apps in Apple's `FamilyActivityPicker` | You cannot build your own picker UI |
| The shield UI is a fixed card: icon, title, subtitle, two buttons | **No PIN pad inside the shield.** Unlock means: shield button → open your app → authenticate → lift the shield |
| Shield extensions have tight memory limits and no network | Keep the extension logic trivial |

So the honest iOS product is: *shield the app, tap "Unlock", authenticate with Face ID
in the host app, shield lifts for N minutes.* That is a good product. It is not the
Android behaviour, and the marketing should not pretend otherwise.

## What is in this folder

Swift scaffolding for that design — authorization, picker, shield store, and the two
app extensions. It is a starting point to drop into an Xcode project, not a buildable
workspace (Xcode project files are generated, not hand-authored).

## Setup checklist

1. Xcode → new iOS App target, bundle id `com.sabid.applock`.
2. Signing & Capabilities → add **Family Controls**.
3. Request the distribution entitlement: https://developer.apple.com/contact/request/family-controls-distribution
4. Add two extension targets: **Shield Configuration** and **Shield Action**.
5. Add an App Group (e.g. `group.com.sabid.applock`) shared by all three targets — the
   extensions cannot reach your main app's storage without it.
6. The vault, PIN hashing and Face ID parts of the app have no such restrictions and can
   be built normally with CryptoKit + LocalAuthentication + Keychain.

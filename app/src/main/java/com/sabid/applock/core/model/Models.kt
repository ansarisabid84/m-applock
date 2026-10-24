package com.sabid.applock.core.model

/**
 * How the user proves identity at the lock screen.
 *
 * There was a PASSWORD variant here that nothing could ever produce — no setup flow
 * offered it — and the lock screen still drew a numeric keypad for it. Removing it means
 * the compiler now proves both branches are handled everywhere.
 */
enum class LockMethod { PIN, PATTERN }

/** When a temporarily-unlocked app becomes locked again. */
enum class RelockPolicy {
    /** Re-lock the moment the user leaves the app. Strictest. */
    IMMEDIATELY,

    /** Stay unlocked until the screen turns off. */
    SCREEN_OFF,

    /** Stay unlocked for [Settings.briefExitMinutes] after leaving. */
    TIMED,

    /** Stay unlocked until the device reboots. Loosest. */
    RESTART
}

enum class ThemeSkin { MIDNIGHT, BRASS, MOSS, SLATE, INK_ROSE }

/**
 * How the intruder gallery lays its photos out.
 *
 * Ordered loosest-to-densest, which is what lets a pinch gesture step through them: pinching
 * out moves towards [LIST], pinching in towards [GRID_SMALL]. Persisted by name.
 */
enum class IntruderViewMode {
    /** One row per capture, with the app name and time beside a small thumbnail. */
    LIST,

    /** Two columns of large tiles. */
    GRID_LARGE,

    /** Three columns of small tiles. */
    GRID_SMALL;

    val columns: Int get() = if (this == GRID_SMALL) 3 else 2

    /** The next mode when pinching in (towards more, smaller items). */
    fun denser(): IntruderViewMode = entries.getOrElse(ordinal + 1) { this }

    /** The next mode when pinching out (towards fewer, larger items). */
    fun sparser(): IntruderViewMode = entries.getOrElse(ordinal - 1) { this }
}

/** A single item stored in the encrypted vault. */
enum class VaultKind { PHOTO, VIDEO, AUDIO, FILE }

data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val isLocked: Boolean
)

/**
 * Why a package is being challenged.
 *
 * An enum rather than the free-text string this used to be, because the two cases are not
 * interchangeable and the engine has to tell them apart: a [TAMPER_GUARD] challenge is
 * skipped when AppLock cannot present a lock screen, so that guarding Settings can never
 * lock the user out of the page where the missing permission is granted. A [PROTECTED]
 * challenge is never skipped for any reason.
 */
enum class ChallengeReason {
    /** The user put this app on their locked list. */
    PROTECTED,

    /** AppLock defending itself: Settings, the package installer, the vendor security app. */
    TAMPER_GUARD
}

/** Result of asking the engine whether a package may be shown. */
sealed interface LockDecision {
    data object Allow : LockDecision
    data class Challenge(val packageName: String, val reason: ChallengeReason) : LockDecision
}

package com.sabid.applock.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the one piece of [LockPresenter] that does not need a Context: the record of
 * whether a lock screen ever actually reached the screen.
 *
 * It is worth a test because getting it wrong is invisible and expensive. `wasShown`
 * returning false is [LockEngine]'s signal that the activity route failed, and acting on
 * that signal means bouncing the user out of the app they just opened.
 */
class LockPresenterTest {

    @Before
    fun reset() {
        LockPresenter.dismiss()
    }

    @Test
    fun `confirmShown records the package`() {
        LockPresenter.confirmShown("com.example.gallery")
        assertTrue(LockPresenter.wasShown("com.example.gallery"))
    }

    @Test
    fun `wasShown does not leak across packages`() {
        LockPresenter.confirmShown("com.example.gallery")
        assertFalse(LockPresenter.wasShown("com.example.bank"))
    }

    @Test
    fun `dismiss does not forget that a lock screen was shown`() {
        // dismiss() runs from LockActivity.onPause. Clearing the record there made a lock
        // screen that appeared and was then navigated away from indistinguishable from one
        // that never appeared at all — and the latter is what triggers containment.
        LockPresenter.confirmShown("com.example.gallery")
        LockPresenter.dismiss()
        assertTrue(LockPresenter.wasShown("com.example.gallery"))
    }

    @Test
    fun `dismiss clears the in-flight challenge`() {
        LockSession.challenging = "com.example.gallery"
        LockPresenter.dismiss()
        assertFalse(LockSession.challenging == "com.example.gallery")
    }
}

package com.sabid.applock.service

import com.sabid.applock.AppLockApp
import kotlinx.coroutines.launch

/** Fire-and-forget writer for the lock history. Never blocks or fails the unlock path. */
object LockHistory {
    fun record(kind: String, pkg: String) {
        val app = AppLockApp.get()
        if (pkg == app.packageName) return
        app.appScope.launch { runCatching { app.repo.recordEvent(pkg, kind) } }
    }
}

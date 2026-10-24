package com.sabid.applock.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.sabid.applock.AppLockApp
import com.sabid.applock.core.data.IntruderLog
import com.sabid.applock.core.util.IntruderPhotos
import kotlinx.coroutines.launch
import java.io.File

/**
 * Silently captures a front-camera frame after repeated failed unlock attempts,
 * so the phone's owner can see who was trying to get into their apps.
 *
 * The photo is stored as an ordinary JPEG in app-private storage, which no other app can read
 * and the media scanner never indexes — so it never turns up in the gallery. It is never
 * uploaded, and reaching it through AppLock still means getting past the lock screen.
 *
 * Every abort path logs. This feature fails silently by nature — nobody notices a photo
 * that was never taken until they go looking for evidence that isn't there — so "why was
 * there no photo?" has to be answerable from a log rather than by reasoning about the code.
 */
object IntruderCamera {

    /**
     * Guards against a second capture starting while the first is still open.
     *
     * Needed since captures repeat on *every* failed attempt rather than firing once:
     * `bindToLifecycle` after `unbindAll` would tear the in-flight session down mid-shot, and
     * two overlapping requests on a single front camera reliably produce one error and no
     * photo — the exact moment we most want a photo.
     */
    @Volatile
    private var capturingSince = 0L

    /**
     * After this long, an in-flight capture is assumed dead and a new one may start.
     *
     * A plain boolean latch was a one-way door: if the camera opened and then neither
     * callback ever arrived — the process losing the camera to another app is enough — the
     * latch stayed set and intruder photos were silently off for the rest of the process
     * lifetime. Being time-based means the worst case is one missed frame, not a dead feature.
     */
    private const val STALE_MS = 12_000L

    /**
     * The lifecycle CameraX binds to.
     *
     * Deliberately ours rather than the lock screen's. The capture is triggered *by* a failed
     * attempt, and the very next thing an intruder does is close the lock screen — which
     * destroyed the host lifecycle, unbound the camera and cancelled the shot before the JPEG
     * was written. The photo we most wanted was the one we always lost. Owning the lifecycle
     * means the shot outlives the challenge that provoked it.
     *
     * Only ever touched on the main thread; [LifecycleRegistry] requires that.
     */
    private val cameraLifecycle = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    fun capture(context: Context, targetPackage: String, failedAttempts: Int) {
        val busyFor = SystemClock.elapsedRealtime() - capturingSince
        if (capturingSince != 0L && busyFor < STALE_MS) {
            LockLog.d("intruder capture skipped, one in flight ${busyFor}ms")
            return
        }
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            LockLog.w("intruder capture skipped: camera permission not granted")
            return
        }
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)) {
            LockLog.w("intruder capture skipped: no front camera on this device")
            return
        }

        capturingSince = SystemClock.elapsedRealtime()
        LockLog.d("intruder capture starting ($targetPackage, attempt $failedAttempts)")

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = runCatching { providerFuture.get() }
                .onFailure { LockLog.w("no camera provider: ${it.message}") }
                .getOrNull()
            if (provider == null) {
                capturingSince = 0L
                return@addListener
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val bound = runCatching {
                provider.unbindAll()
                // Reset to RESUMED for every shot: the previous capture left this DESTROYED,
                // and CameraX refuses to bind to a destroyed lifecycle.
                cameraLifecycle.registry.currentState = Lifecycle.State.RESUMED
                provider.bindToLifecycle(
                    cameraLifecycle,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    capture
                )
            }.onFailure { LockLog.w("could not bind front camera: ${it.message}") }.isSuccess
            if (!bound) {
                release(provider)
                return@addListener
            }

            // Straight into app-private storage under its final name, as the plain JPEG the
            // camera produced. Nothing re-encodes it and nothing copies it. `filesDir` is not
            // part of MediaStore, so no gallery app can ever list or index what lands here —
            // see IntruderPhotos.
            val name = IntruderPhotos.photoName(System.currentTimeMillis())
            val target = File(IntruderPhotos.dir(context).apply { mkdirs() }, name)
            val options = ImageCapture.OutputFileOptions.Builder(target).build()

            runCatching {
                capture.takePicture(
                    options,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            LockLog.d("intruder photo captured (${target.length()} bytes)")
                            finish(context, name, targetPackage, failedAttempts)
                            release(provider)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            LockLog.w("intruder capture failed: ${exception.message}")
                            target.delete()
                            release(provider)
                        }
                    }
                )
            }.onFailure {
                LockLog.w("takePicture rejected: ${it.message}")
                target.delete()
                release(provider)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Main thread only — it moves [cameraLifecycle]. */
    private fun release(provider: ProcessCameraProvider) {
        runCatching {
            provider.unbindAll()
            cameraLifecycle.registry.currentState = Lifecycle.State.DESTROYED
        }
        capturingSince = 0L
    }

    private fun finish(context: Context, name: String, pkg: String, attempts: Int) {
        val app = AppLockApp.get()
        app.appScope.launch {
            app.repo.addIntruder(
                IntruderLog(
                    capturedAt = System.currentTimeMillis(),
                    photoFile = name,
                    targetPackage = pkg,
                    failedAttempts = attempts
                )
            )
            // Before anyone can open the gallery, not on the first frame of it. Downscaling a
            // 3 MB JPEG takes long enough to be visible if it happens while the user is
            // looking at an empty tile, and there is nothing to wait for out here.
            IntruderPhotos.cacheThumbnail(context, name)
            LockLog.d("intruder photo saved as $name")
        }
    }
}

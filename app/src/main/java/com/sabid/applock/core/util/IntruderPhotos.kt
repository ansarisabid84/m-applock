package com.sabid.applock.core.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.LruCache
import com.sabid.applock.AppLockApp
import com.sabid.applock.core.crypto.VaultCrypto
import com.sabid.applock.core.data.IntruderLog
import com.sabid.applock.service.LockLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * Reading, caching and expiring the intruder captures.
 *
 * Captures are ordinary JPEG files in `filesDir/intruders`, written exactly as the camera
 * produced them. Two consequences, both deliberate:
 *
 *  - **They never appear in the gallery.** Internal app storage is not part of MediaStore, so
 *    the media scanner never indexes it and no gallery app can list it. That — not encryption —
 *    is what keeps these photos out of the gallery, and it costs nothing to read.
 *  - **They open instantly.** Earlier builds stored each capture AES-GCM encrypted, so showing
 *    even a 64dp thumbnail meant reading three megabytes, decrypting the whole thing into a
 *    byte array and only then decoding. That work happened on the first sight of every photo,
 *    which is what made this screen crawl no matter how small the thumbnails were.
 *
 * Speed now comes from three layers, cheapest first:
 *  - an in-memory [LruCache], so scrolling back over a row costs nothing at all;
 *  - a ~30 KB thumbnail JPEG per capture under `intruders/thumbs`, written *at capture time*
 *    rather than on first view, so the gallery never has to touch a full-size photo to draw
 *    itself;
 *  - `inSampleSize` on the decodes that do read a full-size photo, so the full bitmap is never
 *    allocated even when a thumbnail has to be regenerated.
 *
 * The disk thumbnail is a pure derived cache: no database column points at it, deleting it
 * only costs one slow decode, and a capture whose thumbnail fails to generate still displays.
 */
object IntruderPhotos {

    /** Longest edge of a cached thumbnail, in pixels. Comfortably covers a 3-column tile. */
    private const val THUMB_PX = 320

    private const val THUMB_QUALITY = 82

    /** How captures were named while they were encrypted. See [migrateLegacy]. */
    private const val LEGACY_SUFFIX = ".enc"

    /**
     * Bumped whenever a change makes previously written thumbnails wrong rather than merely
     * suboptimal, which wipes the cache on the next start. Version 2 bakes in the capture's
     * EXIF rotation: version 1 thumbnails were saved straight from the landscape sensor frame,
     * so every face in the gallery was on its side while the full-screen view was upright.
     */
    private const val THUMB_VERSION = 2

    /**
     * Bitmap cache, sized as a fraction of the heap rather than a fixed byte count so it
     * behaves on both a 2 GB phone and a flagship.
     */
    private val memory = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceIn(2L * 1024 * 1024, 24L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun dir(context: Context): File = File(context.filesDir, "intruders")

    private fun thumbsDir(context: Context): File = File(dir(context), "thumbs")

    /** File name for a capture taken at [at]. Plain `.jpg` — see the class comment. */
    fun photoName(at: Long): String = "shot_$at.jpg"

    /**
     * Thumbnails are always named `.jpg` after the capture's base name, even for a legacy
     * `.enc` capture. That way [migrateLegacy] renaming the original does not orphan the
     * thumbnail it already generated.
     */
    private fun thumbName(photo: String): String = photo.substringBeforeLast('.') + ".jpg"

    private fun photoFile(context: Context, log: IntruderLog): File? =
        log.photoFile?.let { File(dir(context), it) }?.takeIf { it.isFile }

    /**
     * A small bitmap for [log], generating and caching one if needed.
     *
     * Null when the capture file is missing or unreadable — a row can outlive its file if
     * storage was cleared externally, and the gallery shows a placeholder rather than
     * pretending the photo is still there.
     */
    suspend fun thumbnail(context: Context, log: IntruderLog): Bitmap? {
        val name = log.photoFile ?: return null
        memory.get(name)?.let { return it }

        val thumb = withContext(Dispatchers.IO) {
            val cached = File(thumbsDir(context), thumbName(name))
            if (cached.isFile) {
                decodeFile(cached, THUMB_PX)?.let { return@withContext it }
                // Corrupt, half-written, or an encrypted thumbnail left by an older build.
                // Either way it is a derived file: throw it away and make a new one.
                cached.delete()
            }

            val fresh = read(context, log, THUMB_PX) ?: return@withContext null
            writeThumb(context, name, fresh)
            fresh
        } ?: return null

        memory.put(name, thumb)
        return thumb
    }

    /**
     * The capture at up to [maxPx] on its longest edge, for the full-screen viewer.
     *
     * Still sampled rather than decoded at full size: a phone screen cannot show 12
     * megapixels, and decoding them to prove it is how an image viewer runs out of memory on
     * the devices most likely to have a small heap. Not memory-cached — one of these is
     * several megabytes and only one is on screen at a time.
     */
    suspend fun full(context: Context, log: IntruderLog, maxPx: Int): Bitmap? =
        withContext(Dispatchers.IO) { read(context, log, maxPx) }

    /**
     * Writes the thumbnail for a capture that has just been taken.
     *
     * Called from the capture path so the cost lands while nobody is waiting, instead of on
     * the first frame of the gallery. Failure is not fatal: [thumbnail] regenerates on demand.
     */
    suspend fun cacheThumbnail(context: Context, photoName: String) {
        withContext(Dispatchers.IO) {
            val source = File(dir(context), photoName).takeIf { it.isFile } ?: return@withContext
            decodeFile(source, THUMB_PX)?.let { writeThumb(context, photoName, it) }
        }
    }

    /**
     * Generates any thumbnail that is missing, oldest row last.
     *
     * Runs once per process start for the handful of captures that predate the thumbnail cache
     * — or whose cache a "clear app data" wiped — so opening the gallery is fast the *first*
     * time too, which is the time it matters. A no-op on the normal path, since captures write
     * their own thumbnail.
     */
    suspend fun warmThumbnails(context: Context) {
        val logs = AppLockApp.get().repo.allIntruders()
        withContext(Dispatchers.IO) {
            discardOutdatedThumbs(context)
            logs.forEach { log ->
                val name = log.photoFile ?: return@forEach
                if (File(thumbsDir(context), thumbName(name)).isFile) return@forEach
                read(context, log, THUMB_PX)?.let { writeThumb(context, name, it) }
            }
        }
    }

    /**
     * Rewrites captures from earlier builds, which stored an AES-GCM blob, as plain JPEGs.
     *
     * One pass, once, on the first start of this build: decrypt, write the JPEG next to it,
     * repoint the row, delete the blob. A capture that fails to decrypt is left exactly as it
     * was — the loader still handles the encrypted form — because losing the one photo that
     * caught somebody would be far worse than keeping a slow path around for it.
     */
    suspend fun migrateLegacy(context: Context) {
        val stale = AppLockApp.get().repo.allIntruders()
            .filter { it.photoFile?.endsWith(LEGACY_SUFFIX) == true }
        if (stale.isEmpty()) return

        var moved = 0
        stale.forEach { log ->
            val oldName = log.photoFile ?: return@forEach
            val newName = thumbName(oldName)
            val converted = withContext(Dispatchers.IO) {
                val encrypted = File(dir(context), oldName)
                val bytes = runCatching { VaultCrypto.decryptBytes(encrypted) }
                    .onFailure { LockLog.w("could not convert $oldName: ${it.message}") }
                    .getOrNull() ?: return@withContext false

                // Temp-then-rename, so a kill mid-write cannot leave a truncated JPEG that
                // the row already points at.
                val temp = File(dir(context), "$newName.part")
                val ok = runCatching { temp.writeBytes(bytes) }
                    .map { temp.renameTo(File(dir(context), newName)) }
                    .getOrDefault(false)
                if (!ok) {
                    temp.delete()
                    return@withContext false
                }

                encrypted.delete()
                File(thumbsDir(context), oldName).delete()
                // From the file just written, not from the bytes in hand: the file read is the
                // one that applies the capture's EXIF rotation, and a thumbnail that disagrees
                // with the full-screen view about which way up the face goes is worse than no
                // thumbnail at all.
                decodeFile(File(dir(context), newName), THUMB_PX)
                    ?.let { writeThumb(context, newName, it) }
                true
            }
            if (converted) {
                AppLockApp.get().repo.setIntruderPhotoFile(log.id, newName)
                memory.remove(oldName)
                moved++
            }
        }
        if (moved > 0) LockLog.d("converted $moved intruder photo(s) to plain JPEG")
    }

    /**
     * Throws the whole thumbnail cache away when it was written by an older [THUMB_VERSION].
     *
     * Safe by construction — every file in here is derived from a capture that is still on
     * disk, so the cost of being wrong is one slow decode per photo, once.
     */
    private fun discardOutdatedThumbs(context: Context) {
        val dir = thumbsDir(context)
        val marker = File(dir, ".version")
        if (runCatching { marker.readText().trim().toInt() }.getOrNull() == THUMB_VERSION) return

        dir.listFiles()?.forEach { it.delete() }
        memory.evictAll()
        runCatching { dir.mkdirs(); marker.writeText(THUMB_VERSION.toString()) }
    }

    /** Decodes [log] at up to [maxPx], transparently handling a legacy encrypted capture. */
    private fun read(context: Context, log: IntruderLog, maxPx: Int): Bitmap? {
        val source = photoFile(context, log) ?: return null
        if (!source.name.endsWith(LEGACY_SUFFIX)) return decodeFile(source, maxPx)

        val bytes = runCatching { VaultCrypto.decryptBytes(source) }.getOrNull() ?: return null
        return decodeBytes(bytes, maxPx)
    }

    /**
     * Decodes [file] at the smallest power-of-two scale that still covers [maxPx], then
     * applies the JPEG's own rotation.
     *
     * The first pass reads only the header ([BitmapFactory.Options.inJustDecodeBounds]
     * allocates nothing), so the full-size bitmap is never allocated at any point. Honouring
     * EXIF matters here specifically: the front camera records orientation as metadata rather
     * than rotating pixels, and `BitmapFactory` ignores metadata — so without this every
     * capture is displayed on its side.
     */
    private fun decodeFile(file: File, maxPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)

        val decoded = BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply {
                inSampleSize = scaleFor(bounds, maxPx)
                // No alpha channel in a JPEG, so half the memory for the same picture.
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        ) ?: return@runCatching null

        rotate(decoded, degreesOf(file))
    }.getOrNull()

    private fun decodeBytes(bytes: ByteArray, maxPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = scaleFor(bounds, maxPx)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        ) ?: return@runCatching null

        rotate(decoded, degreesOf(ByteArrayInputStream(bytes)))
    }.getOrNull()

    private fun scaleFor(bounds: BitmapFactory.Options, maxPx: Int): Int {
        var scale = 1
        var longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / 2 >= maxPx) {
            scale *= 2
            longest /= 2
        }
        return scale
    }

    private fun degreesOf(file: File): Int =
        runCatching { degreesOf(ExifInterface(file.path)) }.getOrDefault(0)

    private fun degreesOf(stream: InputStream): Int =
        runCatching { stream.use { degreesOf(ExifInterface(it)) } }.getOrDefault(0)

    private fun degreesOf(exif: ExifInterface): Int =
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        return runCatching {
            Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height,
                Matrix().apply { postRotate(degrees.toFloat()) },
                true
            ).also { if (it != bitmap) bitmap.recycle() }
        }.getOrDefault(bitmap)
    }

    private fun writeThumb(context: Context, photo: String, thumb: Bitmap) {
        runCatching {
            val dir = thumbsDir(context).apply { mkdirs() }
            // Written to a temp name and renamed, so a kill mid-write cannot leave a
            // truncated file that decodes to nothing on every future launch.
            val temp = File(dir, "$photo.part")
            temp.outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, it) }
            if (!temp.renameTo(File(dir, thumbName(photo)))) temp.delete()
        }.onFailure { LockLog.w("could not cache intruder thumbnail: ${it.message}") }
    }

    /**
     * Deletes [log] and everything on disk that belongs to it.
     *
     * The database delete used to happen on its own, which left the capture in `filesDir`
     * forever with no row naming it — a file the user believed they had deleted, unreachable
     * through the UI and never cleaned up.
     */
    suspend fun delete(context: Context, log: IntruderLog) {
        AppLockApp.get().repo.deleteIntruder(log)
        withContext(Dispatchers.IO) { forget(context, log.photoFile) }
    }

    /** Deletes every capture, rows and files together. */
    suspend fun clearAll(context: Context) {
        val repo = AppLockApp.get().repo
        val logs = repo.allIntruders()
        repo.clearIntruders()
        withContext(Dispatchers.IO) {
            logs.forEach { forget(context, it.photoFile) }
            // Sweeps up anything already orphaned by an earlier build, which deleted rows
            // only. Safe because every row has just been removed.
            dir(context).listFiles()?.forEach { if (it.isFile) it.delete() }
            thumbsDir(context).listFiles()?.forEach { it.delete() }
        }
    }

    private fun forget(context: Context, name: String?) {
        val photo = name ?: return
        memory.remove(photo)
        File(dir(context), photo).delete()
        File(thumbsDir(context), thumbName(photo)).delete()
        // Older builds named the thumbnail after the encrypted capture, extension included.
        File(thumbsDir(context), photo).delete()
    }

    /**
     * Deletes captures past their retention window. Returns how many went.
     *
     * Cheap enough to call on every app start and on unlock: with auto-delete off it is a
     * single settings read, and with it on it is one indexed query that normally matches
     * nothing. Photos individually marked [IntruderLog.keepForever] are never touched, which
     * is what lets the owner keep the one shot that caught someone without keeping months of
     * their own face.
     */
    suspend fun sweep(context: Context): Int {
        val settings = AppLockApp.get().settings.current()
        if (!settings.intruderAutoDelete) return 0

        val cutoff = System.currentTimeMillis() -
            settings.intruderRetentionDays.toLong() * 24 * 60 * 60 * 1000
        val expired = AppLockApp.get().repo.expiredIntruders(cutoff)
        if (expired.isEmpty()) return 0

        expired.forEach { delete(context, it) }
        LockLog.d("swept ${expired.size} intruder photo(s) older than ${settings.intruderRetentionDays}d")
        return expired.size
    }
}

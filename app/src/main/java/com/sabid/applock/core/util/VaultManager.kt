package com.sabid.applock.core.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.sabid.applock.core.crypto.VaultCrypto
import com.sabid.applock.core.data.VaultItem
import com.sabid.applock.core.model.VaultKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Encrypted store for photos, videos, audio and arbitrary files.
 *
 * Import is a move, not a copy: the source is deleted once the ciphertext is on disk,
 * otherwise "hiding" a photo would leave the original sitting in the gallery.
 */
object VaultManager {

    private fun vaultDir(context: Context) = File(context.filesDir, "vault").apply { mkdirs() }
    private fun thumbDir(context: Context) = File(context.filesDir, "vault/thumbs").apply { mkdirs() }

    fun kindFor(mime: String?): VaultKind = when {
        mime == null -> VaultKind.FILE
        mime.startsWith("image/") -> VaultKind.PHOTO
        mime.startsWith("video/") -> VaultKind.VIDEO
        mime.startsWith("audio/") -> VaultKind.AUDIO
        else -> VaultKind.FILE
    }

    suspend fun import(context: Context, uri: Uri, deleteSource: Boolean = true): VaultItem? =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val name = safeFileName(displayName(context, uri)) ?: "item_${System.currentTimeMillis()}"
            val cipherFile = File(vaultDir(context), "v_${System.currentTimeMillis()}_${name.hashCode()}.enc")

            val size = runCatching {
                resolver.openInputStream(uri)?.use { VaultCrypto.encryptTo(it, cipherFile) } ?: 0L
            }.getOrElse {
                cipherFile.delete()
                return@withContext null
            }

            val kind = kindFor(mime)
            val thumb = if (kind == VaultKind.PHOTO) makeThumb(context, uri, cipherFile.name) else null

            if (deleteSource) {
                // Fails silently on scoped-storage URIs the app does not own; the UI
                // surfaces a "couldn't remove original" hint in that case.
                runCatching { resolver.delete(uri, null, null) }
            }

            VaultItem(
                displayName = name,
                kind = kind,
                cipherFile = cipherFile.name,
                thumbFile = thumb,
                sizeBytes = size,
                mimeType = mime
            )
        }

    private fun makeThumb(context: Context, uri: Uri, baseName: String): String? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = maxOf(1, minOf(bounds.outWidth / 256, bounds.outHeight / 256))
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bitmap.recycle()

        val thumbFile = File(thumbDir(context), "t_$baseName")
        out.toByteArray().inputStream().use { VaultCrypto.encryptTo(it, thumbFile) }
        thumbFile.name
    }.getOrNull()

    suspend fun thumbnail(context: Context, item: VaultItem): ImageBitmap? = withContext(Dispatchers.IO) {
        val name = item.thumbFile ?: return@withContext null
        runCatching {
            val bytes = VaultCrypto.decryptBytes(File(thumbDir(context), name))
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }

    /**
     * Decrypts an item into cacheDir so a system viewer can open it, and returns the
     * plaintext file. Callers must call [purgeCache] when the viewer closes.
     */
    suspend fun decryptForViewing(context: Context, item: VaultItem): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            // Sanitise again on the way out. displayName comes from a content provider,
            // and rows imported before the sanitiser existed can still hold "../".
            val out = File(dir, safeFileName(item.displayName) ?: "item_${item.id}")
            out.outputStream().use { VaultCrypto.decryptTo(File(vaultDir(context), item.cipherFile), it) }
            out
        }.getOrNull()
    }

    /** Restores an item back to plaintext storage outside the vault. */
    suspend fun export(context: Context, item: VaultItem, target: Uri): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(target)?.use { out ->
                    VaultCrypto.decryptTo(File(vaultDir(context), item.cipherFile), out)
                } ?: return@withContext false
                true
            }.getOrDefault(false)
        }

    suspend fun delete(context: Context, item: VaultItem) = withContext(Dispatchers.IO) {
        File(vaultDir(context), item.cipherFile).delete()
        item.thumbFile?.let { File(thumbDir(context), it).delete() }
    }

    fun purgeCache(context: Context) {
        File(context.cacheDir, "shared").listFiles()?.forEach { it.delete() }
    }

    /**
     * Reduces a provider-supplied file name to a single harmless path segment.
     *
     * The name arrives from another app's ContentProvider, which is free to return
     * "../../databases/applock.db". Handing that straight to File(dir, name) let an
     * import or an export overwrite AppLock's own database.
     */
    internal fun safeFileName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        // substringAfterLast on both separators collapses any traversal, then we drop
        // everything that is not obviously safe in a file name.
        val leaf = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = leaf.map { c ->
            if (c.isLetterOrDigit() || c in "._- ()[]") c else '_'
        }.joinToString("").trim().trimStart('.')
        return cleaned.take(120).ifBlank { null }
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
}

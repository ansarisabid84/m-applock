package com.sabid.applock.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM file encryption backed by the Android Keystore.
 *
 * The key never leaves the TEE / StrongBox, so vault files are unreadable if the
 * app data is copied off the device. Format on disk is: [1 byte IV length][IV][ciphertext].
 */
object VaultCrypto {

    private const val KEY_ALIAS = "applock_vault_key_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val BUFFER = 64 * 1024

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Deliberately NOT setUserAuthenticationRequired: the vault must be
                // readable while the overlay lock screen is showing, before biometrics.
                .build()
        )
        return gen.generateKey()
    }

    /** Streams [source] into [target], encrypting as it goes. Returns bytes written. */
    fun encryptTo(source: InputStream, target: File): Long {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val iv = cipher.iv
        target.outputStream().use { raw ->
            raw.write(iv.size)
            raw.write(iv)
            CipherOutputStream(raw, cipher).use { out -> source.copyTo(out, BUFFER) }
        }
        return target.length()
    }

    /** Opens a decrypting stream over an encrypted vault file. Caller closes it. */
    fun decryptStream(source: File): InputStream {
        val raw = source.inputStream()
        val ivLen = raw.read()
        require(ivLen in 1..32) { "Corrupt vault header in ${source.name}" }
        val iv = ByteArray(ivLen)
        var read = 0
        while (read < ivLen) {
            val n = raw.read(iv, read, ivLen - read)
            require(n > 0) { "Truncated vault header" }
            read += n
        }
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        }
        return CipherInputStream(raw, cipher)
    }

    fun decryptTo(source: File, target: OutputStream) {
        decryptStream(source).use { it.copyTo(target, BUFFER) }
    }

    fun decryptBytes(source: File): ByteArray = decryptStream(source).use { it.readBytes() }
}

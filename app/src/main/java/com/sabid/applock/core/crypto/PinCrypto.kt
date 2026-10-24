package com.sabid.applock.core.crypto

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Secret verification for the lock screen.
 *
 * The PIN / pattern / password is never stored. We store PBKDF2-HMAC-SHA256(secret, salt)
 * and compare in constant time, so a database dump does not reveal the unlock secret.
 */
object PinCrypto {

    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256

    data class Digest(val saltB64: String, val hashB64: String)

    fun hash(secret: CharArray, saltB64: String? = null): Digest {
        val salt = saltB64?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: ByteArray(16).also { SecureRandom().nextBytes(it) }
        val spec = PBEKeySpec(secret, salt, ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return Digest(
            saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP),
            hashB64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        )
    }

    fun verify(secret: CharArray, stored: Digest): Boolean {
        val candidate = hash(secret, stored.saltB64)
        return MessageDigest.isEqual(
            Base64.decode(candidate.hashB64, Base64.NO_WRAP),
            Base64.decode(stored.hashB64, Base64.NO_WRAP)
        )
    }

    /** Pattern nodes 0..8 encoded as a stable string, e.g. "0-4-8-7". */
    fun encodePattern(nodes: List<Int>): CharArray = nodes.joinToString("-").toCharArray()
}

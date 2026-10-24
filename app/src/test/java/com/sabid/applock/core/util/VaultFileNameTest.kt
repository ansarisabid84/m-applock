package com.sabid.applock.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The display name on an imported file comes from whatever content provider handed us the
 * Uri, so it is attacker-controlled in the same sense any external input is. These cases
 * cover the ways it could previously have escaped filesDir/vault.
 */
class VaultFileNameTest {

    @Test
    fun `keeps an ordinary name intact`() {
        assertEquals("holiday photo (2).jpg", VaultManager.safeFileName("holiday photo (2).jpg"))
    }

    @Test
    fun `strips directory components`() {
        assertEquals("passwd", VaultManager.safeFileName("../../../../etc/passwd"))
        assertEquals("secret.txt", VaultManager.safeFileName("/data/data/com.other/secret.txt"))
        assertEquals("file.txt", VaultManager.safeFileName("..\\..\\windows\\file.txt"))
    }

    @Test
    fun `strips leading dots so nothing becomes a traversal or a hidden file`() {
        assertEquals("ssh", VaultManager.safeFileName(".ssh"))
        assertNull(VaultManager.safeFileName(".."))
        assertNull(VaultManager.safeFileName("."))
    }

    @Test
    fun `replaces characters that are not safe in a file name`() {
        assertEquals("a_b_c", VaultManager.safeFileName("a:b*c"))
        assertEquals("name_txt_", VaultManager.safeFileName("name?txt|"))
    }

    @Test
    fun `rejects blank and null input`() {
        assertNull(VaultManager.safeFileName(null))
        assertNull(VaultManager.safeFileName(""))
        assertNull(VaultManager.safeFileName("   "))
    }

    @Test
    fun `caps the length so no path can exceed the filesystem limit`() {
        val result = VaultManager.safeFileName("x".repeat(500))
        assertEquals(120, result?.length)
    }
}

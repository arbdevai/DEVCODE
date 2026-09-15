package com.devcode.terminal

import com.devcode.terminal.core.ubuntu.UbuntuManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UbuntuManagerTest {

    @Test
    fun testInstallStatusEnumValues() {
        val statuses = UbuntuManager.InstallStatus.values()
        assertNotNull(statuses)
        assertEquals(7, statuses.size)
        assertEquals("INSTALLED", UbuntuManager.InstallStatus.INSTALLED.name)
        assertEquals("NOT_INSTALLED", UbuntuManager.InstallStatus.NOT_INSTALLED.name)
    }

    @Test
    fun testInitialState() {
        val state = UbuntuManager.UbuntuState()
        assertEquals(UbuntuManager.InstallStatus.UNKNOWN, state.status)
        assertEquals(0f, state.downloadProgress, 0.001f)
        assertEquals("", state.message)
        assertEquals(0L, state.storageUsedBytes)
        assertEquals(0L, state.storageAvailBytes)
        assertEquals(false, state.busy)
    }

    @Test
    fun testConstants() {
        assertEquals("/data/local/devcode/ubuntu", UbuntuManager.INSTALL_DIR)
        assertEquals("/data/local/devcode/staging", UbuntuManager.STAGING_DIR)
        assert(UbuntuManager.ROOTFS_URL.endsWith(".tar.gz"))
        assert(UbuntuManager.SHA256SUMS_URL.endsWith("SHA256SUMS"))
        assertEquals("ubuntu-base-24.04.5-base-arm64.tar.gz", UbuntuManager.TARBALL_FILENAME)
        assertEquals("com.devcode.terminal", UbuntuManager.OWNER_VALUE)
    }

    @Test
    fun testParseSha256SumsStandardFormat() {
        val hash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val body = """
            $hash  ubuntu-base-24.04.5-base-arm64.tar.gz
            1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef  other.tar.gz
        """.trimIndent()

        val parsed = UbuntuManager.parseSha256Sums(body, "ubuntu-base-24.04.5-base-arm64.tar.gz")
        assertEquals(hash, parsed)
    }

    @Test
    fun testParseSha256SumsBinaryAsteriskFormat() {
        val hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val body = "$hash *ubuntu-base-24.04.5-base-arm64.tar.gz"

        val parsed = UbuntuManager.parseSha256Sums(body, "ubuntu-base-24.04.5-base-arm64.tar.gz")
        assertEquals(hash, parsed)
    }

    @Test
    fun testParseSha256SumsNotFound() {
        val body = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  unrelated-file.tar.gz"
        val parsed = UbuntuManager.parseSha256Sums(body, "ubuntu-base-24.04.5-base-arm64.tar.gz")
        assertNull(parsed)
    }

    @Test
    fun testParseSha256SumsInvalidHashLength() {
        val body = "tooshort  ubuntu-base-24.04.5-base-arm64.tar.gz"
        val parsed = UbuntuManager.parseSha256Sums(body, "ubuntu-base-24.04.5-base-arm64.tar.gz")
        assertNull(parsed)
    }
}

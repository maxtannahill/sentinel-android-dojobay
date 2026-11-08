package com.samourai.sentinel.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * #471: import size limits must hold against providers whose
 * `available()` lies, and must reject without reading the whole stream.
 */
class ImportLimitsTest {

    /** Endless 'a' bytes; available() always 0; counts bytes handed out. */
    private class LyingStream(private val total: Long = Long.MAX_VALUE) : InputStream() {
        var served = 0L
        override fun available(): Int = 0
        override fun read(): Int {
            if (served >= total) return -1
            served++
            return 'a'.code
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (served >= total) return -1
            val n = minOf(len.toLong(), total - served).toInt()
            b.fill('a'.code.toByte(), off, off + n)
            served += n
            return n
        }
    }

    @Test
    fun `stream with zero available but over the limit is rejected early`() {
        val limit = 1_000
        val stream = LyingStream()
        try {
            ImportLimits.readBoundedText(stream, limit)
            fail("expected ImportTooLargeException")
        } catch (e: ImportTooLargeException) {
            assertEquals(limit.toLong(), e.limit)
        }
        // Never read more than limit + 1 bytes from the provider.
        assertEquals(limit + 1L, stream.served)
    }

    @Test
    fun `backup-sized limit rejects an oversized lying stream`() {
        val stream = LyingStream(ImportLimits.BACKUP_MAX_BYTES + 10_000L)
        try {
            ImportLimits.readBoundedText(stream, ImportLimits.BACKUP_MAX_BYTES)
            fail("expected ImportTooLargeException")
        } catch (e: ImportTooLargeException) {
            assertEquals(ImportLimits.BACKUP_MAX_BYTES + 1L, stream.served)
        }
    }

    @Test
    fun `content exactly at the limit is accepted`() {
        val text = ImportLimits.readBoundedText(LyingStream(1_000), 1_000)
        assertEquals(1_000, text.length)
    }

    @Test
    fun `utf8 content round-trips`() {
        val json = """{"payload":"é✓"}"""
        val text = ImportLimits.readBoundedText(
            ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)), 1_000
        )
        assertEquals(json, text)
    }

    @Test
    fun `pasted text over the limit is rejected`() {
        ImportLimits.requireTextWithin("a".repeat(10), 10)
        try {
            ImportLimits.requireTextWithin("a".repeat(11), 10)
            fail("expected ImportTooLargeException")
        } catch (e: ImportTooLargeException) {
            assertTrue(e.message!!.contains("10"))
        }
    }

    @Test
    fun `decoded transaction bytes over the limit are rejected`() {
        ImportLimits.requireBytesWithin(ByteArray(ImportLimits.TX_MAX_DECODED_BYTES), ImportLimits.TX_MAX_DECODED_BYTES)
        try {
            ImportLimits.requireBytesWithin(
                ByteArray(ImportLimits.TX_MAX_DECODED_BYTES + 1), ImportLimits.TX_MAX_DECODED_BYTES
            )
            fail("expected ImportTooLargeException")
        } catch (_: ImportTooLargeException) {
        }
    }

    @Test
    fun `collection and key counts are bounded`() {
        ImportLimits.requireImportCounts(ImportLimits.MAX_IMPORT_COLLECTIONS, ImportLimits.MAX_IMPORT_PUBKEYS)
        try {
            ImportLimits.requireImportCounts(ImportLimits.MAX_IMPORT_COLLECTIONS + 1, 0)
            fail("expected collections limit")
        } catch (e: ImportTooLargeException) {
            assertEquals("collections", e.unit)
        }
        try {
            ImportLimits.requireImportCounts(1, ImportLimits.MAX_IMPORT_PUBKEYS + 1)
            fail("expected pubkey limit")
        } catch (e: ImportTooLargeException) {
            assertEquals("public keys", e.unit)
        }
    }
}

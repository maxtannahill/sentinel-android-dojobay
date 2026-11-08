package com.samourai.sentinel.util

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * #471: enforceable size limits for user-supplied import content.
 *
 * The old guards asked `InputStream.available()`, which only estimates
 * what can be read without blocking - a content provider may return 0 and
 * then stream far more. These helpers count what is actually read and stop
 * at `limit + 1` bytes, so an oversized document is rejected before it is
 * ever held in memory as a string, JSON tree, ciphertext and plaintext.
 */
object ImportLimits {

    /** Backup envelope, file or clipboard (the pre-#471 nominal limit). */
    const val BACKUP_MAX_BYTES = 5_000_000

    /** Transaction text envelope (hex, possibly with whitespace), file or clipboard. */
    const val TX_TEXT_MAX_CHARS = 1_000_000

    /**
     * Decoded transaction bytes. A standard transaction is at most
     * 400,000 weight units, so its serialization cannot exceed 400 kB.
     */
    const val TX_MAX_DECODED_BYTES = 400_000

    /** Collections in one decrypted backup. Generous; real wallets hold a handful. */
    const val MAX_IMPORT_COLLECTIONS = 1_000

    /** Public keys across all collections in one decrypted backup. */
    const val MAX_IMPORT_PUBKEYS = 5_000

    /**
     * Reads at most [maxBytes] from [input] and decodes it as UTF-8.
     * Throws [ImportTooLargeException] as soon as byte `maxBytes + 1`
     * arrives; never consults `available()`. Does not close [input].
     */
    @Throws(IOException::class)
    fun readBoundedText(input: InputStream, maxBytes: Int): String {
        require(maxBytes >= 0)
        val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val chunk = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            // Never ask for more than one byte past the limit.
            val want = minOf(chunk.size.toLong(), maxBytes + 1L - total).toInt()
            val n = input.read(chunk, 0, want)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw ImportTooLargeException(maxBytes.toLong(), "bytes")
            out.write(chunk, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** Rejects text (e.g. clipboard content) longer than [maxChars]. */
    fun requireTextWithin(text: CharSequence, maxChars: Int) {
        if (text.length > maxChars) throw ImportTooLargeException(maxChars.toLong(), "characters")
    }

    /** Rejects decoded payloads larger than [maxBytes]. */
    fun requireBytesWithin(bytes: ByteArray, maxBytes: Int) {
        if (bytes.size > maxBytes) throw ImportTooLargeException(maxBytes.toLong(), "bytes")
    }

    /**
     * Rejects a decrypted backup whose collection or key counts exceed
     * [MAX_IMPORT_COLLECTIONS] / [MAX_IMPORT_PUBKEYS]. Called before
     * anything is written, so existing wallet data stays untouched.
     */
    fun requireImportCounts(collectionCount: Int, pubKeyCount: Int) {
        if (collectionCount > MAX_IMPORT_COLLECTIONS) {
            throw ImportTooLargeException(MAX_IMPORT_COLLECTIONS.toLong(), "collections")
        }
        if (pubKeyCount > MAX_IMPORT_PUBKEYS) {
            throw ImportTooLargeException(MAX_IMPORT_PUBKEYS.toLong(), "public keys")
        }
    }
}

class ImportTooLargeException(val limit: Long, val unit: String) :
    IOException("Import is too large (limit: $limit $unit)")

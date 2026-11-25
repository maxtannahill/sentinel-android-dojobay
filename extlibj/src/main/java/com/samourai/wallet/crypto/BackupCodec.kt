package com.samourai.wallet.crypto

import com.samourai.wallet.util.CharSequenceX
import org.bouncycastle.util.encoders.Base64
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Versioned backup envelope.
 *
 * Format 3 is AES-256-GCM with a key from Argon2id. The header carries the
 * KDF profile that produced the key. Older envelopes stay readable:
 * version 1 uses the historical PBKDF2 count, and every later legacy
 * envelope uses [AESUtil.DefaultPBKDF2HMACSHA256Iterations], the same count
 * export used for that format. [com.samourai.sentinel.util.ExportImportUtil]
 * opens both current and legacy sentinel backups through here so the two
 * decrypt paths cannot pick different counts (#278).
 */
object BackupCodec {

    const val FORMAT_AES_GCM = 3

    private const val KDF_ARGON2ID = "argon2id"
    private const val AES_BLOCK_BYTES = 16
    private const val LEGACY_SALTED_HEADER_LEN = 8
    private const val LEGACY_SALT_LEN = 8
    private const val LEGACY_HEADER_LEN = LEGACY_SALTED_HEADER_LEN + LEGACY_SALT_LEN
    private const val LEGACY_MIN_BYTES = LEGACY_HEADER_LEN + AES_BLOCK_BYTES
    private const val GCM_MIN_BYTES = AesGcm.NONCE_LEN + AesGcm.TAG_BITS / 8
    private val SALTED_PREFIX = "Salted__".toByteArray(Charsets.US_ASCII)
    private val random = SecureRandom()

    /**
     * #404: optional network declaration for the envelope, written beside
     * format/version/time. Only a unanimous keychain produces a value; a
     * mixed or empty one passes null and nothing is written - absent
     * means absent, readers must not guess. No format bump: older readers
     * ignore the extra key, and no decrypt arm changes.
     */
    fun encrypt(
        plaintext: String,
        password: String,
        appVersionCode: Int,
        nowMillis: Long = System.currentTimeMillis(),
        declaredNetwork: String? = null
    ): String {
        if (password.isEmpty()) {
            throw BackupDecryptException("Password is empty")
        }
        val salt = ByteArray(16)
        random.nextBytes(salt)
        val passwordChars = password.toCharArray()
        val key = Argon2Kdf.derive(
            passwordChars,
            salt,
            Argon2Kdf.BACKUP_MEMORY_KIB,
            Argon2Kdf.BACKUP_ITERATIONS,
            Argon2Kdf.BACKUP_PARALLELISM,
            AesGcm.KEY_LEN
        )
        try {
            val blob = AesGcm.encrypt(key, plaintext.toByteArray(Charsets.UTF_8))
            val envelope = JSONObject()
                .put("format", FORMAT_AES_GCM)
                .put("version", appVersionCode)
                .put("time", nowMillis)
            // #404: write-site guard - only the two contract values ever
            // land in the envelope, whatever a future caller passes.
            if (declaredNetwork == "mainnet" || declaredNetwork == "testnet") {
                envelope.put("network", declaredNetwork)
            }
            return envelope
                .put("kdf", KDF_ARGON2ID)
                .put("salt", Base64.toBase64String(salt))
                .put("memoryKiB", Argon2Kdf.BACKUP_MEMORY_KIB)
                .put("iterations", Argon2Kdf.BACKUP_ITERATIONS)
                .put("parallelism", Argon2Kdf.BACKUP_PARALLELISM)
                .put("payload", Base64.toBase64String(blob))
                .toString()
        } finally {
            passwordChars.fill('\u0000')
            key.fill(0)
        }
    }

    /**
     * Validates a parsed Sentinel backup envelope's structure without requiring
     * the password or running a KDF.
     *
     * Throws [BackupCorruptException] when required fields, KDF parameters,
     * Base64 encoding, OpenSSL "Salted__" header, or cipher/GCM byte lengths
     * are missing, malformed, or truncated.
     */
    fun validateEnvelope(json: JSONObject) {
        val rawPayload = json.opt("payload") as? String
        if (rawPayload.isNullOrEmpty()) {
            throw BackupCorruptException("Invalid payload")
        }
        if (json.has("format")) {
            val format = intField(json, "format")
                ?: throw BackupCorruptException("Invalid backup format")
            if (format != FORMAT_AES_GCM) {
                throw BackupCorruptException("Unsupported backup format")
            }
            validateCurrentEnvelope(json, rawPayload)
            return
        }
        validateLegacyEnvelope(json, rawPayload)
    }

    private fun validateCurrentEnvelope(json: JSONObject, rawPayload: String) {
        if (json.opt("kdf") as? String != KDF_ARGON2ID) {
            throw BackupCorruptException("Unsupported backup KDF")
        }
        val memoryKiB = intField(json, "memoryKiB") ?: -1
        val iterations = intField(json, "iterations") ?: -1
        val parallelism = intField(json, "parallelism") ?: -1
        if (memoryKiB < 8 * 1024 || memoryKiB > 256 * 1024 ||
            iterations < 1 || iterations > 8 ||
            parallelism != 1
        ) {
            throw BackupCorruptException("Unsupported backup KDF profile")
        }
        val rawSalt = json.opt("salt") as? String
            ?: throw BackupCorruptException("Unsupported backup salt")
        val salt = decodeStrictBase64(rawSalt)
        if (salt.size < 16 || salt.size > 64) {
            throw BackupCorruptException("Unsupported backup salt")
        }
        val blob = decodeStrictBase64(rawPayload)
        if (blob.size < GCM_MIN_BYTES) {
            throw BackupCorruptException("Truncated backup payload")
        }
    }

    private fun validateLegacyEnvelope(json: JSONObject, rawPayload: String) {
        val version = legacyVersionField(json)
            ?: throw BackupCorruptException("Invalid backup version")
        val bytes = decodeStrictBase64(rawPayload)
        if (bytes.size < LEGACY_MIN_BYTES || bytes.size % AES_BLOCK_BYTES != 0) {
            throw BackupCorruptException("Truncated or misaligned backup payload")
        }
        if (version != 1) {
            for (i in SALTED_PREFIX.indices) {
                if (bytes[i] != SALTED_PREFIX[i]) {
                    throw BackupCorruptException("Invalid backup header")
                }
            }
        }
    }

    fun decryptEnvelope(envelope: String, password: String): String {
        val json = try {
            JSONObject(envelope)
        } catch (e: Exception) {
            throw BackupCorruptException("Invalid payload", e)
        }
        validateEnvelope(json)
        if (password.isEmpty()) {
            throw BackupDecryptException("Password is empty")
        }
        if (json.optInt("format", 0) == FORMAT_AES_GCM) {
            return decryptCurrent(json, password)
        }
        return decryptLegacy(json.getString("payload"), json.getInt("version"), password)
    }

    private fun decryptCurrent(json: JSONObject, password: String): String {
        if (json.optString("kdf") != KDF_ARGON2ID) {
            throw BackupDecryptException("Unsupported backup KDF")
        }
        val memoryKiB = json.optInt("memoryKiB", -1)
        val iterations = json.optInt("iterations", -1)
        val parallelism = json.optInt("parallelism", -1)
        if (memoryKiB < 8 * 1024 || memoryKiB > 256 * 1024 ||
            iterations < 1 || iterations > 8 ||
            parallelism != 1
        ) {
            throw BackupDecryptException("Unsupported backup KDF profile")
        }
        val salt = decodeStrictBase64(json.optString("salt"))
        val blob = decodeStrictBase64(json.optString("payload"))
        if (salt.size < 16 || salt.size > 64) {
            throw BackupDecryptException("Unsupported backup salt")
        }
        val passwordChars = password.toCharArray()
        val key = Argon2Kdf.derive(
            passwordChars,
            salt,
            memoryKiB,
            iterations,
            parallelism,
            AesGcm.KEY_LEN
        )
        try {
            val plain = AesGcm.decrypt(key, blob)
            return String(plain, Charsets.UTF_8)
        } catch (e: AuthTagException) {
            throw BackupDecryptException("Authentication check failed", e)
        } finally {
            passwordChars.fill('\u0000')
            key.fill(0)
        }
    }

    private fun decryptLegacy(payload: String, version: Int, password: String): String {
        val passwordChars = CharSequenceX(password)
        val plain = try {
            if (version == 1) {
                AESUtil.decrypt(payload, passwordChars, AESUtil.DefaultPBKDF2Iterations)
            } else {
                AESUtil.decryptSHA256(
                    payload,
                    passwordChars,
                    AESUtil.DefaultPBKDF2HMACSHA256Iterations
                )
            }
        } catch (e: Exception) {
            throw BackupDecryptException("Unable to decrypt", e)
        }
        if (plain.indexOf('\uFFFD') >= 0) {
            throw BackupDecryptException("Unable to decrypt")
        }
        return plain
    }

    private fun intField(json: JSONObject, key: String): Int? {
        if (!json.has(key) || json.isNull(key)) return null
        val value = json.opt(key)
        return when (value) {
            is Int -> value
            is Long -> if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else null
            else -> null
        }
    }

    private fun legacyVersionField(json: JSONObject): Int? {
        if (!json.has("version") || json.isNull("version")) return null
        val value = json.opt("version")
        return when (value) {
            is Int -> value
            is Long -> if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else null
            is String -> value.toIntOrNull()
            else -> null
        }
    }

    private fun decodeStrictBase64(value: String): ByteArray {
        val cleaned = buildString(value.length) {
            for (ch in value) {
                if (ch != '\r' && ch != '\n') append(ch)
            }
        }.trim()
        if (cleaned.isEmpty() || cleaned.length % 4 != 0) {
            throw BackupCorruptException("Invalid payload encoding")
        }
        val len = cleaned.length
        for (i in 0 until len) {
            val ch = cleaned[i]
            val isBase64Char = (ch in 'A'..'Z') || (ch in 'a'..'z') || (ch in '0'..'9') || ch == '+' || ch == '/'
            if (isBase64Char) continue
            if (ch == '=') {
                if (i < len - 2) throw BackupCorruptException("Invalid payload padding")
                if (i == len - 2 && cleaned[len - 1] != '=') throw BackupCorruptException("Invalid payload padding")
                continue
            }
            throw BackupCorruptException("Invalid payload character")
        }
        if (cleaned[len - 1] == '=') {
            if (cleaned[len - 2] == '=') {
                val idx = base64AlphabetIndex(cleaned[len - 3])
                if ((idx and 0x0F) != 0) throw BackupCorruptException("Invalid payload padding")
            } else {
                val idx = base64AlphabetIndex(cleaned[len - 2])
                if ((idx and 0x03) != 0) throw BackupCorruptException("Invalid payload padding")
            }
        }
        return try {
            Base64.decode(cleaned)
        } catch (e: Exception) {
            throw BackupCorruptException("Invalid payload encoding", e)
        }
    }

    private fun base64AlphabetIndex(ch: Char): Int = when (ch) {
        in 'A'..'Z' -> ch - 'A'
        in 'a'..'z' -> ch - 'a' + 26
        in '0'..'9' -> ch - '0' + 52
        '+' -> 62
        '/' -> 63
        else -> -1
    }
}

open class BackupDecryptException(message: String, cause: Throwable? = null) : Exception(message, cause)

class BackupCorruptException(message: String, cause: Throwable? = null) :
    BackupDecryptException(message, cause)

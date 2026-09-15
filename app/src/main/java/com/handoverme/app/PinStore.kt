package com.handoverme.app

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

class PinStore(context: Context) {

    private val prefs = context.getSharedPreferences(
        "handover_security",
        Context.MODE_PRIVATE
    )

    private val random = SecureRandom()

    fun hasPin(): Boolean =
        prefs.contains(KEY_SALT) && prefs.contains(KEY_HASH)

    fun setPin(pin: String) {
        require(pin.length in 4..12)
        require(pin.all { it in '0'..'9' })

        val salt = ByteArray(32).also(random::nextBytes)
        val hash = sha256(salt, pin.toByteArray(Charsets.UTF_8))

        prefs.edit()
            .putString(KEY_SALT, salt.toHex())
            .putString(KEY_HASH, hash.toHex())
            .apply()
    }

    fun verify(pin: String): Boolean {
        val saltHex = prefs.getString(KEY_SALT, null) ?: return false
        val hashHex = prefs.getString(KEY_HASH, null) ?: return false

        val salt = saltHex.hexToBytes()
        val expected = hashHex.hexToBytes()
        val actual = sha256(salt, pin.toByteArray(Charsets.UTF_8))

        return MessageDigest.isEqual(expected, actual)
    }

    private fun sha256(salt: ByteArray, value: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(salt + value)
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0)
        return ByteArray(length / 2) {
            substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    companion object {
        private const val KEY_SALT = "pin_salt"
        private const val KEY_HASH = "pin_hash"
    }
}

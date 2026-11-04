package ru.flowseal.tgwsproxy.data

import java.security.SecureRandom

data class ProxyConfig(
    val port: Int = DEFAULT_PORT,
    val secretHex: String = DEFAULT_SECRET_HEX,
    val customWorkerDomains: String = ""
) {
    companion object {
        const val DEFAULT_PORT = 1443
        // Standard 16-byte hex secret (must NOT start with 'ee' or 'dd' so Telegram doesn't trigger Fake TLS)
        const val DEFAULT_SECRET_HEX = "102030405060708090a0b0c0d0e0f011"

        fun generateRandomSecret(): String {
            val random = SecureRandom()
            val bytes = ByteArray(16)
            random.nextBytes(bytes)
            // Ensure first byte is neither 0xee (Fake-TLS) nor 0xdd (Padded intermediate)
            while (bytes[0] == 0xee.toByte() || bytes[0] == 0xdd.toByte()) {
                bytes[0] = random.nextInt(256).toByte()
            }
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    fun getSecretBytes(): ByteArray {
        val cleanHex = secretHex.trim()
        if (cleanHex.length % 2 != 0 || cleanHex.isEmpty()) {
            return hexToBytes(DEFAULT_SECRET_HEX)
        }
        return try {
            hexToBytes(cleanHex)
        } catch (_: Exception) {
            hexToBytes(DEFAULT_SECRET_HEX)
        }
    }

    fun getWorkerDomainsList(): List<String> {
        return customWorkerDomains.split(",", "\n", ";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}

package ru.flowseal.tgwsproxy.core

object Constants {
    const val HANDSHAKE_LEN = 64
    const val SKIP_LEN = 8
    const val PREKEY_LEN = 32
    const val KEY_LEN = 32
    const val IV_LEN = 16
    const val PROTO_TAG_POS = 56
    const val DC_IDX_POS = 60

    val PROTO_TAG_ABRIDGED = byteArrayOf(0xef.toByte(), 0xef.toByte(), 0xef.toByte(), 0xef.toByte())
    val PROTO_TAG_INTERMEDIATE = byteArrayOf(0xee.toByte(), 0xee.toByte(), 0xee.toByte(), 0xee.toByte())
    val PROTO_TAG_SECURE = byteArrayOf(0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte())

    const val PROTO_ABRIDGED_INT = 0xEFEFEFEF.toInt()
    const val PROTO_INTERMEDIATE_INT = 0xEEEEEEEE.toInt()
    const val PROTO_PADDED_INTERMEDIATE_INT = 0xDDDDDDDD.toInt()

    val RESERVED_FIRST_BYTES: Set<Byte> = setOf(0xef.toByte())
    val RESERVED_STARTS: List<ByteArray> = listOf(
        "HEAD".toByteArray(Charsets.US_ASCII),
        "POST".toByteArray(Charsets.US_ASCII),
        "GET ".toByteArray(Charsets.US_ASCII),
        byteArrayOf(0xee.toByte(), 0xee.toByte(), 0xee.toByte(), 0xee.toByte()),
        byteArrayOf(0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte()),
        byteArrayOf(0x16.toByte(), 0x03.toByte(), 0x01.toByte(), 0x02.toByte()),
        "OPTI".toByteArray(Charsets.US_ASCII)
    )
    val RESERVED_CONTINUE = byteArrayOf(0x00, 0x00, 0x00, 0x00)

    val ZERO_64 = ByteArray(64)

    val DC_DEFAULT_IPS = mapOf(
        1 to "149.154.175.50",
        2 to "149.154.167.51",
        3 to "149.154.175.100",
        4 to "149.154.167.91",
        5 to "149.154.171.5",
        203 to "91.105.192.100"
    )

    fun wsDomains(dcId: Int, isMedia: Boolean): List<String> {
        val dc = if (dcId == 203) 2 else dcId
        return if (!isMedia) {
            listOf("kws$dc.web.telegram.org")
        } else {
            listOf("kws$dc-1.web.telegram.org", "kws$dc.web.telegram.org")
        }
    }
}

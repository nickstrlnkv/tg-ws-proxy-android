package ru.flowseal.tgwsproxy.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class HandshakeResult(
    val dcId: Int,
    val isMedia: Boolean,
    val protoTag: ByteArray,
    val clientDecPrekeyIv: ByteArray,
    val matchedSecret: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HandshakeResult
        if (dcId != other.dcId) return false
        if (isMedia != other.isMedia) return false
        if (!protoTag.contentEquals(other.protoTag)) return false
        if (!clientDecPrekeyIv.contentEquals(other.clientDecPrekeyIv)) return false
        if (!matchedSecret.contentEquals(other.matchedSecret)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = dcId
        result = 31 * result + isMedia.hashCode()
        result = 31 * result + protoTag.contentHashCode()
        result = 31 * result + clientDecPrekeyIv.contentHashCode()
        result = 31 * result + matchedSecret.contentHashCode()
        return result
    }
}

class CryptoCtx(
    val cltDec: Cipher,
    val cltEnc: Cipher,
    val tgEnc: Cipher,
    val tgDec: Cipher
)

object MtprotoCrypto {

    private const val TAG = "TGWSProxy"

    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val len = clean.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    fun createAesCtrCipher(key: ByteArray, iv: ByteArray, mode: Int = Cipher.ENCRYPT_MODE): Cipher {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(mode, keySpec, ivSpec)
        return cipher
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (part in parts) {
            md.update(part)
        }
        return md.digest()
    }

    fun tryHandshakeWithSecrets(handshake: ByteArray, candidateSecrets: List<ByteArray>): HandshakeResult? {
        if (handshake.size < Constants.HANDSHAKE_LEN) {
            AppLog.w(TAG, "Handshake buffer size ${handshake.size} < 64")
            return null
        }

        val decPrekeyAndIv = handshake.copyOfRange(
            Constants.SKIP_LEN,
            Constants.SKIP_LEN + Constants.PREKEY_LEN + Constants.IV_LEN
        )
        val decPrekey = decPrekeyAndIv.copyOfRange(0, Constants.PREKEY_LEN)
        val decIv = decPrekeyAndIv.copyOfRange(Constants.PREKEY_LEN, Constants.PREKEY_LEN + Constants.IV_LEN)

        for (secret in candidateSecrets) {
            val decKey = sha256(decPrekey, secret)
            val decryptor = createAesCtrCipher(decKey, decIv, Cipher.DECRYPT_MODE)
            val decrypted = try {
                decryptor.doFinal(handshake)
            } catch (e: Exception) {
                decryptor.update(handshake)
            } ?: continue

            val protoTag = decrypted.copyOfRange(Constants.PROTO_TAG_POS, Constants.PROTO_TAG_POS + 4)
            val isAbridged = protoTag.contentEquals(Constants.PROTO_TAG_ABRIDGED)
            val isIntermediate = protoTag.contentEquals(Constants.PROTO_TAG_INTERMEDIATE)
            val isSecure = protoTag.contentEquals(Constants.PROTO_TAG_SECURE)

            if (isAbridged || isIntermediate || isSecure) {
                val dcIdx = ByteBuffer.wrap(decrypted, Constants.DC_IDX_POS, 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .short

                val dcId = Math.abs(dcIdx.toInt())
                val isMedia = dcIdx < 0

                AppLog.i(TAG, "Handshake SUCCESS with secret ${bytesToHex(secret)}: DC$dcId, media=$isMedia, tag=${bytesToHex(protoTag)}")
                return HandshakeResult(
                    dcId = dcId,
                    isMedia = isMedia,
                    protoTag = protoTag,
                    clientDecPrekeyIv = decPrekeyAndIv,
                    matchedSecret = secret
                )
            } else {
                AppLog.d(TAG, "Candidate secret ${bytesToHex(secret)} decrypted protoTag 0x${bytesToHex(protoTag)} (no match)")
            }
        }

        return null
    }

    fun tryHandshake(handshake: ByteArray, secret: ByteArray): HandshakeResult? {
        return tryHandshakeWithSecrets(handshake, listOf(secret))
    }

    fun generateRelayInit(
        protoTag: ByteArray,
        dcIdx: Short,
        secureRandom: SecureRandom = SecureRandom()
    ): ByteArray {
        val rnd = ByteArray(Constants.HANDSHAKE_LEN)
        while (true) {
            secureRandom.nextBytes(rnd)

            if (Constants.RESERVED_FIRST_BYTES.contains(rnd[0])) {
                continue
            }

            val first4 = rnd.copyOfRange(0, 4)
            var matchedReserved = false
            for (reserved in Constants.RESERVED_STARTS) {
                if (first4.contentEquals(reserved)) {
                    matchedReserved = true
                    break
                }
            }
            if (matchedReserved) {
                continue
            }

            if (rnd[4] == 0.toByte() && rnd[5] == 0.toByte() && rnd[6] == 0.toByte() && rnd[7] == 0.toByte()) {
                continue
            }
            break
        }

        val rndBytes = rnd.copyOf()
        val encKey = rndBytes.copyOfRange(Constants.SKIP_LEN, Constants.SKIP_LEN + Constants.PREKEY_LEN)
        val encIv = rndBytes.copyOfRange(
            Constants.SKIP_LEN + Constants.PREKEY_LEN,
            Constants.SKIP_LEN + Constants.PREKEY_LEN + Constants.IV_LEN
        )

        val encryptor = createAesCtrCipher(encKey, encIv, Cipher.ENCRYPT_MODE)

        val tailPlain = ByteArray(8)
        System.arraycopy(protoTag, 0, tailPlain, 0, 4)
        tailPlain[4] = (dcIdx.toInt() and 0xFF).toByte()
        tailPlain[5] = ((dcIdx.toInt() shr 8) and 0xFF).toByte()
        val rnd2 = ByteArray(2)
        secureRandom.nextBytes(rnd2)
        tailPlain[6] = rnd2[0]
        tailPlain[7] = rnd2[1]

        val encryptedFull = encryptor.update(rndBytes) ?: ByteArray(0)
        val keystreamTail = ByteArray(8)
        for (i in 0 until 8) {
            keystreamTail[i] = (encryptedFull[56 + i].toInt() xor rndBytes[56 + i].toInt()).toByte()
        }

        val encryptedTail = ByteArray(8)
        for (i in 0 until 8) {
            encryptedTail[i] = (tailPlain[i].toInt() xor keystreamTail[i].toInt()).toByte()
        }

        System.arraycopy(encryptedTail, 0, rnd, Constants.PROTO_TAG_POS, 8)
        return rnd
    }

    fun buildCryptoCtx(
        clientDecPrekeyIv: ByteArray,
        secret: ByteArray,
        relayInit: ByteArray
    ): CryptoCtx {
        val cltDecPrekey = clientDecPrekeyIv.copyOfRange(0, Constants.PREKEY_LEN)
        val cltDecIv = clientDecPrekeyIv.copyOfRange(
            Constants.PREKEY_LEN,
            Constants.PREKEY_LEN + Constants.IV_LEN
        )
        val cltDecKey = sha256(cltDecPrekey, secret)

        val cltEncPrekeyIv = clientDecPrekeyIv.reversedArray()
        val cltEncKey = sha256(
            cltEncPrekeyIv.copyOfRange(0, Constants.PREKEY_LEN),
            secret
        )
        val cltEncIv = cltEncPrekeyIv.copyOfRange(
            Constants.PREKEY_LEN,
            Constants.PREKEY_LEN + Constants.IV_LEN
        )

        val cltDecryptor = createAesCtrCipher(cltDecKey, cltDecIv, Cipher.DECRYPT_MODE)
        val cltEncryptor = createAesCtrCipher(cltEncKey, cltEncIv, Cipher.ENCRYPT_MODE)

        // Fast-forward client decryptor past the 64-byte init
        cltDecryptor.update(Constants.ZERO_64)

        // Relay side: standard obfuscation (no secret hash, raw key)
        val relayEncKey = relayInit.copyOfRange(
            Constants.SKIP_LEN,
            Constants.SKIP_LEN + Constants.PREKEY_LEN
        )
        val relayEncIv = relayInit.copyOfRange(
            Constants.SKIP_LEN + Constants.PREKEY_LEN,
            Constants.SKIP_LEN + Constants.PREKEY_LEN + Constants.IV_LEN
        )

        val relayDecPrekeyIv = relayInit.copyOfRange(
            Constants.SKIP_LEN,
            Constants.SKIP_LEN + Constants.PREKEY_LEN + Constants.IV_LEN
        ).reversedArray()
        val relayDecKey = relayDecPrekeyIv.copyOfRange(0, Constants.KEY_LEN)
        val relayDecIv = relayDecPrekeyIv.copyOfRange(Constants.KEY_LEN, Constants.KEY_LEN + Constants.IV_LEN)

        val tgEncryptor = createAesCtrCipher(relayEncKey, relayEncIv, Cipher.ENCRYPT_MODE)
        val tgDecryptor = createAesCtrCipher(relayDecKey, relayDecIv, Cipher.DECRYPT_MODE)

        // Fast-forward upstream encryptor past 64-byte init
        tgEncryptor.update(Constants.ZERO_64)

        return CryptoCtx(
            cltDec = cltDecryptor,
            cltEnc = cltEncryptor,
            tgEnc = tgEncryptor,
            tgDec = tgDecryptor
        )
    }
}

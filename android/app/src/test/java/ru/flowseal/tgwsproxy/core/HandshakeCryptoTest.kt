package ru.flowseal.tgwsproxy.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HandshakeCryptoTest {

    private val random = SecureRandom()

    private fun generateClientHandshake(protoTag: ByteArray, dcIdx: Short, secret: ByteArray): ByteArray {
        val rnd = ByteArray(Constants.HANDSHAKE_LEN)
        random.nextBytes(rnd)
        val prekey = rnd.copyOfRange(Constants.SKIP_LEN, Constants.SKIP_LEN + Constants.PREKEY_LEN)
        val iv = rnd.copyOfRange(
            Constants.SKIP_LEN + Constants.PREKEY_LEN,
            Constants.SKIP_LEN + Constants.PREKEY_LEN + Constants.IV_LEN
        )
        val decKey = MtprotoCrypto.sha256(prekey, secret)
        val encryptor = MtprotoCrypto.createAesCtrCipher(decKey, iv)

        val tailPlain = ByteArray(8)
        System.arraycopy(protoTag, 0, tailPlain, 0, 4)
        tailPlain[4] = (dcIdx.toInt() and 0xFF).toByte()
        tailPlain[5] = ((dcIdx.toInt() shr 8) and 0xFF).toByte()
        tailPlain[6] = 0x11
        tailPlain[7] = 0x22

        val encryptedFull = encryptor.update(rnd) ?: ByteArray(0)
        val keystreamTail = ByteArray(8)
        for (i in 0 until 8) {
            keystreamTail[i] = (encryptedFull[56 + i].toInt() xor rnd[56 + i].toInt()).toByte()
        }
        val encryptedTail = ByteArray(8)
        for (i in 0 until 8) {
            encryptedTail[i] = (tailPlain[i].toInt() xor keystreamTail[i].toInt()).toByte()
        }
        System.arraycopy(encryptedTail, 0, rnd, Constants.PROTO_TAG_POS, 8)
        return rnd
    }

    @Test
    fun testHandshakeSuccessNonMedia() {
        val secret = ByteArray(16) { it.toByte() }
        val handshake = generateClientHandshake(Constants.PROTO_TAG_ABRIDGED, 2, secret)

        val result = MtprotoCrypto.tryHandshake(handshake, secret)
        assertNotNull(result)
        assertEquals(2, result!!.dcId)
        assertFalse(result.isMedia)
        assertArrayEquals(Constants.PROTO_TAG_ABRIDGED, result.protoTag)
    }

    @Test
    fun testHandshakeSuccessMedia() {
        val secret = ByteArray(16) { 0xEE.toByte() }
        // DC 4, media = -4
        val handshake = generateClientHandshake(Constants.PROTO_TAG_INTERMEDIATE, -4, secret)

        val result = MtprotoCrypto.tryHandshake(handshake, secret)
        assertNotNull(result)
        assertEquals(4, result!!.dcId)
        assertTrue(result.isMedia)
        assertArrayEquals(Constants.PROTO_TAG_INTERMEDIATE, result.protoTag)
    }

    @Test
    fun testHandshakeWrongSecretFails() {
        val secret = ByteArray(16) { 0xAA.toByte() }
        val wrongSecret = ByteArray(16) { 0xBB.toByte() }
        val handshake = generateClientHandshake(Constants.PROTO_TAG_ABRIDGED, 2, secret)

        val result = MtprotoCrypto.tryHandshake(handshake, wrongSecret)
        assertNull(result)
    }

    @Test
    fun testGenerateRelayInitDecryptionByServer() {
        val protoTag = Constants.PROTO_TAG_SECURE
        val dcIdx: Short = -2 // DC 2 media

        val relayInit = MtprotoCrypto.generateRelayInit(protoTag, dcIdx)
        assertEquals(Constants.HANDSHAKE_LEN, relayInit.size)

        // Verify reserved constraints
        assertFalse(Constants.RESERVED_FIRST_BYTES.contains(relayInit[0]))

        // Telegram server verifies and decrypts relay init:
        val encKey = relayInit.copyOfRange(Constants.SKIP_LEN, Constants.SKIP_LEN + Constants.PREKEY_LEN)
        val encIv = relayInit.copyOfRange(
            Constants.SKIP_LEN + Constants.PREKEY_LEN,
            Constants.SKIP_LEN + Constants.PREKEY_LEN + Constants.IV_LEN
        )

        val serverDecryptor = Cipher.getInstance("AES/CTR/NoPadding")
        serverDecryptor.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(encIv))
        val decrypted = serverDecryptor.update(relayInit) ?: ByteArray(0)

        // Verify tail at offset 56
        val decryptedProto = decrypted.copyOfRange(Constants.PROTO_TAG_POS, Constants.PROTO_TAG_POS + 4)
        assertArrayEquals(protoTag, decryptedProto)

        val decryptedDcIdx = ByteBuffer.wrap(decrypted, Constants.DC_IDX_POS, 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .short
        assertEquals(dcIdx, decryptedDcIdx)
    }

    @Test
    fun testCryptoCtxBidirectionalReencryption() {
        val secret = ByteArray(16) { 0x12.toByte() }
        val clientPrekeyIv = ByteArray(Constants.PREKEY_LEN + Constants.IV_LEN) { it.toByte() }
        val relayInit = MtprotoCrypto.generateRelayInit(Constants.PROTO_TAG_ABRIDGED, 2)

        val ctx = MtprotoCrypto.buildCryptoCtx(clientPrekeyIv, secret, relayInit)

        // Test Client -> TG stream
        // Telegram client sends plaintext:
        // Plaintext is encrypted by client with clt_dec's corresponding encryptor
        val cltDecKey = MtprotoCrypto.sha256(clientPrekeyIv.copyOfRange(0, Constants.PREKEY_LEN), secret)
        val cltDecIv = clientPrekeyIv.copyOfRange(Constants.PREKEY_LEN, Constants.PREKEY_LEN + Constants.IV_LEN)
        val fakeClientEnc = Cipher.getInstance("AES/CTR/NoPadding")
        fakeClientEnc.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cltDecKey, "AES"), IvParameterSpec(cltDecIv))
        // Client encryptor also skips 64 bytes init:
        fakeClientEnc.update(Constants.ZERO_64)

        val payload = "Hello MTProto over WebSocket!".toByteArray(Charsets.UTF_8)
        val encryptedByClient = fakeClientEnc.update(payload) ?: ByteArray(0)

        // Proxy decrypts with cltDec:
        val decryptedByProxy = ctx.cltDec.update(encryptedByClient) ?: ByteArray(0)
        assertArrayEquals(payload, decryptedByProxy)

        // Proxy encrypts with tgEnc:
        val encryptedForTg = ctx.tgEnc.update(decryptedByProxy) ?: ByteArray(0)

        // TG server decryptor (raw key from relay_init):
        val relayEncKey = relayInit.copyOfRange(Constants.SKIP_LEN, Constants.SKIP_LEN + Constants.PREKEY_LEN)
        val relayEncIv = relayInit.copyOfRange(
            Constants.SKIP_LEN + Constants.PREKEY_LEN,
            Constants.SKIP_LEN + Constants.PREKEY_LEN + Constants.IV_LEN
        )
        val fakeTgServerDec = Cipher.getInstance("AES/CTR/NoPadding")
        fakeTgServerDec.init(Cipher.DECRYPT_MODE, SecretKeySpec(relayEncKey, "AES"), IvParameterSpec(relayEncIv))
        fakeTgServerDec.update(Constants.ZERO_64)

        val decryptedByTg = fakeTgServerDec.update(encryptedForTg) ?: ByteArray(0)
        assertArrayEquals(payload, decryptedByTg)
    }
}

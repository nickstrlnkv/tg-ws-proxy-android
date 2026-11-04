package ru.flowseal.tgwsproxy.core

import org.junit.Assert.*
import org.junit.Test

class MsgSplitterTest {

    @Test
    fun testAbridgedSingleByteHeader() {
        val splitter = MsgSplitter(Constants.PROTO_ABRIDGED_INT)
        val payloadLenWords = 5
        val payloadLenBytes = payloadLenWords * 4 // 20 bytes
        val packet = ByteArray(1 + payloadLenBytes)
        packet[0] = payloadLenWords.toByte()
        for (i in 1 until packet.size) {
            packet[i] = (i and 0xFF).toByte()
        }

        // Cipher and plain are identical for this framing test
        val parts = splitter.split(packet, packet)
        assertEquals(1, parts.size)
        assertArrayEquals(packet, parts[0])
    }

    @Test
    fun testAbridgedMultiByteHeader() {
        val splitter = MsgSplitter(Constants.PROTO_ABRIDGED_INT)
        val payloadWords = 300 // requires 3 bytes ( > 126 )
        val payloadBytes = payloadWords * 4 // 1200 bytes
        val packet = ByteArray(4 + payloadBytes)
        packet[0] = 0x7F.toByte()
        packet[1] = (payloadWords and 0xFF).toByte()
        packet[2] = ((payloadWords shr 8) and 0xFF).toByte()
        packet[3] = ((payloadWords shr 16) and 0xFF).toByte()
        for (i in 4 until packet.size) {
            packet[i] = 0xAA.toByte()
        }

        val parts = splitter.split(packet, packet)
        assertEquals(1, parts.size)
        assertEquals(4 + payloadBytes, parts[0].size)
        assertArrayEquals(packet, parts[0])
    }

    @Test
    fun testIntermediateFraming() {
        val splitter = MsgSplitter(Constants.PROTO_INTERMEDIATE_INT)
        val payloadLen = 64
        val packet = ByteArray(4 + payloadLen)
        packet[0] = (payloadLen and 0xFF).toByte()
        packet[1] = ((payloadLen shr 8) and 0xFF).toByte()
        packet[2] = ((payloadLen shr 16) and 0xFF).toByte()
        packet[3] = ((payloadLen shr 24) and 0xFF).toByte()
        for (i in 4 until packet.size) {
            packet[i] = (i and 0xFF).toByte()
        }

        val parts = splitter.split(packet, packet)
        assertEquals(1, parts.size)
        assertArrayEquals(packet, parts[0])
    }

    @Test
    fun testFragmentedArrival() {
        val splitter = MsgSplitter(Constants.PROTO_ABRIDGED_INT)
        val payloadWords = 10
        val payloadBytes = payloadWords * 4 // 40 bytes
        val packet = ByteArray(1 + payloadBytes)
        packet[0] = payloadWords.toByte()
        for (i in 1 until packet.size) {
            packet[i] = (i * 3).toByte()
        }

        // Send first 15 bytes
        val part1 = splitter.split(packet.copyOfRange(0, 15), packet.copyOfRange(0, 15))
        assertTrue(part1.isEmpty())

        // Send remaining 26 bytes
        val part2 = splitter.split(packet.copyOfRange(15, packet.size), packet.copyOfRange(15, packet.size))
        assertEquals(1, part2.size)
        assertArrayEquals(packet, part2[0])
    }

    @Test
    fun testConcatenatedPackets() {
        val splitter = MsgSplitter(Constants.PROTO_ABRIDGED_INT)

        // Packet 1 (1 + 8 = 9 bytes)
        val pkt1 = ByteArray(1 + 8)
        pkt1[0] = 2 // 2 * 4 = 8 bytes
        for (i in 1..8) pkt1[i] = 1

        // Packet 2 (1 + 16 = 17 bytes)
        val pkt2 = ByteArray(1 + 16)
        pkt2[0] = 4 // 4 * 4 = 16 bytes
        for (i in 1..16) pkt2[i] = 2

        val combined = pkt1 + pkt2
        val parts = splitter.split(combined, combined)
        assertEquals(2, parts.size)
        assertArrayEquals(pkt1, parts[0])
        assertArrayEquals(pkt2, parts[1])
    }

    @Test
    fun testFlushReturnsTail() {
        val splitter = MsgSplitter(Constants.PROTO_ABRIDGED_INT)
        val incomplete = byteArrayOf(0x05, 0x01, 0x02) // Header says 20 bytes payload, but only 2 given
        val parts = splitter.split(incomplete, incomplete)
        assertTrue(parts.isEmpty())

        val flushed = splitter.flush()
        assertEquals(1, flushed.size)
        assertArrayEquals(incomplete, flushed[0])
        assertTrue(splitter.flush().isEmpty())
    }
}

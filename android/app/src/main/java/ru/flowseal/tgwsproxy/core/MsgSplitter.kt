package ru.flowseal.tgwsproxy.core

class ByteChunkBuffer(initialCapacity: Int = 8192) {
    private var data = ByteArray(initialCapacity)
    var size: Int = 0
        private set

    fun append(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        if (length <= 0) return
        ensureCapacity(size + length)
        System.arraycopy(bytes, offset, data, size, length)
        size += length
    }

    operator fun get(index: Int): Byte = data[index]

    fun slice(from: Int, to: Int): ByteArray {
        val len = to - from
        val res = ByteArray(len)
        System.arraycopy(data, from, res, 0, len)
        return res
    }

    fun removePrefix(count: Int) {
        if (count >= size) {
            size = 0
        } else if (count > 0) {
            System.arraycopy(data, count, data, 0, size - count)
            size -= count
        }
    }

    fun toByteArray(): ByteArray {
        return slice(0, size)
    }

    fun clear() {
        size = 0
    }

    private fun ensureCapacity(minCapacity: Int) {
        if (minCapacity > data.size) {
            var newCap = data.size * 2
            if (newCap < minCapacity) newCap = minCapacity
            data = data.copyOf(newCap)
        }
    }
}

class MsgSplitter(private val protoInt: Int) {
    private val cipherBuf = ByteChunkBuffer()
    private val plainBuf = ByteChunkBuffer()
    private var disabled = false

    fun split(chunk: ByteArray, plain: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) {
            return emptyList()
        }
        if (disabled) {
            return listOf(chunk)
        }

        cipherBuf.append(chunk)
        plainBuf.append(plain)

        val parts = mutableListOf<ByteArray>()
        var offset = 0
        val bufLen = cipherBuf.size

        while (offset < bufLen) {
            val packetLen = nextPacketLen(offset, bufLen - offset) ?: break
            if (packetLen <= 0) {
                val tail = if (offset == 0) cipherBuf.toByteArray() else cipherBuf.slice(offset, bufLen)
                parts.add(tail)
                offset = bufLen
                disabled = true
                break
            }
            if (offset == 0 && packetLen == bufLen) {
                parts.add(cipherBuf.toByteArray())
            } else {
                parts.add(cipherBuf.slice(offset, offset + packetLen))
            }
            offset += packetLen
        }

        if (offset > 0) {
            cipherBuf.removePrefix(offset)
            plainBuf.removePrefix(offset)
        }

        return parts
    }

    fun flush(): List<ByteArray> {
        if (cipherBuf.size == 0) {
            return emptyList()
        }
        val tail = cipherBuf.toByteArray()
        cipherBuf.clear()
        plainBuf.clear()
        return listOf(tail)
    }

    private fun nextPacketLen(offset: Int, avail: Int): Int? {
        if (avail <= 0) return null
        if (protoInt == Constants.PROTO_ABRIDGED_INT) {
            return nextAbridgedLen(offset, avail)
        }
        if (protoInt == Constants.PROTO_INTERMEDIATE_INT || protoInt == Constants.PROTO_PADDED_INTERMEDIATE_INT) {
            return nextIntermediateLen(offset, avail)
        }
        return 0
    }

    private fun nextAbridgedLen(offset: Int, avail: Int): Int? {
        val first = plainBuf[offset].toInt() and 0xFF
        val payloadLen: Int
        val headerLen: Int
        if (first == 0x7F || first == 0xFF) {
            if (avail < 4) return null
            val b1 = plainBuf[offset + 1].toInt() and 0xFF
            val b2 = plainBuf[offset + 2].toInt() and 0xFF
            val b3 = plainBuf[offset + 3].toInt() and 0xFF
            payloadLen = (b1 or (b2 shl 8) or (b3 shl 16)) * 4
            headerLen = 4
        } else {
            payloadLen = (first and 0x7F) * 4
            headerLen = 1
        }
        if (payloadLen <= 0) return 0
        val packetLen = headerLen + payloadLen
        if (avail < packetLen) return null
        return packetLen
    }

    private fun nextIntermediateLen(offset: Int, avail: Int): Int? {
        if (avail < 4) return null
        val b0 = plainBuf[offset].toLong() and 0xFF
        val b1 = plainBuf[offset + 1].toLong() and 0xFF
        val b2 = plainBuf[offset + 2].toLong() and 0xFF
        val b3 = plainBuf[offset + 3].toLong() and 0xFF
        val raw = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        val payloadLen = (raw and 0x7FFFFFFFL).toInt()
        if (payloadLen <= 0) return 0
        val packetLen = 4 + payloadLen
        if (avail < packetLen) return null
        return packetLen
    }
}

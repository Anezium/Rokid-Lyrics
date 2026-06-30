package com.rokid.lyrics.contracts

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

object BleWireFramer {
    private const val VERSION: Byte = 1
    private const val HEADER_SIZE = 9
    private const val MAX_CHUNK_COUNT = 0xffff

    fun encode(message: String, messageId: Int, maxPacketSize: Int): List<ByteArray> {
        val bytes = (message + "\n").toByteArray(StandardCharsets.UTF_8)
        val payloadSize = (maxPacketSize - HEADER_SIZE).coerceAtLeast(1)
        val chunkCount = ((bytes.size + payloadSize - 1) / payloadSize).coerceAtLeast(1)
        if (chunkCount > MAX_CHUNK_COUNT) return emptyList()

        return (0 until chunkCount).map { index ->
            val start = index * payloadSize
            val end = minOf(start + payloadSize, bytes.size)
            ByteArray(HEADER_SIZE + end - start).also { frame ->
                frame[0] = VERSION
                frame[1] = ((messageId ushr 24) and 0xff).toByte()
                frame[2] = ((messageId ushr 16) and 0xff).toByte()
                frame[3] = ((messageId ushr 8) and 0xff).toByte()
                frame[4] = (messageId and 0xff).toByte()
                frame[5] = ((index ushr 8) and 0xff).toByte()
                frame[6] = (index and 0xff).toByte()
                frame[7] = ((chunkCount ushr 8) and 0xff).toByte()
                frame[8] = (chunkCount and 0xff).toByte()
                System.arraycopy(bytes, start, frame, HEADER_SIZE, end - start)
            }
        }
    }

    class Reassembler {
        private data class PendingMessage(
            val chunkCount: Int,
            val chunks: Array<ByteArray?>,
        )

        private val pending = mutableMapOf<Int, PendingMessage>()

        fun accept(frame: ByteArray): String? {
            if (frame.size < HEADER_SIZE || frame[0] != VERSION) return null

            val messageId =
                ((frame[1].toInt() and 0xff) shl 24) or
                    ((frame[2].toInt() and 0xff) shl 16) or
                    ((frame[3].toInt() and 0xff) shl 8) or
                    (frame[4].toInt() and 0xff)
            val chunkIndex = ((frame[5].toInt() and 0xff) shl 8) or (frame[6].toInt() and 0xff)
            val chunkCount = ((frame[7].toInt() and 0xff) shl 8) or (frame[8].toInt() and 0xff)
            if (chunkCount <= 0 || chunkIndex !in 0 until chunkCount) return null

            val entry = pending[messageId] ?: PendingMessage(
                chunkCount = chunkCount,
                chunks = arrayOfNulls(chunkCount),
            )
            if (entry.chunkCount != chunkCount) {
                pending.remove(messageId)
                return null
            }

            entry.chunks[chunkIndex] = frame.copyOfRange(HEADER_SIZE, frame.size)
            pending[messageId] = entry
            if (entry.chunks.any { it == null }) return null

            pending.remove(messageId)
            val output = ByteArrayOutputStream()
            entry.chunks.forEach { chunk ->
                output.write(chunk ?: ByteArray(0))
            }
            return output.toByteArray()
                .toString(StandardCharsets.UTF_8)
                .trimEnd('\r', '\n')
        }

        fun clear() {
            pending.clear()
        }
    }
}

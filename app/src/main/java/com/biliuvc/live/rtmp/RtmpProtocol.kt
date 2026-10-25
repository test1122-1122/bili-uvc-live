package com.biliuvc.live.rtmp

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import kotlin.math.min

/** RTMP message type ids (see RTMP specification 5.4). */
internal object RtmpMessageType {
    const val SET_CHUNK_SIZE = 1
    const val ABORT = 2
    const val ACKNOWLEDGEMENT = 3
    const val USER_CONTROL = 4
    const val WINDOW_ACK_SIZE = 5
    const val SET_PEER_BANDWIDTH = 6
    const val AUDIO = 8
    const val VIDEO = 9
    const val DATA_AMF3 = 15
    const val SHARED_OBJECT_AMF3 = 16
    const val COMMAND_AMF3 = 17
    const val DATA_AMF0 = 18
    const val SHARED_OBJECT_AMF0 = 19
    const val COMMAND_AMF0 = 20
    const val AGGREGATE = 22
}

/** Chunk stream ids used by the publisher. */
internal object RtmpChunkStreamId {
    const val PROTOCOL_CONTROL = 2
    const val COMMAND = 3
    const val DATA = 4
    const val AUDIO = 5
    const val VIDEO = 6
}

/** User control event types. */
internal object RtmpUserControlEvent {
    const val STREAM_BEGIN = 0
    const val STREAM_EOF = 1
    const val STREAM_DRY = 2
    const val SET_BUFFER_LENGTH = 3
    const val STREAM_IS_RECORDED = 4
    const val PING_REQUEST = 6
    const val PING_RESPONSE = 7
}

/** A completely reassembled inbound RTMP message. */
internal class RtmpIncomingMessage(
    val type: Int,
    val messageStreamId: Int,
    val timestamp: Long,
    val payload: ByteArray
)

/** Writes plain RTMP chunk stream messages (fmt0 header + fmt3 continuation chunks). */
internal object RtmpChunks {

    /**
     * Writes one RTMP message as a fmt0 chunk followed by fmt3 continuation chunks.
     * The caller is responsible for serialising concurrent writes.
     */
    fun writeMessage(
        out: OutputStream,
        chunkSize: Int,
        chunkStreamId: Int,
        messageType: Int,
        messageStreamId: Int,
        timestampMs: Long,
        payload: ByteArray
    ) {
        require(chunkStreamId in 2..63) { "unsupported chunk stream id $chunkStreamId" }
        val effectiveChunkSize = if (chunkSize <= 0) 128 else chunkSize
        val useExtendedTimestamp = timestampMs >= 0xFFFFFFL
        val timestampField = if (useExtendedTimestamp) 0xFFFFFF else timestampMs.toInt()
        val extended = (timestampMs and 0xFFFFFFFFL).toInt()

        // Basic header: fmt0, chunk stream id (always < 64 here).
        out.write(chunkStreamId)
        // Message header (fmt0): timestamp, length, type, message stream id.
        writeUInt24(out, timestampField)
        writeUInt24(out, payload.size)
        out.write(messageType)
        writeUInt32LittleEndian(out, messageStreamId)
        if (useExtendedTimestamp) writeUInt32(out, extended)

        var offset = 0
        while (true) {
            val count = min(effectiveChunkSize, payload.size - offset)
            if (count > 0) out.write(payload, offset, count)
            offset += count
            if (offset >= payload.size) break
            // Continuation chunk: fmt3 basic header, extended timestamp repeated when used.
            out.write(0xC0 or chunkStreamId)
            if (useExtendedTimestamp) writeUInt32(out, extended)
        }
    }

    fun writeUInt24(out: OutputStream, value: Int) {
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    fun writeUInt32(out: OutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    fun writeUInt32LittleEndian(out: OutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }
}

/**
 * Incremental RTMP chunk stream reader. Handles fmt0..fmt3 headers, 1/2/3 byte basic headers,
 * extended timestamps and messages split over an arbitrary number of chunks.
 *
 * Read helpers retry on [SocketTimeoutException] so `SO_TIMEOUT` can be used to observe shutdown
 * requests without corrupting the stream state (partial reads are only committed when complete).
 */
internal class RtmpChunkReader(
    private val input: InputStream,
    private val shouldStop: () -> Boolean
) {

    /** Current inbound chunk size, updated by the caller when a SetChunkSize message arrives. */
    var chunkSize: Int = 128

    private class HeaderInfo(
        val timestamp: Long,
        val length: Int,
        val messageType: Int,
        val messageStreamId: Int,
        val extendedTimestamp: Boolean
    )

    private class PartialMessage(val header: HeaderInfo, var filled: Int)

    private val lastHeaders = HashMap<Int, HeaderInfo>()
    private val partialPayloads = HashMap<Int, ByteArray>()
    private val partialMessages = HashMap<Int, PartialMessage>()

    /**
     * Reads the next complete message.
     * @return the message, or `null` when the stream ended / shutdown was requested.
     */
    fun readMessage(): RtmpIncomingMessage? {
        while (true) {
            if (shouldStop()) return null

            val basicHeader = readBasicHeader()
            val format = basicHeader.format
            val chunkStreamId = basicHeader.chunkStreamId

            val header: HeaderInfo
            when (format) {
                0 -> {
                    var timestamp = readUInt24()
                    val length = readUInt24()
                    val messageType = readByte()
                    val messageStreamId = readUInt32LittleEndian()
                    val extended = timestamp == 0xFFFFFF
                    if (extended) timestamp = readUInt32()
                    header = HeaderInfo(timestamp.toLong() and 0xFFFFFFFFL, length, messageType, messageStreamId, extended)
                }
                1 -> {
                    val previous = lastHeaders[chunkStreamId]
                        ?: throw IOException("fmt1 chunk without previous header (csid=$chunkStreamId)")
                    var delta = readUInt24()
                    val length = readUInt24()
                    val messageType = readByte()
                    val extended = delta == 0xFFFFFF
                    if (extended) delta = readUInt32()
                    header = HeaderInfo(
                        previous.timestamp + (delta.toLong() and 0xFFFFFFFFL),
                        length,
                        messageType,
                        previous.messageStreamId,
                        extended
                    )
                }
                2 -> {
                    val previous = lastHeaders[chunkStreamId]
                        ?: throw IOException("fmt2 chunk without previous header (csid=$chunkStreamId)")
                    var delta = readUInt24()
                    val extended = delta == 0xFFFFFF
                    if (extended) delta = readUInt32()
                    header = HeaderInfo(
                        previous.timestamp + (delta.toLong() and 0xFFFFFFFFL),
                        previous.length,
                        previous.messageType,
                        previous.messageStreamId,
                        extended
                    )
                }
                else -> {
                    val previous = lastHeaders[chunkStreamId]
                        ?: throw IOException("fmt3 chunk without previous header (csid=$chunkStreamId)")
                    // When the previous header used an extended timestamp, every chunk repeats it.
                    if (previous.extendedTimestamp) readUInt32()
                    header = previous
                }
            }

            lastHeaders[chunkStreamId] = header

            if (format == 3 && partialMessages[chunkStreamId] == null) {
                // A fmt3 chunk may also start a brand new message with the same header values
                // (and timestamp) only when the previous message already completed. Such a
                // message carries no payload delta, so treat it as a zero-length message.
                if (header.length == 0) {
                    return RtmpIncomingMessage(header.messageType, header.messageStreamId, header.timestamp, ByteArray(0))
                }
                partialPayloads[chunkStreamId] = ByteArray(header.length)
                partialMessages[chunkStreamId] = PartialMessage(header, 0)
            } else if (format != 3) {
                partialPayloads[chunkStreamId] = ByteArray(header.length)
                partialMessages[chunkStreamId] = PartialMessage(header, 0)
            }

            val partial = partialMessages[chunkStreamId]
            val payload = partialPayloads[chunkStreamId]
            if (partial == null || payload == null) {
                throw IOException("missing chunk state for csid=$chunkStreamId")
            }

            val toRead = min(chunkSize, partial.header.length - partial.filled).coerceAtLeast(0)
            if (toRead > 0) {
                readFully(payload, partial.filled, toRead)
                partial.filled += toRead
            }
            if (partial.filled >= partial.header.length) {
                partialMessages.remove(chunkStreamId)
                partialPayloads.remove(chunkStreamId)
                return RtmpIncomingMessage(
                    partial.header.messageType,
                    partial.header.messageStreamId,
                    partial.header.timestamp,
                    payload
                )
            }
            // Otherwise loop and read the next chunk header (usually fmt3).
        }
    }

    private class BasicHeader(val format: Int, val chunkStreamId: Int)

    private fun readBasicHeader(): BasicHeader {
        val first = readByte()
        val format = (first ushr 6) and 0x03
        var chunkStreamId = first and 0x3F
        if (chunkStreamId == 0) {
            chunkStreamId = 64 + readByte()
        } else if (chunkStreamId == 1) {
            chunkStreamId = 64 + readByte() + (readByte() shl 8)
        }
        return BasicHeader(format, chunkStreamId)
    }

    private fun readByte(): Int {
        while (true) {
            try {
                val value = input.read()
                if (value < 0) throw EOFException("rtmp stream closed")
                return value and 0xFF
            } catch (timeout: SocketTimeoutException) {
                if (shouldStop()) throw EOFException("rtmp read aborted")
            }
        }
    }

    private fun readFully(buffer: ByteArray, offset: Int, length: Int) {
        var read = 0
        while (read < length) {
            while (true) {
                try {
                    val count = input.read(buffer, offset + read, length - read)
                    if (count < 0) throw EOFException("rtmp stream closed")
                    read += count
                    break
                } catch (timeout: SocketTimeoutException) {
                    if (shouldStop()) throw EOFException("rtmp read aborted")
                }
            }
        }
    }

    private fun readUInt24(): Int = (readByte() shl 16) or (readByte() shl 8) or readByte()

    private fun readUInt32(): Int = (readByte() shl 24) or (readByte() shl 16) or (readByte() shl 8) or readByte()

    private fun readUInt32LittleEndian(): Int =
        readByte() or (readByte() shl 8) or (readByte() shl 16) or (readByte() shl 24)
}

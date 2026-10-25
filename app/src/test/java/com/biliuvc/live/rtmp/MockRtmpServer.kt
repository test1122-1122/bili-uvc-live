package com.biliuvc.live.rtmp

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.min

/** One AMF0 command received by the mock server. */
internal data class MockCommand(
    val name: String,
    val transactionId: Double?,
    val values: List<AmfValue>,
    val messageStreamId: Int,
    val timestamp: Long
) {
    fun amfObject(): AmfObject? = values.filterIsInstance<AmfObject>().firstOrNull()

    fun stringAt(index: Int): String? = (values.getOrNull(index) as? AmfString)?.value
}

/** One FLV media / data message received by the mock server. */
internal data class MockMediaMessage(
    val type: Int,
    val timestamp: Long,
    val messageStreamId: Int,
    val payload: ByteArray,
    val chunkCount: Int
)

/**
 * Minimal RTMP server used by the unit tests. Pure JVM (ServerSocket on loopback), no network
 * access beyond localhost.
 *
 * It performs the simple handshake, parses chunked AMF0 commands and FLV media messages, answers
 * `connect` / `createStream` / `publish`, and optionally sends ping / window acknowledgement control
 * messages to exercise the client's control-message handling.
 */
internal class MockRtmpServer(
    private val acceptPublish: Boolean = true,
    private val sendWindowAckSize: Boolean = true,
    private val sendPingRequest: Boolean = true
) : Closeable {

    private val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))

    val port: Int get() = serverSocket.localPort

    val commands = CopyOnWriteArrayList<MockCommand>()
    val media = CopyOnWriteArrayList<MockMediaMessage>()

    /** type -> payload for protocol control messages sent by the client (1, 3, 4, 5, 6). */
    val protocolMessages = CopyOnWriteArrayList<Pair<Int, ByteArray>>()

    val handshakeDone = CountDownLatch(1)
    val publishReceived = CountDownLatch(1)
    val pongReceived = CountDownLatch(1)
    val ackReceived = CountDownLatch(1)

    @Volatile
    var failure: Throwable? = null
        private set

    @Volatile
    private var running = true

    @Volatile
    private var clientSocket: Socket? = null

    private val serverThread = Thread({ runServer() }, "mock-rtmp-server").apply {
        isDaemon = true
        start()
    }

    fun awaitHandshake(timeoutMs: Long = 5_000): Boolean = handshakeDone.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun awaitPublish(timeoutMs: Long = 5_000): Boolean = publishReceived.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun awaitPong(timeoutMs: Long = 5_000): Boolean = pongReceived.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun awaitAck(timeoutMs: Long = 5_000): Boolean = ackReceived.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun videoMessages(): List<MockMediaMessage> = media.filter { it.type == 9 }

    fun audioMessages(): List<MockMediaMessage> = media.filter { it.type == 8 }

    fun dataMessages(): List<MockMediaMessage> = media.filter { it.type == 18 }

    override fun close() {
        running = false
        try {
            clientSocket?.close()
        } catch (ignored: Throwable) {
        }
        try {
            serverSocket.close()
        } catch (ignored: Throwable) {
        }
        try {
            serverThread.join(2_000)
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ---- server loop ----------------------------------------------------------------------

    private fun runServer() {
        try {
            val client = serverSocket.accept()
            clientSocket = client
            client.use { socket ->
                socket.tcpNoDelay = true
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())
                performHandshake(input, output)
                handshakeDone.countDown()

                if (sendWindowAckSize) {
                    // window of 1 byte forces an Acknowledgement from the client quickly
                    writeMessage(output, 2, RtmpMessageType.WINDOW_ACK_SIZE, 0, 0L, uint32(1))
                }
                if (sendPingRequest) {
                    writeMessage(
                        output,
                        2,
                        RtmpMessageType.USER_CONTROL,
                        0,
                        0L,
                        byteArrayOf(0, RtmpUserControlEvent.PING_REQUEST.toByte(), 0x01, 0x02, 0x03, 0x04)
                    )
                }

                val reader = MockChunkReader(input)
                while (running) {
                    val message = reader.readMessage() ?: break
                    handleMessage(reader, message, output)
                }
            }
        } catch (t: Throwable) {
            if (running) failure = t
        }
    }

    private fun performHandshake(input: InputStream, output: OutputStream) {
        val c0 = readFully(input, 1)[0].toInt() and 0xFF
        check(c0 == 3) { "unexpected RTMP handshake version: $c0" }
        val c1 = readFully(input, 1536)
        val s1 = ByteArray(1536)
        for (i in 8 until s1.size) s1[i] = ((i * 31) and 0xFF).toByte()
        val s2 = c1.copyOf()
        synchronized(output) {
            output.write(3)
            output.write(s1)
            output.write(s2)
            output.flush()
        }
        readFully(input, 1536) // C2
    }

    private fun handleMessage(reader: MockChunkReader, message: MockIncomingMessage, output: OutputStream) {
        when (message.type) {
            RtmpMessageType.SET_CHUNK_SIZE -> {
                protocolMessages.add(message.type to message.payload)
                if (message.payload.size >= 4) {
                    val value = readInt32(message.payload, 0) and 0x7FFFFFFF
                    if (value > 0) reader.chunkSize = value
                }
            }
            RtmpMessageType.ACKNOWLEDGEMENT -> {
                protocolMessages.add(message.type to message.payload)
                ackReceived.countDown()
            }
            RtmpMessageType.USER_CONTROL -> {
                protocolMessages.add(message.type to message.payload)
                if (message.payload.size >= 2) {
                    val event = ((message.payload[0].toInt() and 0xFF) shl 8) or (message.payload[1].toInt() and 0xFF)
                    if (event == RtmpUserControlEvent.PING_RESPONSE) pongReceived.countDown()
                }
            }
            RtmpMessageType.WINDOW_ACK_SIZE,
            RtmpMessageType.SET_PEER_BANDWIDTH -> protocolMessages.add(message.type to message.payload)
            RtmpMessageType.COMMAND_AMF0 -> handleCommand(message, output)
            RtmpMessageType.COMMAND_AMF3 -> {
                if (message.payload.size > 1) {
                    val amf0Payload = message.payload.copyOfRange(1, message.payload.size)
                    handleCommand(message.copy(payload = amf0Payload), output)
                }
            }
            RtmpMessageType.AUDIO,
            RtmpMessageType.VIDEO,
            RtmpMessageType.DATA_AMF0 -> media.add(
                MockMediaMessage(
                    type = message.type,
                    timestamp = message.timestamp,
                    messageStreamId = message.messageStreamId,
                    payload = message.payload,
                    chunkCount = message.chunkCount
                )
            )
            else -> Unit
        }
    }

    private fun handleCommand(message: MockIncomingMessage, output: OutputStream) {
        val values = try {
            Amf0.decode(message.payload)
        } catch (t: Throwable) {
            return
        }
        val name = (values.getOrNull(0) as? AmfString)?.value ?: return
        val transactionId = (values.getOrNull(1) as? AmfNumber)?.value
        commands.add(MockCommand(name, transactionId, values, message.messageStreamId, message.timestamp))
        when (name) {
            "connect" -> writeCommandResult(output, transactionId ?: 1.0, connectResultPayload())
            "createStream" -> writeCommandResult(output, transactionId ?: 4.0, listOf(AmfNumber(1.0)))
            "publish" -> {
                publishReceived.countDown()
                if (acceptPublish) {
                    writeOnStatus(output, "status", "NetStream.Publish.Start", "Start publishing")
                } else {
                    writeOnStatus(output, "error", "NetStream.Publish.BadName", "Stream already publishing")
                }
            }
            "releaseStream", "FCPublish", "FCUnpublish", "deleteStream" -> Unit
            else -> Unit
        }
    }

    private fun connectResultPayload(): List<AmfValue> = listOf(
        AmfObject(
            linkedMapOf(
                "fmsVer" to AmfString("FMS/3,0,1,123"),
                "capabilities" to AmfNumber(31.0)
            )
        ),
        AmfObject(
            linkedMapOf(
                "level" to AmfString("status"),
                "code" to AmfString("NetConnection.Connect.Success"),
                "description" to AmfString("Connection succeeded."),
                "objectEncoding" to AmfNumber(0.0)
            )
        )
    )

    private fun writeCommandResult(output: OutputStream, transactionId: Double, extra: List<AmfValue>) {
        val values = ArrayList<AmfValue>(3 + extra.size)
        values.add(AmfString("_result"))
        values.add(AmfNumber(transactionId))
        values.add(AmfNull)
        values.addAll(extra)
        writeMessage(output, 3, RtmpMessageType.COMMAND_AMF0, 0, 0L, Amf0.encodeAll(values))
    }

    private fun writeOnStatus(output: OutputStream, level: String, code: String, description: String) {
        val status = AmfObject(
            linkedMapOf(
                "level" to AmfString(level),
                "code" to AmfString(code),
                "description" to AmfString(description)
            )
        )
        val values = listOf(AmfString("onStatus"), AmfNumber(0.0), AmfNull, status)
        writeMessage(output, 3, RtmpMessageType.COMMAND_AMF0, 0, 0L, Amf0.encodeAll(values))
    }

    /** Writes a message using the server's fixed 128 byte outbound chunk size. */
    private fun writeMessage(
        output: OutputStream,
        chunkStreamId: Int,
        messageType: Int,
        messageStreamId: Int,
        timestamp: Long,
        payload: ByteArray
    ) {
        val chunkSize = 128
        val extended = timestamp >= 0xFFFFFFL
        synchronized(output) {
            output.write(chunkStreamId)
            writeUInt24(output, if (extended) 0xFFFFFF else timestamp.toInt())
            writeUInt24(output, payload.size)
            output.write(messageType)
            writeUInt32LittleEndian(output, messageStreamId)
            if (extended) writeUInt32(output, (timestamp and 0xFFFFFFFFL).toInt())
            var offset = 0
            while (true) {
                val count = min(chunkSize, payload.size - offset)
                if (count > 0) output.write(payload, offset, count)
                offset += count
                if (offset >= payload.size) break
                output.write(0xC0 or chunkStreamId)
                if (extended) writeUInt32(output, (timestamp and 0xFFFFFFFFL).toInt())
            }
            output.flush()
        }
    }

    // ---- independent chunk reader (kept separate from the production implementation) -------

    private data class MockIncomingMessage(
        val type: Int,
        val messageStreamId: Int,
        val timestamp: Long,
        val payload: ByteArray,
        val chunkCount: Int
    )

    private class MockChunkReader(private val input: InputStream) {

        var chunkSize = 128

        private class Header(
            val timestamp: Long,
            val length: Int,
            val type: Int,
            val messageStreamId: Int,
            val extendedTimestamp: Boolean
        )

        private val headers = HashMap<Int, Header>()
        private val payloads = HashMap<Int, ByteArray>()
        private val filled = HashMap<Int, Int>()
        private val chunks = HashMap<Int, Int>()

        fun readMessage(): MockIncomingMessage? {
            while (true) {
                val first = input.read()
                if (first < 0) return null
                val format = (first ushr 6) and 0x03
                var chunkStreamId = first and 0x3F
                if (chunkStreamId == 0) {
                    chunkStreamId = 64 + readByte()
                } else if (chunkStreamId == 1) {
                    chunkStreamId = 64 + readByte() + (readByte() shl 8)
                }

                val header: Header
                when (format) {
                    0 -> {
                        var timestamp = readUInt24()
                        val length = readUInt24()
                        val type = readByte()
                        val messageStreamId = readUInt32LittleEndian()
                        val extended = timestamp == 0xFFFFFF
                        if (extended) timestamp = readUInt32()
                        header = Header(timestamp.toLong() and 0xFFFFFFFFL, length, type, messageStreamId, extended)
                    }
                    1 -> {
                        val previous = headers[chunkStreamId] ?: error("fmt1 without previous header")
                        var delta = readUInt24()
                        val length = readUInt24()
                        val type = readByte()
                        val extended = delta == 0xFFFFFF
                        if (extended) delta = readUInt32()
                        header = Header(
                            previous.timestamp + (delta.toLong() and 0xFFFFFFFFL),
                            length,
                            type,
                            previous.messageStreamId,
                            extended
                        )
                    }
                    2 -> {
                        val previous = headers[chunkStreamId] ?: error("fmt2 without previous header")
                        var delta = readUInt24()
                        val extended = delta == 0xFFFFFF
                        if (extended) delta = readUInt32()
                        header = Header(
                            previous.timestamp + (delta.toLong() and 0xFFFFFFFFL),
                            previous.length,
                            previous.type,
                            previous.messageStreamId,
                            extended
                        )
                    }
                    else -> {
                        val previous = headers[chunkStreamId] ?: error("fmt3 without previous header")
                        if (previous.extendedTimestamp) readUInt32()
                        header = previous
                    }
                }
                headers[chunkStreamId] = header

                val isNewMessage = format != 3 || payloads[chunkStreamId] == null
                if (isNewMessage) {
                    payloads[chunkStreamId] = ByteArray(header.length)
                    filled[chunkStreamId] = 0
                    chunks[chunkStreamId] = 0
                }
                val buffer = payloads[chunkStreamId] ?: error("chunk without message start")
                val alreadyFilled = filled[chunkStreamId] ?: 0
                val toRead = min(chunkSize, header.length - alreadyFilled)
                if (toRead > 0) {
                    readFully(buffer, alreadyFilled, toRead)
                    filled[chunkStreamId] = alreadyFilled + toRead
                }
                chunks[chunkStreamId] = (chunks[chunkStreamId] ?: 0) + 1

                if ((filled[chunkStreamId] ?: 0) >= header.length) {
                    payloads.remove(chunkStreamId)
                    filled.remove(chunkStreamId)
                    val chunkCount = chunks.remove(chunkStreamId) ?: 1
                    return MockIncomingMessage(
                        header.type,
                        header.messageStreamId,
                        header.timestamp,
                        buffer,
                        chunkCount
                    )
                }
            }
        }

        private fun readByte(): Int {
            val value = input.read()
            if (value < 0) throw EOFException("mock server: EOF")
            return value and 0xFF
        }

        private fun readUInt24(): Int = (readByte() shl 16) or (readByte() shl 8) or readByte()

        private fun readUInt32(): Int =
            (readByte() shl 24) or (readByte() shl 16) or (readByte() shl 8) or readByte()

        private fun readUInt32LittleEndian(): Int =
            readByte() or (readByte() shl 8) or (readByte() shl 16) or (readByte() shl 24)

        private fun readFully(buffer: ByteArray, offset: Int, length: Int) {
            var read = 0
            while (read < length) {
                val count = input.read(buffer, offset + read, length - read)
                if (count < 0) throw EOFException("mock server: EOF")
                read += count
            }
        }
    }
}

// ---- helpers shared with the tests ---------------------------------------------------------

internal fun readFully(input: InputStream, count: Int): ByteArray {
    val buffer = ByteArray(count)
    var read = 0
    while (read < count) {
        val n = input.read(buffer, read, count - read)
        if (n < 0) throw EOFException("unexpected EOF")
        read += n
    }
    return buffer
}

internal fun uint32(value: Int): ByteArray = byteArrayOf(
    (value ushr 24).toByte(),
    (value ushr 16).toByte(),
    (value ushr 8).toByte(),
    value.toByte()
)

internal fun readInt32(payload: ByteArray, offset: Int): Int =
    ((payload[offset].toInt() and 0xFF) shl 24) or
        ((payload[offset + 1].toInt() and 0xFF) shl 16) or
        ((payload[offset + 2].toInt() and 0xFF) shl 8) or
        (payload[offset + 3].toInt() and 0xFF)

private fun writeUInt24(output: OutputStream, value: Int) {
    output.write((value ushr 16) and 0xFF)
    output.write((value ushr 8) and 0xFF)
    output.write(value and 0xFF)
}

private fun writeUInt32(output: OutputStream, value: Int) {
    output.write((value ushr 24) and 0xFF)
    output.write((value ushr 16) and 0xFF)
    output.write((value ushr 8) and 0xFF)
    output.write(value and 0xFF)
}

private fun writeUInt32LittleEndian(output: OutputStream, value: Int) {
    output.write(value and 0xFF)
    output.write((value ushr 8) and 0xFF)
    output.write((value ushr 16) and 0xFF)
    output.write((value ushr 24) and 0xFF)
}

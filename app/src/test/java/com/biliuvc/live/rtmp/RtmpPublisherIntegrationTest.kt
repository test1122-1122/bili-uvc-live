package com.biliuvc.live.rtmp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

class RtmpPublisherIntegrationTest {

    private val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E, 0x8C.toByte(), 0x68.toByte())
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
    private val idr = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x00, 0x21)

    private class RecordingListener : RtmpPublisher.Listener {
        val states = CopyOnWriteArrayList<RtmpState>()
        val details = CopyOnWriteArrayList<String?>()
        val statsSnapshots = CopyOnWriteArrayList<RtmpStats>()
        val errors = CopyOnWriteArrayList<String>()

        override fun onState(state: RtmpState, detail: String?) {
            states.add(state)
            details.add(detail)
        }

        override fun onStats(stats: RtmpStats) {
            statsSnapshots.add(stats)
        }

        override fun onError(message: String, cause: Throwable?) {
            errors.add(message)
        }
    }

    private fun annexB(vararg units: ByteArray): ByteArray {
        val out = ByteArray(units.sumOf { 4 + it.size })
        var offset = 0
        for (unit in units) {
            out[offset] = 0
            out[offset + 1] = 0
            out[offset + 2] = 0
            out[offset + 3] = 1
            unit.copyInto(out, offset + 4)
            offset += 4 + unit.size
        }
        return out
    }

    private fun await(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private fun connectAndWait(server: MockRtmpServer, listener: RecordingListener): RtmpPublisher {
        val publisher = RtmpPublisher(listener)
        publisher.connect("rtmp://127.0.0.1:${server.port}/live-bvc/?streamname=live_test_room&key=k3y")
        assertTrue("publish command was not received", server.awaitPublish(8_000))
        assertTrue("publisher did not reach PUBLISHING", await(8_000) { publisher.isPublishing })
        return publisher
    }

    @Test(timeout = 30_000)
    fun connectsPublishesAndSendsBilibiliCommands() {
        MockRtmpServer().use { server ->
            val listener = RecordingListener()
            val publisher = connectAndWait(server, listener)
            try {
                // ---- handshake / connect ----
                assertTrue("handshake not completed", server.awaitHandshake(3_000))
                val connect = server.commands.first { it.name == "connect" }
                val connectObject = connect.amfObject()
                assertNotNull(connectObject)
                assertEquals(
                    "live-bvc",
                    (connectObject!!.properties["app"] as AmfString).value
                )
                assertEquals(
                    "rtmp://127.0.0.1:${server.port}/live-bvc",
                    (connectObject.properties["tcUrl"] as AmfString).value
                )
                assertEquals("nonprivate", (connectObject.properties["type"] as AmfString).value)

                // ---- publish: Bilibili stream name keeps the leading '?' ----
                val publish = server.commands.first { it.name == "publish" }
                assertEquals("?streamname=live_test_room&key=k3y", publish.stringAt(3))
                assertEquals("live", publish.stringAt(4))
                assertEquals(1, publish.messageStreamId)

                // ---- client announced an outbound chunk size of 4096 ----
                val setChunkSize = server.protocolMessages.first { it.first == RtmpMessageType.SET_CHUNK_SIZE }
                assertEquals(4096, readInt32(setChunkSize.second, 0))

                // ---- client sent window acknowledgement size + peer bandwidth ----
                assertTrue(server.protocolMessages.any { it.first == RtmpMessageType.WINDOW_ACK_SIZE })
                assertTrue(server.protocolMessages.any { it.first == RtmpMessageType.SET_PEER_BANDWIDTH })
                assertEquals(2_500_000, readInt32(server.protocolMessages.first { it.first == RtmpMessageType.WINDOW_ACK_SIZE }.second, 0))

                // ---- ping -> pong and ack handling ----
                assertTrue("client did not answer the ping request", server.awaitPong(3_000))
                assertTrue("client did not send an acknowledgement", server.awaitAck(3_000))

                // ---- first video frame: SPS/PPS + IDR, metadata first ----
                publisher.setVideoInfo(1280, 720, 30)
                publisher.setAudioInfo(44_100, 2)
                publisher.sendVideoFrame(annexB(sps, pps, idr), 0L)
                assertTrue(await(5_000) { server.videoMessages().size >= 2 })

                val video = server.videoMessages()
                // AVC sequence header (from SPS/PPS) comes before the NALU frame
                val sequenceHeader = video[0].payload
                assertEquals(0x17, sequenceHeader[0].toInt() and 0xFF)
                assertEquals(0x00, sequenceHeader[1].toInt() and 0xFF)
                val record = sequenceHeader.copyOfRange(5, sequenceHeader.size)
                assertArrayEquals(H264AnnexB.buildAvcConfigRecord(sps, pps), record)

                val pictureFrame = video[1].payload
                assertEquals(0x17, pictureFrame[0].toInt() and 0xFF) // key frame
                assertEquals(0x01, pictureFrame[1].toInt() and 0xFF)
                assertEquals(0, pictureFrame[2].toInt())
                assertEquals(0, pictureFrame[3].toInt())
                assertEquals(0, pictureFrame[4].toInt())
                val naluLength = readInt32(pictureFrame, 5)
                assertEquals(idr.size, naluLength)
                assertArrayEquals(idr, pictureFrame.copyOfRange(9, 9 + idr.size))

                // metadata (@setDataFrame onMetaData) arrives before/with the first video frame
                assertTrue(await(5_000) { server.dataMessages().isNotEmpty() })
                val metadata = Amf0.decode(server.dataMessages()[0].payload)
                assertEquals("@setDataFrame", (metadata[0] as AmfString).value)
                assertEquals("onMetaData", (metadata[1] as AmfString).value)
                val properties = (metadata[2] as AmfEcmaArray).properties
                assertEquals(1280.0, (properties["width"] as AmfNumber).value, 0.0)
                assertEquals(720.0, (properties["height"] as AmfNumber).value, 0.0)
                assertEquals(30.0, (properties["framerate"] as AmfNumber).value, 0.0)
                assertEquals(7.0, (properties["videocodecid"] as AmfNumber).value, 0.0)
                assertEquals(10.0, (properties["audiocodecid"] as AmfNumber).value, 0.0)

                // ---- audio sequence header + raw AAC ----
                publisher.setAudioSpecificConfig(byteArrayOf(0x12, 0x10))
                val rawAac = byteArrayOf(0x21, 0x02, 0x03, 0x04)
                publisher.sendAudioFrame(rawAac, 1_000L)
                assertTrue(await(5_000) { server.audioMessages().size >= 2 })
                val audio = server.audioMessages()
                assertArrayEquals(
                    byteArrayOf(0xAF.toByte(), 0x00, 0x12, 0x10),
                    audio[0].payload
                )
                val expectedRaw = ByteArray(2 + rawAac.size)
                expectedRaw[0] = 0xAF.toByte()
                expectedRaw[1] = 0x01
                rawAac.copyInto(expectedRaw, 2)
                assertArrayEquals(expectedRaw, audio[1].payload)

                // ---- stats ----
                assertTrue(await(3_000) { publisher.stats.videoFramesSent >= 1L })
                assertTrue(await(3_000) { publisher.stats.audioFramesSent >= 1L })
                val stats = publisher.stats
                assertEquals(RtmpState.PUBLISHING, stats.state)
                assertEquals(0L, stats.droppedVideoFrames)
                assertEquals(0L, stats.droppedAudioFrames)
                assertTrue(stats.bytesSent > 0L)
                assertTrue(stats.bitrateBps >= 0L)
                assertNull(stats.lastError)
                assertTrue(listener.errors.isEmpty())
                assertTrue(listener.states.contains(RtmpState.CONNECTING))
                assertTrue(listener.states.contains(RtmpState.CONNECTED))
                assertTrue(listener.states.contains(RtmpState.PUBLISHING))
            } finally {
                publisher.disconnect()
            }

            // ---- disconnect: idempotent, STOPPED, threads stop, frames dropped ----
            assertEquals(RtmpState.STOPPED, publisher.stats.state)
            assertFalse(publisher.isPublishing)
            assertEquals(RtmpState.STOPPED, listener.states.last())
            publisher.sendVideoFrame(annexB(idr), 2_000_000L)
            assertEquals(1L, publisher.stats.droppedVideoFrames)
            publisher.disconnect()
            assertEquals(RtmpState.STOPPED, publisher.stats.state)
        }
    }

    @Test(timeout = 30_000)
    fun usesParameterSetsReceivedBeforePublishing() {
        MockRtmpServer().use { server ->
            val listener = RecordingListener()
            val publisher = RtmpPublisher(listener)
            try {
                publisher.setVideoInfo(1280, 720, 30)
                publisher.setAudioInfo(44_100, 2)

                // The H.264 encoder starts before connect(): its codec-config buffer (SPS+PPS)
                // arrives while the publisher is still IDLE. It must be cached, not dropped.
                publisher.sendVideoFrame(annexB(sps, pps), 0L)
                assertEquals(0L, publisher.stats.droppedVideoFrames)

                // Picture frames produced before PUBLISHING are dropped and counted.
                publisher.sendVideoFrame(annexB(idr), 33_000L)
                assertEquals(1L, publisher.stats.droppedVideoFrames)

                // Audio before publishing is dropped as well.
                publisher.sendAudioFrame(byteArrayOf(0x21, 0x02, 0x03), 0L)
                assertEquals(1L, publisher.stats.droppedAudioFrames)

                publisher.connect("rtmp://127.0.0.1:${server.port}/live-bvc/?streamname=early&key=k")
                assertTrue("publish command was not received", server.awaitPublish(8_000))
                assertTrue(await(8_000) { publisher.isPublishing })

                // First IDR after publish: the cached SPS/PPS must produce the AVC sequence header
                // before the NALU frame even though no SPS/PPS are present in this buffer.
                publisher.sendVideoFrame(annexB(idr), 100_000L)
                assertTrue(await(5_000) { server.videoMessages().size >= 2 })
                val video = server.videoMessages()
                assertEquals(0x17, video[0].payload[0].toInt() and 0xFF)
                assertEquals(0x00, video[0].payload[1].toInt() and 0xFF)
                assertArrayEquals(
                    H264AnnexB.buildAvcConfigRecord(sps, pps),
                    video[0].payload.copyOfRange(5, video[0].payload.size)
                )
                assertEquals(0x17, video[1].payload[0].toInt() and 0xFF)
                assertEquals(0x01, video[1].payload[1].toInt() and 0xFF)

                // AAC sequence header is derived from setAudioInfo even though the frames sent
                // before publishing were dropped: the header is generated with the first frame.
                publisher.sendAudioFrame(byteArrayOf(0x31, 0x02, 0x03), 200_000L)
                assertTrue(await(5_000) { server.audioMessages().size >= 2 })
                assertArrayEquals(
                    byteArrayOf(0xAF.toByte(), 0x00, 0x12, 0x10),
                    server.audioMessages()[0].payload
                )
                assertEquals(0x01, server.audioMessages()[1].payload[1].toInt() and 0xFF)
                assertTrue(listener.errors.isEmpty())
            } finally {
                publisher.disconnect()
            }
        }
    }

    @Test(timeout = 30_000)
    fun chunksLargeFramesAndHandlesExtendedTimestamps() {
        MockRtmpServer().use { server ->
            val listener = RecordingListener()
            val publisher = connectAndWait(server, listener)
            try {
                publisher.setVideoInfo(640, 480, 30)
                // First frame: codec config + IDR at timestamp 0
                publisher.sendVideoFrame(annexB(sps, pps, idr), 0L)
                assertTrue(await(5_000) { server.videoMessages().size >= 2 })

                // A large NAL that does not fit in one 4096 byte chunk
                val largeIdr = ByteArray(64_000) { index -> (index % 251).toByte() }
                largeIdr[0] = 0x65
                publisher.sendVideoFrame(annexB(largeIdr), 100_000L)
                assertTrue(await(5_000) { server.videoMessages().size >= 3 })

                // Timestamp beyond 0xFFFFFF ms forces extended timestamps
                publisher.sendVideoFrame(annexB(largeIdr), 20_000_000_000L)
                assertTrue(await(5_000) { server.videoMessages().size >= 4 })

                val video = server.videoMessages()
                val expectedPayloadSize = 5 + 4 + largeIdr.size
                // chunks: ceil(64009 / 4096) = 16
                val expectedChunks = (expectedPayloadSize + 4095) / 4096
                assertEquals(expectedPayloadSize, video[2].payload.size)
                assertEquals(expectedChunks, video[2].chunkCount)
                assertEquals(expectedPayloadSize, video[3].payload.size)
                assertEquals(expectedChunks, video[3].chunkCount)

                assertEquals(0L, video[0].timestamp) // sequence header
                assertEquals(0L, video[1].timestamp)
                assertEquals(100L, video[2].timestamp)
                assertEquals(20_000_000L, video[3].timestamp)

                // 4 byte NAL length prefix
                assertEquals(largeIdr.size, readInt32(video[2].payload, 5))
            } finally {
                publisher.disconnect()
            }
        }
    }

    @Test(timeout = 20_000)
    fun dropsFramesWhenNotPublishing() {
        val listener = RecordingListener()
        val publisher = RtmpPublisher(listener)
        try {
            publisher.sendVideoFrame(annexB(sps, pps, idr), 0L)
            publisher.sendAudioFrame(byteArrayOf(1, 2, 3), 0L)
            val stats = publisher.stats
            assertEquals(RtmpState.IDLE, stats.state)
            assertFalse(stats.publishing)
            assertEquals(1L, stats.droppedVideoFrames)
            assertEquals(1L, stats.droppedAudioFrames)
            assertEquals(0L, stats.videoFramesSent)
            assertEquals(0L, stats.audioFramesSent)
            assertTrue(listener.errors.isEmpty())
        } finally {
            publisher.disconnect()
        }
    }

    @Test(timeout = 40_000)
    fun connectFailureReportsErrorAndAllowsRetry() {
        val deadPort = ServerSocket(0).use { it.localPort }
        val listener = RecordingListener()
        val publisher = RtmpPublisher(listener)
        try {
            publisher.connect("rtmp://127.0.0.1:$deadPort/live-bvc/?streamname=x&key=y")
            assertTrue("expected onError for unreachable server", await(15_000) { listener.errors.isNotEmpty() })
            assertTrue(await(15_000) { publisher.stats.state == RtmpState.ERROR })
            assertNotNull(publisher.stats.lastError)
            assertFalse(publisher.isPublishing)

            // A second connect() must recover from ERROR.
            MockRtmpServer().use { server ->
                publisher.connect("rtmp://127.0.0.1:${server.port}/live-bvc/?streamname=retry&key=k")
                assertTrue("publish after retry failed", server.awaitPublish(10_000))
                assertTrue(await(10_000) { publisher.isPublishing })
                assertEquals(RtmpState.PUBLISHING, publisher.stats.state)
                assertNull(publisher.stats.lastError)
            }
        } finally {
            publisher.disconnect()
        }
        assertEquals(RtmpState.STOPPED, publisher.stats.state)
    }

    @Test(timeout = 30_000)
    fun publishRejectionReportsError() {
        MockRtmpServer(acceptPublish = false).use { server ->
            val listener = RecordingListener()
            val publisher = RtmpPublisher(listener)
            try {
                publisher.connect("rtmp://127.0.0.1:${server.port}/live-bvc/?streamname=rejected&key=k")
                assertTrue("expected onError for rejected publish", await(10_000) { listener.errors.isNotEmpty() })
                assertTrue(await(10_000) { publisher.stats.state == RtmpState.ERROR })
                val error = publisher.stats.lastError ?: ""
                assertTrue("unexpected error message: $error", error.contains("publish", ignoreCase = true))
                assertFalse(publisher.isPublishing)
            } finally {
                publisher.disconnect()
            }
            assertEquals(RtmpState.STOPPED, publisher.stats.state)
        }
    }

    @Test(timeout = 20_000)
    fun disconnectWithoutConnectIsSafeAndIdempotent() {
        val listener = RecordingListener()
        val publisher = RtmpPublisher(listener)
        publisher.disconnect()
        publisher.disconnect()
        assertEquals(RtmpState.STOPPED, publisher.stats.state)
        assertFalse(publisher.isPublishing)
    }
}

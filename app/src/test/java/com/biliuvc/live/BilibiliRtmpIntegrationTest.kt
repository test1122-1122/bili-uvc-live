package com.biliuvc.live

import com.biliuvc.live.bili.ApiHarness
import com.biliuvc.live.bili.StartLiveResult
import com.biliuvc.live.rtmp.AmfString
import com.biliuvc.live.rtmp.H264AnnexB
import com.biliuvc.live.rtmp.MockRtmpServer
import com.biliuvc.live.rtmp.RtmpPublisher
import com.biliuvc.live.rtmp.RtmpState
import com.biliuvc.live.rtmp.RtmpTargetParser
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-component integration test:
 *
 * BiliApi.startLive(MockWebServer) -> StartLiveResult(addr, code) -> RtmpTargetParser ->
 * RtmpPublisher -> real RTMP wire protocol against MockRtmpServer.
 *
 * It also guards the "codec config arrives before RTMP publish is ready" regression: the first
 * video message on the wire must be the AVC sequence header, not a dropped config buffer.
 */
class BilibiliRtmpIntegrationTest {

    private val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E, 0x8C.toByte(), 0x68.toByte())
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
    private val idr = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x00, 0x21, 0x37)
    private val rawAac = byteArrayOf(0x21, 0x02, 0x03, 0x04)

    private class RecordingListener : RtmpPublisher.Listener {
        val states = CopyOnWriteArrayList<RtmpState>()
        val errors = CopyOnWriteArrayList<String>()

        override fun onState(state: RtmpState, detail: String?) {
            states.add(state)
        }

        override fun onStats(stats: com.biliuvc.live.rtmp.RtmpStats) = Unit

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

    private fun await(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    @Test(timeout = 60_000)
    fun bilibiliStartLiveResponseIsPublishedAsRtmpFlvStream() {
        MockRtmpServer().use { rtmpServer ->
            val harness = ApiHarness()
            try {
                val addr = "rtmp://127.0.0.1:${rtmpServer.port}/live-bvc/"
                val code = "?streamname=live_integration_42&key=secretKey"
                harness.enqueue(
                    """{"code":0,"message":"OK","data":{"build":11202,"curr_version":"8.11.1.11202"}}"""
                )
                harness.enqueue(
                    """{"code":0,"message":"OK","data":{"rtmp":{"addr":"$addr","code":"$code"}}}"""
                )

                val api = harness.api()
                val result = api.startLive(
                    ApiHarness.COOKIES,
                    ApiHarness.CSRF,
                    ApiHarness.ROOM_ID,
                    ApiHarness.AREA_ID,
                    ApiHarness.UID
                )
                assertTrue("startLive failed: $result", result is StartLiveResult.Success)
                val success = result as StartLiveResult.Success
                assertEquals(addr, success.addr)
                assertEquals(code, success.code)

                // Both HTTP calls really happened, in the expected order.
                val versionRequest = harness.take()
                assertTrue(
                    "first request should be liveVersionInfo, was ${versionRequest.path}",
                    versionRequest.path.orEmpty()
                        .startsWith("/xlive/app-blink/v1/liveVersionInfo/getHomePageLiveVersion")
                )
                val startRequest = harness.take()
                assertEquals("/room/v1/Room/startLive", startRequest.path)
                assertEquals("POST", startRequest.method)

                // ---- Bilibili push URL semantics ----
                val target = RtmpTargetParser.fromParts(success.addr, success.code)
                assertNotNull(target)
                target!!
                assertEquals("live-bvc", target.app)
                assertEquals("?streamname=live_integration_42&key=secretKey", target.streamName)
                assertEquals("rtmp://127.0.0.1:${rtmpServer.port}/live-bvc", target.tcUrl)

                // ---- publish the encoded stream ----
                val listener = RecordingListener()
                val publisher = RtmpPublisher(listener)
                publisher.setVideoInfo(1280, 720, 30)
                publisher.setAudioInfo(44_100, 2)
                try {
                    // Regression: MediaCodec emits the SPS/PPS codec-config buffer as soon as the
                    // encoder starts, i.e. before the RTMP publisher reaches PUBLISHING.
                    publisher.sendVideoFrame(annexB(sps, pps), 0L)
                    publisher.connect(target.originalUrl)

                    assertTrue("mock server did not receive publish", rtmpServer.awaitPublish(10_000))
                    assertTrue("publisher never reached PUBLISHING", await(10_000) { publisher.isPublishing })

                    // First frame after publishing must trigger the AVC sequence header + IDR.
                    publisher.sendVideoFrame(annexB(idr), 33_000L)
                    publisher.sendAudioFrame(rawAac, 40_000L)

                    assertTrue(
                        "no FLV video messages received",
                        await(8_000) { rtmpServer.videoMessages().size >= 2 }
                    )
                    assertTrue(
                        "no FLV audio messages received",
                        await(8_000) { rtmpServer.audioMessages().size >= 2 }
                    )

                    // ---- publish command keeps the leading '?' as the stream name ----
                    val publish = rtmpServer.commands.first { it.name == "publish" }
                    assertEquals("?streamname=live_integration_42&key=secretKey", publish.stringAt(3))

                    // ---- connect command app / tcUrl ----
                    val connect = rtmpServer.commands.first { it.name == "connect" }
                    assertEquals("live-bvc", (connect.amfObject()!!.properties["app"] as AmfString).value)
                    assertEquals(
                        "rtmp://127.0.0.1:${rtmpServer.port}/live-bvc",
                        (connect.amfObject()!!.properties["tcUrl"] as AmfString).value
                    )

                    // ---- AVC sequence header comes first, then the keyframe ----
                    val video = rtmpServer.videoMessages()
                    assertEquals(0x17, video[0].payload[0].toInt() and 0xFF)
                    assertEquals(0x00, video[0].payload[1].toInt() and 0xFF)
                    assertArrayEquals(
                        H264AnnexB.buildAvcConfigRecord(sps, pps),
                        video[0].payload.copyOfRange(5, video[0].payload.size)
                    )

                    val picture = video[1].payload
                    assertEquals(0x17, picture[0].toInt() and 0xFF) // key frame
                    assertEquals(0x01, picture[1].toInt() and 0xFF)
                    val naluLength = readInt32(picture, 5)
                    assertEquals(idr.size, naluLength)
                    assertArrayEquals(idr, picture.copyOfRange(9, 9 + idr.size))

                    // ---- AAC sequence header comes first, then raw AAC ----
                    val audio = rtmpServer.audioMessages()
                    assertEquals(0xAF, audio[0].payload[0].toInt() and 0xFF)
                    assertEquals(0x00, audio[0].payload[1].toInt() and 0xFF)
                    assertEquals(0xAF, audio[1].payload[0].toInt() and 0xFF)
                    assertEquals(0x01, audio[1].payload[1].toInt() and 0xFF)
                    assertArrayEquals(rawAac, audio[1].payload.copyOfRange(2, audio[1].payload.size))

                    assertEquals(0, listener.errors.size)
                } finally {
                    publisher.disconnect()
                }
            } finally {
                harness.close()
            }
        }
    }

    private fun readInt32(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }
}

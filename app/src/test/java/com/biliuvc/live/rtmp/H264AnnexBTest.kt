package com.biliuvc.live.rtmp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class H264AnnexBTest {

    private val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E, 0x8C.toByte(), 0x68.toByte())
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
    private val idr = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x00, 0x21)
    private val nonIdr = byteArrayOf(0x41, 0x9A.toByte(), 0x02, 0x03)

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

    @Test
    fun splitsFourByteStartCodes() {
        val units = H264AnnexB.split(annexB(sps, pps, idr))
        assertEquals(3, units.size)
        assertEquals(H264AnnexB.NAL_TYPE_SPS, units[0].type)
        assertEquals(H264AnnexB.NAL_TYPE_PPS, units[1].type)
        assertEquals(H264AnnexB.NAL_TYPE_IDR, units[2].type)
        assertArrayEquals(sps, units[0].data)
        assertArrayEquals(pps, units[1].data)
        assertArrayEquals(idr, units[2].data)
    }

    @Test
    fun splitsThreeByteStartCodes() {
        val data = byteArrayOf(
            0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1E,
            0x00, 0x00, 0x01, 0x68, 0xCE.toByte(), 0x3C,
            0x00, 0x00, 0x01, 0x65, 0x88.toByte()
        )
        val units = H264AnnexB.split(data)
        assertEquals(3, units.size)
        assertEquals(H264AnnexB.NAL_TYPE_SPS, units[0].type)
        assertEquals(4, units[0].data.size)
        assertEquals(H264AnnexB.NAL_TYPE_PPS, units[1].type)
        assertEquals(H264AnnexB.NAL_TYPE_IDR, units[2].type)
    }

    @Test
    fun splitsMixedStartCodeLengths() {
        val data = byteArrayOf(
            0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1E, // 4 byte start code
            0x00, 0x00, 0x01, 0x68, 0xCE.toByte(), 0x3C, // 3 byte start code
            0x00, 0x00, 0x00, 0x01, 0x65, 0x88.toByte() // 4 byte start code
        )
        val units = H264AnnexB.split(data)
        assertEquals(3, units.size)
        assertEquals(4, units[0].data.size)
        assertEquals(3, units[1].data.size)
        assertEquals(2, units[2].data.size)
    }

    @Test
    fun treatsBufferWithoutStartCodeAsSingleNal() {
        val units = H264AnnexB.split(idr)
        assertEquals(1, units.size)
        assertEquals(H264AnnexB.NAL_TYPE_IDR, units[0].type)
        assertArrayEquals(idr, units[0].data)
    }

    @Test
    fun skipsTrailingZeroPaddingBeforeNextStartCode() {
        val padded = byteArrayOf(
            0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1E, 0x00,
            0x00, 0x00, 0x01, 0x68, 0xCE.toByte()
        )
        val units = H264AnnexB.split(padded)
        assertEquals(2, units.size)
        assertEquals(4, units[0].data.size)
    }

    @Test
    fun detectsKeyFrame() {
        assertTrue(H264AnnexB.isKeyFrame(H264AnnexB.split(annexB(sps, pps, idr))))
        assertFalse(H264AnnexB.isKeyFrame(H264AnnexB.split(annexB(nonIdr))))
    }

    @Test
    fun detectsStartCodeAndEmptyInput() {
        assertTrue(H264AnnexB.hasStartCode(byteArrayOf(0, 0, 1, 0x65)))
        assertTrue(H264AnnexB.hasStartCode(byteArrayOf(0, 0, 0, 1, 0x65)))
        assertFalse(H264AnnexB.hasStartCode(byteArrayOf(0x65, 0x88.toByte())))
        assertTrue(H264AnnexB.split(ByteArray(0)).isEmpty())
    }

    @Test
    fun buildsAvcConfigRecordGoldenBytes() {
        val record = H264AnnexB.buildAvcConfigRecord(sps, pps)
        val expected = byteArrayOf(
            0x01, // configurationVersion
            0x42, // AVCProfileIndication (sps[1])
            0x00, // profile_compatibility (sps[2])
            0x1E, // AVCLevelIndication (sps[3])
            0xFF.toByte(), // lengthSizeMinusOne
            0xE1.toByte(), // numOfSequenceParameterSets
            0x00, sps.size.toByte(),
            sps[0], sps[1], sps[2], sps[3], sps[4], sps[5],
            0x01, // numOfPictureParameterSets
            0x00, pps.size.toByte(),
            pps[0], pps[1], pps[2], pps[3]
        )
        assertArrayEquals(expected, record)
        assertEquals(expected.size, record.size)
    }

    @Test
    fun buildsRecordFromAnnexB() {
        val record = H264AnnexB.avcConfigRecordFromAnnexB(annexB(sps, pps, idr))
        assertNotNull(record)
        assertArrayEquals(H264AnnexB.buildAvcConfigRecord(sps, pps), record)
    }

    @Test
    fun returnsNullRecordWhenParameterSetMissing() {
        assertNull(H264AnnexB.avcConfigRecordFromAnnexB(annexB(sps, idr)))
        assertNull(H264AnnexB.avcConfigRecordFromAnnexB(annexB(idr)))
        assertNull(H264AnnexB.avcConfigRecordFromAnnexB(ByteArray(0)))
    }

    @Test
    fun rejectsTooShortSps() {
        assertThrows(IllegalArgumentException::class.java) {
            H264AnnexB.buildAvcConfigRecord(byteArrayOf(0x67), pps)
        }
    }

    @Test
    fun lengthPrefixesNalUnits() {
        val units = H264AnnexB.split(annexB(idr, nonIdr))
        val prefixed = H264AnnexB.toLengthPrefixed(units)
        assertEquals(4 + idr.size + 4 + nonIdr.size, prefixed.size)
        assertArrayEquals(byteArrayOf(0, 0, 0, idr.size.toByte()), prefixed.copyOfRange(0, 4))
        assertArrayEquals(byteArrayOf(0, 0, 0, nonIdr.size.toByte()), prefixed.copyOfRange(4 + idr.size, 8 + idr.size))
        assertArrayEquals(idr, prefixed.copyOfRange(4, 4 + idr.size))
        assertArrayEquals(nonIdr, prefixed.copyOfRange(8 + idr.size, prefixed.size))
    }
}

class AacConfigTest {

    @Test
    fun buildsAacLcConfigFor44100Stereo() {
        assertArrayEquals(
            byteArrayOf(0x12, 0x10),
            AacConfig.buildAudioSpecificConfig(44_100, 2)
        )
    }

    @Test
    fun buildsAacLcConfigFor48000Mono() {
        // objectType 2 << 11 | frequencyIndex(48000)=3 << 7 | channels 1 << 3 = 0x1188
        assertArrayEquals(
            byteArrayOf(0x11, 0x88.toByte()),
            AacConfig.buildAudioSpecificConfig(48_000, 1)
        )
    }

    @Test
    fun buildsAacLcConfigFor22050Stereo() {
        // frequencyIndex(22050)=7 -> 2<<11 | 7<<7 | 2<<3 = 0x1390
        assertArrayEquals(
            byteArrayOf(0x13, 0x90.toByte()),
            AacConfig.buildAudioSpecificConfig(22_050, 2)
        )
    }

    @Test
    fun audioTagByteMatchesFlvSpec() {
        assertEquals(0xAF.toByte(), AacConfig.audioTagByte(44_100, 2))
        assertEquals(0xAE.toByte(), AacConfig.audioTagByte(44_100, 1))
        assertEquals(0xAF.toByte(), AacConfig.audioTagByte(48_000, 2))
    }

    @Test
    fun stripsAdtsHeader() {
        val raw = byteArrayOf(1, 2, 3, 4, 5)
        val adts = byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(), 0x50, 0x80.toByte(), 0x01, 0x7F, 0xFC.toByte()
        )
        assertTrue(AacConfig.hasAdtsHeader(adts + raw))
        assertArrayEquals(raw, AacConfig.stripAdtsHeader(adts + raw))
        assertFalse(AacConfig.hasAdtsHeader(raw))
        assertArrayEquals(raw, AacConfig.stripAdtsHeader(raw))
    }

    @Test
    fun stripsAdtsHeaderWithCrc() {
        val raw = byteArrayOf(9, 8, 7)
        val adtsWithCrc = byteArrayOf(
            0xFF.toByte(), 0xF0.toByte(), 0x50, 0x80.toByte(), 0x01, 0x7F, 0xFC.toByte(), 0xAA.toByte(), 0xBB.toByte()
        )
        assertArrayEquals(raw, AacConfig.stripAdtsHeader(adtsWithCrc + raw))
    }
}

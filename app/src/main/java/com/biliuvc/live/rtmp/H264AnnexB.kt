package com.biliuvc.live.rtmp

/**
 * H.264 Annex-B utilities: NAL unit splitting (3/4 byte start codes), SPS/PPS/IDR detection and
 * AVCDecoderConfigurationRecord construction (ISO/IEC 14496-15).
 *
 * Pure JVM, no Android dependencies.
 */
object H264AnnexB {

    const val NAL_TYPE_NON_IDR = 1
    const val NAL_TYPE_IDR = 5
    const val NAL_TYPE_SPS = 7
    const val NAL_TYPE_PPS = 8

    /** One NAL unit. [data] excludes any start code / length prefix. */
    data class NalUnit(val type: Int, val data: ByteArray) {
        val size: Int get() = data.size
    }

    /**
     * Splits an Annex-B byte array into NAL units. Accepts both 3-byte and 4-byte start codes,
     * mixed start code lengths and leading garbage. When no start code is present the whole
     * buffer is reported as a single NAL unit (defensive fallback for encoders that hand over a
     * bare NAL unit).
     */
    fun split(annexB: ByteArray): List<NalUnit> = split(annexB, 0, annexB.size)

    /** Splits the Annex-B region `[offset, offset+length)` into NAL units. */
    fun split(annexB: ByteArray, offset: Int, length: Int): List<NalUnit> {
        if (length <= 0 || offset < 0 || offset + length > annexB.size) return emptyList()
        val end = offset + length
        val units = ArrayList<NalUnit>(4)

        var position = offset
        var nalStart = -1
        while (position + 2 < end) {
            val zeroFirst = annexB[position].toInt() == 0
            val zeroSecond = annexB[position + 1].toInt() == 0
            if (zeroFirst && zeroSecond && annexB[position + 2].toInt() == 1) {
                // A 4-byte start code begins one byte earlier.
                val startCodeStart = if (position > offset && annexB[position - 1].toInt() == 0) position - 1 else position
                if (nalStart >= 0) {
                    var nalEnd = startCodeStart
                    // Trailing zero bytes (padding before the next start code) are not payload.
                    while (nalEnd > nalStart && annexB[nalEnd - 1].toInt() == 0) nalEnd--
                    if (nalEnd > nalStart) addUnit(annexB, nalStart, nalEnd, units)
                }
                val startCodeLength = position + 3 - startCodeStart
                nalStart = startCodeStart + startCodeLength
                position = nalStart
                continue
            }
            position++
        }

        if (nalStart >= 0) {
            var nalEnd = end
            while (nalEnd > nalStart && annexB[nalEnd - 1].toInt() == 0) nalEnd--
            if (nalEnd > nalStart) addUnit(annexB, nalStart, nalEnd, units)
        } else if (end > offset) {
            // No start code found: treat the whole buffer as a single raw NAL unit.
            addUnit(annexB, offset, end, units)
        }
        return units
    }

    private fun addUnit(annexB: ByteArray, start: Int, end: Int, out: MutableList<NalUnit>) {
        val type = annexB[start].toInt() and 0x1F
        out.add(NalUnit(type, annexB.copyOfRange(start, end)))
    }

    /** True when [annexB] starts with an Annex-B start code (3 or 4 bytes). */
    fun hasStartCode(annexB: ByteArray): Boolean {
        if (annexB.size < 3) return false
        if (annexB[0].toInt() == 0 && annexB[1].toInt() == 0 && annexB[2].toInt() == 1) return true
        return annexB.size >= 4 &&
            annexB[0].toInt() == 0 && annexB[1].toInt() == 0 &&
            annexB[2].toInt() == 0 && annexB[3].toInt() == 1
    }

    /** True when the NAL units contain an IDR slice (type 5). */
    fun isKeyFrame(units: List<NalUnit>): Boolean = units.any { it.type == NAL_TYPE_IDR }

    /**
     * Builds an AVCDecoderConfigurationRecord from SPS/PPS NAL units (without start codes).
     * Layout: configurationVersion(1), profile, compatibility, level, 0xFF, 0xE1,
     * spsLength(2) sps, 0x01, ppsLength(2) pps.
     */
    fun buildAvcConfigRecord(sps: ByteArray, pps: ByteArray): ByteArray {
        require(sps.size >= 4) { "SPS too short: ${sps.size}" }
        require(pps.isNotEmpty()) { "PPS is empty" }
        require(sps.size <= 0xFFFF) { "SPS too long: ${sps.size}" }
        require(pps.size <= 0xFFFF) { "PPS too long: ${pps.size}" }

        val record = ByteArray(11 + sps.size + pps.size)
        var index = 0
        record[index++] = 0x01 // configurationVersion
        record[index++] = sps[1] // AVCProfileIndication
        record[index++] = sps[2] // profile_compatibility
        record[index++] = sps[3] // AVCLevelIndication
        record[index++] = 0xFF.toByte() // lengthSizeMinusOne = 3 (reserved bits set)
        record[index++] = 0xE1.toByte() // numOfSequenceParameterSets = 1 (reserved bits set)
        record[index++] = ((sps.size ushr 8) and 0xFF).toByte()
        record[index++] = (sps.size and 0xFF).toByte()
        sps.copyInto(record, index)
        index += sps.size
        record[index++] = 0x01 // numOfPictureParameterSets
        record[index++] = ((pps.size ushr 8) and 0xFF).toByte()
        record[index++] = (pps.size and 0xFF).toByte()
        pps.copyInto(record, index)
        return record
    }

    /**
     * Extracts the first SPS/PPS pair found in [annexB] and builds the AVCDecoderConfigurationRecord.
     * Returns `null` when either parameter set is missing or SPS is malformed.
     */
    fun avcConfigRecordFromAnnexB(annexB: ByteArray): ByteArray? {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (unit in split(annexB)) {
            if (unit.type == NAL_TYPE_SPS && sps == null) sps = unit.data
            if (unit.type == NAL_TYPE_PPS && pps == null) pps = unit.data
            if (sps != null && pps != null) break
        }
        val parameterSetSps = sps ?: return null
        val parameterSetPps = pps ?: return null
        return try {
            buildAvcConfigRecord(parameterSetSps, parameterSetPps)
        } catch (t: Throwable) {
            null
        }
    }

    /** Concatenates NAL units using 4-byte big-endian length prefixes (FLV AVC NALU payload). */
    fun toLengthPrefixed(units: List<NalUnit>): ByteArray {
        var size = 0
        for (unit in units) size += 4 + unit.data.size
        val out = ByteArray(size)
        var offset = 0
        for (unit in units) {
            val length = unit.data.size
            out[offset] = ((length ushr 24) and 0xFF).toByte()
            out[offset + 1] = ((length ushr 16) and 0xFF).toByte()
            out[offset + 2] = ((length ushr 8) and 0xFF).toByte()
            out[offset + 3] = (length and 0xFF).toByte()
            unit.data.copyInto(out, offset + 4)
            offset += 4 + length
        }
        return out
    }

    /** 3-byte start code helper, exposed for tests/diagnostics. */
    fun startCodeLength(annexB: ByteArray, offset: Int = 0): Int {
        if (offset + 3 > annexB.size) return 0
        return if (annexB[offset].toInt() == 0 && annexB[offset + 1].toInt() == 0 && annexB[offset + 2].toInt() == 1) 3 else 0
    }
}

package com.biliuvc.live.rtmp

import java.io.ByteArrayOutputStream

/**
 * Minimal AMF0 model + codec used by the RTMP client (command messages and `@setDataFrame`).
 *
 * Supported markers: number (0x00), boolean (0x01), string (0x02), object (0x03), null (0x05),
 * undefined (0x06), ECMA array (0x08) and long string (0x0C).
 */
sealed class AmfValue

/** AMF0 number (IEEE-754 double, marker 0x00). */
data class AmfNumber(val value: Double) : AmfValue() {
    constructor(value: Int) : this(value.toDouble())
    constructor(value: Long) : this(value.toDouble())
}

/** AMF0 boolean (marker 0x01). */
data class AmfBoolean(val value: Boolean) : AmfValue()

/** AMF0 short string, max 65535 bytes (marker 0x02). */
data class AmfString(val value: String) : AmfValue()

/** AMF0 long string, 32 bit length (marker 0x0C). */
data class AmfLongString(val value: String) : AmfValue()

/** AMF0 anonymous object, insertion ordered (marker 0x03). */
data class AmfObject(val properties: LinkedHashMap<String, AmfValue> = LinkedHashMap()) : AmfValue() {
    constructor(vararg entries: Pair<String, AmfValue>) : this(linkedMapOf(*entries))
}

/** AMF0 ECMA array, insertion ordered (marker 0x08). */
data class AmfEcmaArray(val properties: LinkedHashMap<String, AmfValue> = LinkedHashMap()) : AmfValue() {
    constructor(vararg entries: Pair<String, AmfValue>) : this(linkedMapOf(*entries))
}

/** AMF0 null (marker 0x05). */
object AmfNull : AmfValue()

/** AMF0 undefined (marker 0x06). */
object AmfUndefined : AmfValue()

/** Encoder / decoder helpers. */
object Amf0 {

    private const val MARKER_NUMBER = 0x00
    private const val MARKER_BOOLEAN = 0x01
    private const val MARKER_STRING = 0x02
    private const val MARKER_OBJECT = 0x03
    private const val MARKER_NULL = 0x05
    private const val MARKER_UNDEFINED = 0x06
    private const val MARKER_ECMA_ARRAY = 0x08
    private const val MARKER_OBJECT_END = 0x09
    private const val MARKER_LONG_STRING = 0x0C

    /** Encodes a single AMF0 value. */
    fun encode(value: AmfValue): ByteArray {
        val writer = Amf0Writer()
        writer.write(value)
        return writer.toByteArray()
    }

    /** Encodes a sequence of AMF0 values (e.g. one RTMP command). */
    fun encodeAll(values: List<AmfValue>): ByteArray {
        val writer = Amf0Writer()
        for (value in values) writer.write(value)
        return writer.toByteArray()
    }

    /**
     * Decodes all AMF0 values contained in [data].
     * @throws IllegalArgumentException when the payload is malformed or uses an unsupported marker.
     */
    fun decode(data: ByteArray): List<AmfValue> {
        val reader = Amf0Reader(data)
        val values = ArrayList<AmfValue>()
        while (reader.hasMore()) values.add(reader.readValue())
        return values
    }

    /** Convenience factory for an [AmfObject]. */
    fun obj(vararg entries: Pair<String, AmfValue>): AmfObject = AmfObject(linkedMapOf(*entries))

    /** Convenience factory for an [AmfEcmaArray]. */
    fun ecma(vararg entries: Pair<String, AmfValue>): AmfEcmaArray = AmfEcmaArray(linkedMapOf(*entries))
}

/** Streaming AMF0 encoder. */
class Amf0Writer {

    private val out = ByteArrayOutputStream(128)

    fun write(value: AmfValue) {
        when (value) {
            is AmfNumber -> writeNumber(value.value)
            is AmfBoolean -> writeBoolean(value.value)
            is AmfString -> writeString(value.value)
            is AmfLongString -> writeLongString(value.value)
            is AmfObject -> writeObject(value)
            is AmfEcmaArray -> writeEcmaArray(value)
            AmfNull -> out.write(0x05)
            AmfUndefined -> out.write(0x06)
        }
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun writeNumber(value: Double) {
        out.write(0x00)
        val bits = java.lang.Double.doubleToLongBits(value)
        for (shift in 56 downTo 0 step 8) out.write(((bits ushr shift) and 0xFF).toInt())
    }

    private fun writeBoolean(value: Boolean) {
        out.write(0x01)
        out.write(if (value) 1 else 0)
    }

    private fun writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > 0xFFFF) {
            writeLongString(value)
            return
        }
        out.write(0x02)
        writeUInt16(bytes.size)
        out.write(bytes)
    }

    private fun writeLongString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.write(0x0C)
        writeUInt32(bytes.size)
        out.write(bytes)
    }

    private fun writeObject(value: AmfObject) {
        out.write(0x03)
        writeProperties(value.properties)
        writeObjectEnd()
    }

    private fun writeEcmaArray(value: AmfEcmaArray) {
        out.write(0x08)
        writeUInt32(value.properties.size)
        writeProperties(value.properties)
        writeObjectEnd()
    }

    private fun writeProperties(properties: Map<String, AmfValue>) {
        for ((key, propertyValue) in properties) {
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            writeUInt16(keyBytes.size)
            out.write(keyBytes)
            write(propertyValue)
        }
    }

    private fun writeObjectEnd() {
        out.write(0x00)
        out.write(0x00)
        out.write(0x09)
    }

    private fun writeUInt16(value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeUInt32(value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}

/** Streaming AMF0 decoder. */
class Amf0Reader(private val data: ByteArray) {

    private var position = 0

    fun hasMore(): Boolean = position < data.size

    fun position(): Int = position

    /**
     * Reads a single AMF0 value.
     * @throws IllegalArgumentException on truncated data or unsupported marker.
     */
    fun readValue(): AmfValue {
        val marker = readUnsignedByte()
        return when (marker) {
            0x00 -> AmfNumber(readDouble())
            0x01 -> AmfBoolean(readUnsignedByte() != 0)
            0x02 -> AmfString(readUtf8(readUnsignedShort()))
            0x03 -> AmfObject(readProperties().toLinkedMap())
            0x05 -> AmfNull
            0x06 -> AmfUndefined
            0x08 -> {
                // ECML array length is informational; entries are terminated by the object end marker.
                readUInt32()
                AmfEcmaArray(readProperties().toLinkedMap())
            }
            0x0C -> AmfLongString(readUtf8(readInt32()))
            else -> throw IllegalArgumentException(
                "unsupported AMF0 marker 0x" + Integer.toHexString(marker) + " at " + (position - 1)
            )
        }
    }

    private fun readProperties(): List<Pair<String, AmfValue>> {
        val properties = ArrayList<Pair<String, AmfValue>>()
        while (true) {
            val nameLength = readUnsignedShort()
            if (nameLength == 0) {
                if (!hasMore()) throw IllegalArgumentException("truncated AMF0 object")
                if (data[position].toInt() and 0xFF == 0x09) {
                    position++
                    return properties
                }
                throw IllegalArgumentException("invalid AMF0 object end marker at $position")
            }
            val name = readUtf8(nameLength)
            properties.add(name to readValue())
        }
    }

    private fun readUnsignedByte(): Int {
        if (position >= data.size) throw IllegalArgumentException("truncated AMF0 payload")
        return data[position++].toInt() and 0xFF
    }

    private fun readUnsignedShort(): Int = (readUnsignedByte() shl 8) or readUnsignedByte()

    private fun readInt32(): Int = (readUnsignedByte() shl 24) or
        (readUnsignedByte() shl 16) or
        (readUnsignedByte() shl 8) or
        readUnsignedByte()

    private fun readUInt32(): Long = readInt32().toLong() and 0xFFFFFFFFL

    private fun readDouble(): Double {
        var bits = 0L
        for (i in 0 until 8) bits = (bits shl 8) or readUnsignedByte().toLong()
        return java.lang.Double.longBitsToDouble(bits)
    }

    private fun readUtf8(length: Int): String {
        if (length < 0 || position + length > data.size) {
            throw IllegalArgumentException("truncated AMF0 string")
        }
        val value = String(data, position, length, Charsets.UTF_8)
        position += length
        return value
    }

    private fun List<Pair<String, AmfValue>>.toLinkedMap(): LinkedHashMap<String, AmfValue> {
        val map = LinkedHashMap<String, AmfValue>(size)
        for ((key, value) in this) map[key] = value
        return map
    }
}

package com.biliuvc.live.rtmp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Amf0Test {

    @Test
    fun encodesNumberGoldenBytes() {
        assertArrayEquals(
            byteArrayOf(0x00, 0x3F, 0xF0.toByte(), 0, 0, 0, 0, 0, 0),
            Amf0.encode(AmfNumber(1.0))
        )
        assertArrayEquals(
            byteArrayOf(0x00, 0xC0.toByte(), 0x08, 0, 0, 0, 0, 0, 0),
            Amf0.encode(AmfNumber(-3.0))
        )
    }

    @Test
    fun encodesStringGoldenBytes() {
        assertArrayEquals(
            byteArrayOf(0x02, 0x00, 0x03, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte()),
            Amf0.encode(AmfString("abc"))
        )
    }

    @Test
    fun encodesBooleanNullAndUndefined() {
        assertArrayEquals(byteArrayOf(0x01, 0x01), Amf0.encode(AmfBoolean(true)))
        assertArrayEquals(byteArrayOf(0x01, 0x00), Amf0.encode(AmfBoolean(false)))
        assertArrayEquals(byteArrayOf(0x05), Amf0.encode(AmfNull))
        assertArrayEquals(byteArrayOf(0x06), Amf0.encode(AmfUndefined))
    }

    @Test
    fun encodesLongStringWith32BitLength() {
        val encoded = Amf0.encode(AmfLongString("hello"))
        assertArrayEquals(
            byteArrayOf(0x0C, 0x00, 0x00, 0x00, 0x05, 'h'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte()),
            encoded
        )
    }

    @Test
    fun encodesObjectGoldenBytes() {
        val encoded = Amf0.encode(
            AmfObject(
                linkedMapOf(
                    "a" to AmfNumber(1.0)
                )
            )
        )
        assertArrayEquals(
            byteArrayOf(
                0x03,
                0x00, 0x01, 'a'.code.toByte(),
                0x00, 0x3F, 0xF0.toByte(), 0, 0, 0, 0, 0, 0,
                0x00, 0x00, 0x09
            ),
            encoded
        )
    }

    @Test
    fun encodesEcmaArrayGoldenBytes() {
        val encoded = Amf0.encode(
            AmfEcmaArray(
                linkedMapOf(
                    "k" to AmfBoolean(true)
                )
            )
        )
        assertArrayEquals(
            byteArrayOf(
                0x08,
                0x00, 0x00, 0x00, 0x01,
                0x00, 0x01, 'k'.code.toByte(),
                0x01, 0x01,
                0x00, 0x00, 0x09
            ),
            encoded
        )
    }

    @Test
    fun roundTripsNestedStructure() {
        val values = listOf(
            AmfString("connect"),
            AmfNumber(1.0),
            AmfObject(
                linkedMapOf(
                    "app" to AmfString("live-bvc"),
                    "fpad" to AmfBoolean(false),
                    "nested" to AmfObject(
                        linkedMapOf(
                            "inner" to AmfNumber(42.5),
                            "text" to AmfLongString("long text value")
                        )
                    ),
                    "list" to AmfEcmaArray(
                        linkedMapOf(
                            "zero" to AmfNumber(0.0),
                            "empty" to AmfString("")
                        )
                    )
                )
            ),
            AmfNull
        )
        val encoded = Amf0.encodeAll(values)
        val decoded = Amf0.decode(encoded)
        assertEquals(values.size, decoded.size)
        assertTrue(decoded[2] is AmfObject)
        val objectValue = decoded[2] as AmfObject
        assertEquals("live-bvc", (objectValue.properties["app"] as AmfString).value)
        assertEquals(false, (objectValue.properties["fpad"] as AmfBoolean).value)
        val nested = objectValue.properties["nested"] as AmfObject
        assertEquals(42.5, (nested.properties["inner"] as AmfNumber).value, 0.0)
        assertEquals("long text value", (nested.properties["text"] as AmfLongString).value)
        val list = objectValue.properties["list"] as AmfEcmaArray
        assertEquals(0.0, (list.properties["zero"] as AmfNumber).value, 0.0)
        assertEquals("", (list.properties["empty"] as AmfString).value)
        assertEquals(AmfNull, decoded[3])
    }

    @Test
    fun decodesCommandShapedPayload() {
        val payload = Amf0.encodeAll(
            listOf(
                AmfString("_result"),
                AmfNumber(1.0),
                AmfNull,
                AmfObject(
                    linkedMapOf(
                        "code" to AmfString("NetConnection.Connect.Success"),
                        "level" to AmfString("status")
                    )
                )
            )
        )
        val decoded = Amf0.decode(payload)
        assertEquals("_result", (decoded[0] as AmfString).value)
        assertEquals(1.0, (decoded[1] as AmfNumber).value, 0.0)
        assertEquals(AmfNull, decoded[2])
        val status = decoded[3] as AmfObject
        assertEquals("NetConnection.Connect.Success", (status.properties["code"] as AmfString).value)
    }

    @Test
    fun decodesUnicodeStrings() {
        val original = AmfString("B站推流-test")
        val decoded = Amf0.decode(Amf0.encode(original))
        assertEquals(original.value, (decoded[0] as AmfString).value)
    }

    @Test
    fun rejectsMalformedPayload() {
        assertThrows(IllegalArgumentException::class.java) {
            Amf0.decode(byteArrayOf(0x02, 0x00, 0x05, 'a'.code.toByte()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Amf0.decode(byteArrayOf(0x03, 0x00, 0x01, 'a'.code.toByte()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Amf0.decode(byteArrayOf(0x0A))
        }
    }

    @Test
    fun decodedObjectPreservesInsertionOrder() {
        val encoded = Amf0.encode(
            AmfObject(
                linkedMapOf(
                    "first" to AmfNumber(1.0),
                    "second" to AmfNumber(2.0),
                    "third" to AmfNumber(3.0)
                )
            )
        )
        val decoded = Amf0.decode(encoded)[0] as AmfObject
        assertEquals(listOf("first", "second", "third"), decoded.properties.keys.toList())
    }
}

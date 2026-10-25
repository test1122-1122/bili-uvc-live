package com.biliuvc.live.bili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {

    @Test
    fun `parses nested api response`() {
        val root = Json.parse(
            """
            {"code":0,"message":"0","data":{"rtmp":{"addr":"rtmp://a/live-bvc/","code":"?key=1"},
            "list":[{"id":1,"name":"x"},{"id":2,"name":"y"}],"is_identified":true,"flag":1}}
            """.trimIndent()
        ).asObj()!!
        assertEquals(0, root["code"].asInt())
        assertEquals(0, root["message"].asInt())
        assertEquals("rtmp://a/live-bvc/", root["data"]["rtmp"]["addr"].asString())
        assertEquals("?key=1", root["data"]["rtmp"]["code"].asString())
        assertEquals(2, root["data"]["list"].asArr()!!.items.size)
        assertEquals("y", root["data"]["list"].asArr()!!.items[1]["name"].asString())
        assertEquals(true, root["data"]["is_identified"].asBool())
        assertEquals(true, root["data"]["flag"].asBool())
        assertNull(root["absent"].asString())
    }

    @Test
    fun `handles unicode escapes and quoted characters`() {
        val root = Json.parse("""{"title":"\u6d4b\u8bd5 \"x\" \\ /","raw":"测试"}""").asObj()!!
        assertEquals("测试 \"x\" \\ /", root["title"].asString())
        assertEquals("测试", root["raw"].asString())
    }

    @Test
    fun `converts numeric strings for fields that bilibili returns as strings`() {
        val root = Json.parse("""{"mid":"42","room_id":987654,"negative":-1,"float":1.5}""").asObj()!!
        assertEquals(42L, root["mid"].asLong())
        assertEquals(987654L, root["room_id"].asLong())
        assertEquals(-1, root["negative"].asInt())
        assertNull(root["float"].asInt())
        assertNull(root["mid"].asBool())
    }

    @Test
    fun `invalid json returns null`() {
        assertNull(Json.parse(""))
        assertNull(Json.parse("not json"))
        assertNull(Json.parse("{"))
        assertNull(Json.parse("""{"a":}"""))
        assertNull(Json.parse("["))
    }

    @Test
    fun `quotes strings for json output`() {
        assertEquals("\"plain\"", Json.quote("plain"))
        assertEquals("\"a\\\"b\\\\c\\n\"", Json.quote("a\"b\\c\n"))
        assertEquals("\"\\u0001\"", Json.quote("\u0001"))
        assertEquals("\"测试 %2C *\"", Json.quote("测试 %2C *"))
    }

    @Test
    fun `bool values accept bool numbers and strings`() {
        assertTrue(Json.parse("true").asBool()!!)
        assertFalse(Json.parse("false").asBool()!!)
        assertTrue(Json.parse("1").asBool()!!)
        assertFalse(Json.parse("0").asBool()!!)
        assertTrue(Json.parse("\"1\"").asBool()!!)
        assertNull(Json.parse("\"maybe\"").asBool())
    }
}

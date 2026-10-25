package com.biliuvc.live.bili

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** nav / room_id_by_uid / getRoomBaseInfo / Area.getList / getRoomInfoOld。 */
class BiliApiAccountTest {

    private lateinit var harness: ApiHarness
    private lateinit var api: BiliApi

    @Before
    fun setUp() {
        harness = ApiHarness()
        api = harness.api()
    }

    @After
    fun tearDown() {
        harness.close()
    }

    // ------------------------------------------------------------ nav

    @Test
    fun `nav returns user info and sends cookie headers`() {
        harness.enqueue("""{"code":0,"data":{"isLogin":true,"mid":42,"uname":"测试用户"}}""")
        val user = api.nav(ApiHarness.COOKIES)!!
        assertEquals(42L, user.uid)
        assertEquals("测试用户", user.uname)

        val request = harness.take()
        assertEquals("/x/web-interface/nav", request.path)
        assertEquals(ApiHarness.COOKIES, request.getHeader("Cookie"))
        assertEquals("https://link.bilibili.com", request.getHeader("Origin"))
        assertEquals("https://link.bilibili.com/p/center/index", request.getHeader("Referer"))
    }

    @Test
    fun `nav returns null when not logged in`() {
        harness.enqueue("""{"code":-101,"message":"账号未登录","data":{}}""")
        assertNull(api.nav(ApiHarness.COOKIES))
    }

    @Test
    fun `nav returns null when isLogin is false`() {
        harness.enqueue("""{"code":0,"data":{"isLogin":false}}""")
        assertNull(api.nav(ApiHarness.COOKIES))
    }

    @Test
    fun `nav returns null for invalid mid or malformed body`() {
        harness.enqueue("""{"code":0,"data":{"isLogin":true,"uname":"x"}}""")
        assertNull(api.nav(ApiHarness.COOKIES))
        harness.enqueue("not json")
        assertNull(api.nav(ApiHarness.COOKIES))
        harness.enqueue("""{"code":0,"data":{"isLogin":true,"mid":"x","uname":"x"}}""")
        assertNull(api.nav(ApiHarness.COOKIES))
    }

    @Test
    fun `nav returns null on network failure and skips blank cookies`() {
        assertNull(ApiHarness.offlineApi().nav(ApiHarness.COOKIES))
        assertEquals(0, harness.server.requestCount)
        assertNull(api.nav(""))
        assertEquals(0, harness.server.requestCount)
    }

    // ------------------------------------------------------------ roomIdByUid

    @Test
    fun `roomIdByUid returns room id`() {
        harness.enqueue("""{"code":0,"data":{"room_id":987654}}""")
        assertEquals(987654L, api.roomIdByUid(ApiHarness.COOKIES, 42L))
        val request = harness.take()
        assertEquals("/room/v2/Room/room_id_by_uid?uid=42", request.path)
        assertEquals(ApiHarness.COOKIES, request.getHeader("Cookie"))
    }

    @Test
    fun `roomIdByUid returns zero when room is not opened`() {
        harness.enqueue("""{"code":0,"data":{"room_id":0}}""")
        assertEquals(0L, api.roomIdByUid(ApiHarness.COOKIES, 42L))
    }

    @Test
    fun `roomIdByUid returns null on api error or invalid data`() {
        harness.enqueue("""{"code":-400,"message":"请求错误"}""")
        assertNull(api.roomIdByUid(ApiHarness.COOKIES, 42L))
        harness.enqueue("""{"code":0,"data":{}}""")
        assertNull(api.roomIdByUid(ApiHarness.COOKIES, 42L))
        harness.enqueue("""{"code":0,"data":{"room_id":"abc"}}""")
        assertNull(api.roomIdByUid(ApiHarness.COOKIES, 42L))
    }

    @Test
    fun `roomIdByUid validates uid and network failure`() {
        assertNull(api.roomIdByUid(ApiHarness.COOKIES, 0L))
        assertEquals(0, harness.server.requestCount)
        assertNull(ApiHarness.offlineApi().roomIdByUid(ApiHarness.COOKIES, 42L))
    }

    // ------------------------------------------------------------ roomInfo

    private val roomInfoBody = """
        {"code":0,"data":{"by_room_ids":{"987654":{
            "room_id":987654,"title":"我的直播间","parent_area_id":2,"area_id":270,
            "parent_area_name":"娱乐","area_name":"单机游戏","live_status":1}}}}
    """.trimIndent()

    @Test
    fun `roomInfo parses all fields`() {
        harness.enqueue(roomInfoBody)
        val info = api.roomInfo(ApiHarness.COOKIES, 987654L)!!
        assertEquals(987654L, info.roomId)
        assertEquals("我的直播间", info.title)
        assertEquals(2, info.parentAreaId)
        assertEquals(270, info.areaId)
        assertEquals("娱乐", info.parentAreaName)
        assertEquals("单机游戏", info.areaName)
        assertEquals(1, info.liveStatus)
        assertEquals(
            "/xlive/web-room/v1/index/getRoomBaseInfo?req_biz=web_room_componet&room_ids=987654",
            harness.take().path
        )
    }

    @Test
    fun `roomInfo falls back to first room when key missing`() {
        harness.enqueue(
            """{"code":0,"data":{"by_room_ids":{"111":{"room_id":111,"title":"t","live_status":0}}}}"""
        )
        val info = api.roomInfo(ApiHarness.COOKIES, 987654L)!!
        assertEquals(111L, info.roomId)
        assertEquals("t", info.title)
    }

    @Test
    fun `roomInfo returns null when data is empty or request fails`() {
        harness.enqueue("""{"code":0,"data":{}}""")
        assertNull(api.roomInfo(ApiHarness.COOKIES, 987654L))
        harness.enqueue("""{"code":-404,"message":"房间不存在"}""")
        assertNull(api.roomInfo(ApiHarness.COOKIES, 987654L))
        harness.enqueue("""{"code":0,"data":{"by_room_ids":{"987654":{"title":"no id"}}}}""")
        // 缺 room_id 时用请求参数兜底，其余字段缺省
        val info = api.roomInfo(ApiHarness.COOKIES, 987654L)
        assertEquals(987654L, info!!.roomId)
        assertNull(api.roomInfo(ApiHarness.COOKIES, 0L))
        assertNull(ApiHarness.offlineApi().roomInfo(ApiHarness.COOKIES, 987654L))
    }

    // ------------------------------------------------------------ areaGroups

    @Test
    fun `areaGroups parses parent and sub areas`() {
        harness.enqueue(
            """
            {"code":0,"data":[
              {"id":1,"name":"娱乐","list":[{"id":21,"name":"视频唱见","parent_id":1},{"id":22,"name":"视频聊天"}]},
              {"id":2,"name":"游戏","list":[{"id":270,"name":"单机游戏"}]},
              {"id":3,"name":"电台","sub":[{"id":31,"name":"唱见电台"}]}
            ]}
            """.trimIndent()
        )
        val groups = api.areaGroups()
        assertEquals(3, groups.size)
        assertEquals(1, groups[0].id)
        assertEquals("娱乐", groups[0].name)
        assertEquals(listOf(Area(21, "视频唱见"), Area(22, "视频聊天")), groups[0].subAreas)
        assertEquals(listOf(Area(270, "单机游戏")), groups[1].subAreas)
        assertEquals(listOf(Area(31, "唱见电台")), groups[2].subAreas)
        assertNull(harness.take().getHeader("Cookie"))
    }

    @Test
    fun `areaGroups returns empty list on failure`() {
        harness.enqueue("""{"code":-500,"message":"err"}""")
        assertTrue(api.areaGroups().isEmpty())
        harness.enqueue("oops", status = 503)
        assertTrue(api.areaGroups().isEmpty())
        harness.enqueue("not json")
        assertTrue(api.areaGroups().isEmpty())
        assertTrue(ApiHarness.offlineApi().areaGroups().isEmpty())
    }

    // ------------------------------------------------------------ liveStatus

    @Test
    fun `liveStatus returns status values`() {
        harness.enqueue("""{"code":0,"data":{"liveStatus":1}}""")
        assertEquals(1, api.liveStatus(42L))
        assertEquals("/room/v1/Room/getRoomInfoOld?mid=42", harness.take().path)
        harness.enqueue("""{"code":0,"data":{"liveStatus":0}}""")
        assertEquals(0, api.liveStatus(42L))
        harness.enqueue("""{"code":0,"data":{"liveStatus":"2"}}""")
        assertEquals(2, api.liveStatus(42L))
    }

    @Test
    fun `liveStatus returns null on invalid status or failure`() {
        harness.enqueue("""{"code":0,"data":{"liveStatus":7}}""")
        assertNull(api.liveStatus(42L))
        harness.enqueue("""{"code":-1,"message":"err"}""")
        assertNull(api.liveStatus(42L))
        assertNull(api.liveStatus(0L))
        assertNull(ApiHarness.offlineApi().liveStatus(42L))
    }
}

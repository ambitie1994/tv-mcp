package com.ambitie.tvmcp.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    @Test
    fun `parses attributes and relative urls`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-name="CCTV1" tvg-logo="http://logo/1.png" group-title="央视",CCTV-1 综合
            http://example.com/live/cctv1.m3u8
            #EXTINF:-1 group-title="测试",频道 B
            streams/b.m3u8
            #EXTINF:-1,频道C
            /root/c.m3u8
        """.trimIndent()

        val channels = M3uParser.parse(playlist, "http://host/lists/tv.m3u")
        assertEquals(3, channels.size)
        assertEquals("CCTV-1 综合", channels[0].name)
        assertEquals("央视", channels[0].group)
        assertEquals("http://example.com/live/cctv1.m3u8", channels[0].url)
        assertEquals("http://host/lists/streams/b.m3u8", channels[1].url)
        assertEquals("http://host/root/c.m3u8", channels[2].url)
    }

    @Test
    fun `encodes chinese and spaces in urls`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 group-title="中文 分组",中文 频道
            http://host/电影 列表/测试 视频.m3u8
            #EXTINF:-1,相对 频道
            子目录/第二集 测试.m3u8
        """.trimIndent()

        val channels = M3uParser.parse(playlist, "http://host/节目 列表/tv.m3u")
        assertEquals(2, channels.size)
        assertEquals("中文 频道", channels[0].name)
        assertEquals("中文 分组", channels[0].group)
        assertEquals(
            "http://host/%E7%94%B5%E5%BD%B1%20%E5%88%97%E8%A1%A8/%E6%B5%8B%E8%AF%95%20%E8%A7%86%E9%A2%91.m3u8",
            channels[0].url
        )
        assertEquals(
            "http://host/%E8%8A%82%E7%9B%AE%20%E5%88%97%E8%A1%A8/%E5%AD%90%E7%9B%AE%E5%BD%95/%E7%AC%AC%E4%BA%8C%E9%9B%86%20%E6%B5%8B%E8%AF%95.m3u8",
            channels[1].url
        )
    }

    @Test
    fun `keeps existing percent encoding`() {
        val playlist = """
            #EXTINF:-1,已编码
            http://host/a%20b/中文.m3u8?x=1%202
        """.trimIndent()

        val channels = M3uParser.parse(playlist, "http://host/list.m3u")
        assertEquals(
            "http://host/a%20b/%E4%B8%AD%E6%96%87.m3u8?x=1%202",
            channels[0].url
        )
    }

    @Test
    fun `handles extgrp and vlc options`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1,频道一
            #EXTGRP:新闻
            http://host/a.m3u8|User-Agent=VLC
        """.trimIndent()

        val channels = M3uParser.parse(playlist, "http://host/list.m3u")
        assertEquals(1, channels.size)
        assertEquals("新闻", channels[0].group)
        assertEquals("http://host/a.m3u8", channels[0].url)
    }

    @Test
    fun `skips entries without urls and non http schemes`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1,无地址
            #EXTINF:-1,RTMP
            rtmp://host/live
            #EXTINF:-1,正常
            http://host/ok.m3u8
        """.trimIndent()

        val channels = M3uParser.parse(playlist, "http://host/list.m3u")
        assertEquals(1, channels.size)
        assertEquals("正常", channels[0].name)
    }

    @Test
    fun `keeps same url under different names`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1,一
            http://host/same.m3u8
            #EXTINF:-1,二
            http://host/same.m3u8
        """.trimIndent()
        assertEquals(2, M3uParser.parse(playlist, "http://host/list.m3u").size)
    }

    @Test
    fun `deduplicates identical entries`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1,一
            http://host/same.m3u8
            #EXTINF:-1,一
            http://host/same.m3u8
        """.trimIndent()
        assertEquals(1, M3uParser.parse(playlist, "http://host/list.m3u").size)
    }

    @Test
    fun `keeps vod entries with positive durations`() {
        val playlist = """
            #EXTM3U
            #EXTINF:7200,电影 A
            http://host/a.mp4
            #EXTINF:5400,电影 B
            http://host/b.mp4
        """.trimIndent()

        val channels = M3uParser.parse(playlist, "http://host/list.m3u")
        assertEquals(2, channels.size)
        assertEquals("电影 A", channels[0].name)
        assertEquals("电影 B", channels[1].name)
    }

    @Test
    fun `direct hls media playlist has no channels`() {
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:2
            #EXTINF:2.0,
            seg0.ts
        """.trimIndent()
        assertTrue(M3uParser.parse(playlist, "http://host/live/stream.m3u8").isEmpty())
    }
}

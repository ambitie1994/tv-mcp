package com.ambitie.tvmcp.playback

import com.ambitie.tvmcp.smb.SmbEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaFilesTest {
    @Test
    fun `natural compare orders digit runs by value`() {
        assert(MediaFiles.naturalCompare("01.mp4", "02.mp4") < 0)
        assert(MediaFiles.naturalCompare("2.mp4", "10.mp4") < 0)
        assert(MediaFiles.naturalCompare("第10集.mkv", "第9集.mkv") > 0)
        assertEquals(0, MediaFiles.naturalCompare("A.MP4", "a.mp4"))
    }

    @Test
    fun `natural compare prefers shorter name on prefix`() {
        assert(MediaFiles.naturalCompare("1.mp4", "10.mp4") < 0)
    }

    @Test
    fun `playable filters non media and sorts naturally`() {
        val entries = listOf(
            SmbEntry("cover.jpg", "剧集/cover.jpg", false, 1L),
            SmbEntry("01.mp4", "剧集/01.mp4", false, 1L),
            SmbEntry("第2集.mp4", "剧集/第2集.mp4", false, 1L),
            SmbEntry("10.mkv", "剧集/10.mkv", false, 1L),
            SmbEntry("01.srt", "剧集/01.srt", false, 1L),
            SmbEntry("第100集.mp4", "剧集/第100集.mp4", false, 1L),
            SmbEntry("子目录", "剧集/子目录", true, 0L)
        )
        val names = MediaFiles.playable(entries).map { it.name }
        assertEquals(listOf("01.mp4", "10.mkv", "第2集.mp4", "第100集.mp4"), names)
    }

    @Test
    fun `playable skips hidden files`() {
        val entries = listOf(
            SmbEntry(".01.mp4", ".01.mp4", false, 1L),
            SmbEntry("~01.mp4", "~01.mp4", false, 1L),
            SmbEntry("01.mp4", "01.mp4", false, 1L)
        )
        assertEquals(listOf("01.mp4"), MediaFiles.playable(entries).map { it.name })
    }
}

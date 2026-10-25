package com.ambitie.tvmcp.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleFilesTest {

    @Test
    fun `maps supported subtitle extensions`() {
        assertEquals("application/x-subrip", SubtitleFiles.mimeType("movie.zh.srt"))
        assertEquals("text/vtt", SubtitleFiles.mimeType("movie.en.VTT"))
        assertEquals("text/x-ssa", SubtitleFiles.mimeType("movie.ass"))
        assertEquals("text/x-ssa", SubtitleFiles.mimeType("movie.ssa"))
        assertNull(SubtitleFiles.mimeType("movie.mkv"))
        assertTrue(SubtitleFiles.isSubtitle("movie.zh.srt"))
        assertFalse(SubtitleFiles.isSubtitle("movie.zh.srt.bak"))
    }
}

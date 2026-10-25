package com.ambitie.tvmcp.smb

import com.ambitie.tvmcp.data.SmbSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SmbPathTest {

    private val source = SmbSource(
        id = "s1",
        name = "NAS",
        host = "192.168.1.10",
        share = "Media",
        username = "u",
        password = "p"
    )

    @Test
    fun `normalizes slashes and dots`() {
        assertEquals("Movies/Film.mkv", SmbPath.normalize("\\Movies\\Film.mkv"))
        assertEquals("Movies/Film.mkv", SmbPath.normalize("/Movies//./Film.mkv/"))
        assertEquals("", SmbPath.normalize("/"))
    }

    @Test
    fun `rejects parent traversal`() {
        assertThrows(IllegalArgumentException::class.java) {
            SmbPath.normalize("Movies/../secret.mkv")
        }
    }

    @Test
    fun `builds encoded smb uri`() {
        assertEquals(
            "tvsmb://s1/Movies/My%20Film.mkv",
            SmbPath.smbUri("s1", "Movies/My Film.mkv")
        )
    }

    @Test
    fun `builds display path without credentials`() {
        assertEquals(
            "smb://192.168.1.10/Media/Movies/Film.mkv",
            SmbPath.displayPath(source, "Movies/Film.mkv")
        )
    }
}

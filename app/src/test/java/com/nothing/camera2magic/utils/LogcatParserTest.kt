package com.nothing.camera2magic.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogcatParserTest {

    @Test
    fun parsesStandardThreadtimeVcxLine() {
        val line = "08-05 12:34:56.789  1234  5678 I VCX    : [CAM2] hello world"
        val parsed = parseLogcatLine(line, year = 2026)
        assertNotNull(parsed)
        assertEquals("I", parsed!!.level)
        assertEquals("VCX", parsed.tag)
        assertEquals(1234, parsed.pid)
        assertEquals(5678, parsed.tid)
        assertEquals("[CAM2] hello world", parsed.message)
        assertTrue(parsed.timeMillis > 0)
    }

    @Test
    fun skipsNonVcxTag() {
        val line = "08-05 12:34:56.789  1234  5678 I Other  : hello"
        assertNull(parseLogcatLine(line))
    }

    @Test
    fun skipsBannerAndGarbageLines() {
        assertNull(parseLogcatLine(""))
        assertNull(parseLogcatLine("--------- beginning of main"))
        assertNull(parseLogcatLine("su: invalid uid/gid"))
        assertNull(parseLogcatLine("-su: Permission denied"))
    }

    @Test
    fun extractsPidForOwnProcessDedup() {
        val line = "08-05 12:34:56.789  9999  1111 W VCX    : [WebRTC] rotation 90"
        val parsed = parseLogcatLine(line, year = 2026)
        assertNotNull(parsed)
        assertEquals(9999, parsed!!.pid)
        assertEquals("W", parsed.level)
    }
}

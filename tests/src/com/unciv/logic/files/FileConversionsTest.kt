package com.unciv.logic.files

import org.junit.Assert.assertEquals
import org.junit.Test

class FileConversionsTest {
    @Test
    fun `base64 output stays compatible with java encoder`() {
        assertEquals("VW5jaXYgaU9TIFBPQw==", FileConversions.encode("Unciv iOS POC".toByteArray()))
    }

    @Test
    fun `gzip base64 output stays compatible with existing saves`() {
        val encoded = "H4sIAAAAAAAA/wvNS84sU8j0D1YI8HcGAIa8kI8NAAAA"

        assertEquals(encoded, FileConversions.zip("Unciv iOS POC"))
        assertEquals("Unciv iOS POC", FileConversions.unzip(encoded))
    }
}

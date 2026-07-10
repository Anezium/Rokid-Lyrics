package com.rokid.lyrics.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyCookieCaptureTest {
    @Test
    fun spDcFromCookieHeaders_returnsValueFromFirstHeader() {
        assertEquals(
            "AQD_first",
            spDcFromCookieHeaders("sp_dc=AQD_first; sp_key=old", "sp_dc=AQD_second"),
        )
    }

    @Test
    fun spDcFromCookieHeaders_fallsBackToSecondHeader() {
        assertEquals(
            "AQD_second",
            spDcFromCookieHeaders("sp_key=old; other=1", "sp_dc=AQD_second"),
        )
    }

    @Test
    fun spDcFromCookieHeaders_returnsNullWhenNeitherHeaderContainsSpDc() {
        assertNull(spDcFromCookieHeaders(null, "sp_key=old; other=1"))
    }

    @Test
    fun spDcFromCookieHeaders_parsesRealisticWebViewCookieHeader() {
        val header =
            "sp_t=abc123; OptanonConsent=isGpcEnabled=0; sp_dc=AQD_webview_value; " +
                "__Host-sp_csrf_sid=csrf123"

        assertEquals("AQD_webview_value", spDcFromCookieHeaders(header))
    }
}

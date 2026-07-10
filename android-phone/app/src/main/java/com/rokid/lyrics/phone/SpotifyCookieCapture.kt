package com.rokid.lyrics.phone

import com.rokid.lyrics.phone.lyrics.SpotifySpDcCookie

internal fun spDcFromCookieHeaders(vararg headers: String?): String? {
    headers.forEach { header ->
        val spDc = header?.let(SpotifySpDcCookie::extractValue)
        if (spDc != null) return spDc
    }
    return null
}

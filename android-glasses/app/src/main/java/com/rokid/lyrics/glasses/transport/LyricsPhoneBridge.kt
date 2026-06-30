package com.rokid.lyrics.glasses.transport

import com.rokid.lyrics.contracts.GlassesToPhoneMessage
import com.rokid.lyrics.contracts.PhoneToGlassesMessage

interface LyricsPhoneBridge {
    fun send(message: GlassesToPhoneMessage)
    fun subscribe(listener: (PhoneToGlassesMessage) -> Unit): () -> Unit
}

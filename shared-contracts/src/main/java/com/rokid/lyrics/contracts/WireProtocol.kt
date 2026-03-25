package com.rokid.lyrics.contracts

import com.google.gson.Gson

data class WireEnvelope(
    val channel: String,
    val type: String,
    val payloadJson: String? = null,
)

object WireProtocol {
    private val gson = Gson()

    fun encodeGlassesMessage(message: GlassesToPhoneMessage): String =
        gson.toJson(glassesEnvelopeFor(message))

    fun decodeGlassesMessageOrNull(json: String): GlassesToPhoneMessage? =
        runCatching { gson.fromJson(json, WireEnvelope::class.java) }
            .getOrNull()
            ?.let(::glassesMessageFor)

    fun encodePhoneMessage(message: PhoneToGlassesMessage): String =
        gson.toJson(phoneEnvelopeFor(message))

    fun decodePhoneMessageOrNull(json: String): PhoneToGlassesMessage? =
        runCatching { gson.fromJson(json, WireEnvelope::class.java) }
            .getOrNull()
            ?.let(::phoneMessageFor)

    private fun glassesEnvelopeFor(message: GlassesToPhoneMessage): WireEnvelope = when (message) {
        GlassesToPhoneMessage.RequestSnapshot -> WireEnvelope("runtime", "request_snapshot")
        GlassesToPhoneMessage.RequestStatus -> WireEnvelope("runtime", "request_status")
        GlassesToPhoneMessage.RefreshLyrics -> WireEnvelope("lyrics", "refresh")
    }

    private fun glassesMessageFor(envelope: WireEnvelope): GlassesToPhoneMessage? = when (envelope.channel) {
        "runtime" -> when (envelope.type) {
            "request_snapshot" -> GlassesToPhoneMessage.RequestSnapshot
            "request_status" -> GlassesToPhoneMessage.RequestStatus
            else -> null
        }

        "lyrics" -> when (envelope.type) {
            "refresh" -> GlassesToPhoneMessage.RefreshLyrics
            else -> null
        }

        else -> null
    }

    private fun phoneEnvelopeFor(message: PhoneToGlassesMessage): WireEnvelope = when (message) {
        is PhoneToGlassesMessage.Status -> WireEnvelope("runtime", "status", gson.toJson(message.status))
        is PhoneToGlassesMessage.Lyrics -> when (val event = message.event) {
            is LyricsEvent.Snapshot -> WireEnvelope("lyrics", "snapshot", gson.toJson(event.snapshot))
            is LyricsEvent.Sync -> WireEnvelope("lyrics", "sync", gson.toJson(event.sync))
            is LyricsEvent.Error -> WireEnvelope("lyrics", "error", gson.toJson(event))
        }

        is PhoneToGlassesMessage.Error -> WireEnvelope("runtime", "error", gson.toJson(message))
    }

    private fun phoneMessageFor(envelope: WireEnvelope): PhoneToGlassesMessage? = when (envelope.channel) {
        "runtime" -> when (envelope.type) {
            "status" -> parsePayload(envelope, DeviceStatus::class.java)?.let { PhoneToGlassesMessage.Status(it) }
            "error" -> parsePayload(envelope, PhoneToGlassesMessage.Error::class.java)
            else -> null
        }

        "lyrics" -> when (envelope.type) {
            "snapshot" -> parsePayload(envelope, LyricsSnapshot::class.java)?.let {
                PhoneToGlassesMessage.Lyrics(LyricsEvent.Snapshot(it))
            }

            "sync" -> parsePayload(envelope, LyricsPlaybackSync::class.java)?.let {
                PhoneToGlassesMessage.Lyrics(LyricsEvent.Sync(it))
            }

            "error" -> parsePayload(envelope, LyricsEvent.Error::class.java)?.let {
                PhoneToGlassesMessage.Lyrics(it)
            }

            else -> null
        }

        else -> null
    }

    private fun <T> parsePayload(envelope: WireEnvelope, clazz: Class<T>): T? {
        val payload = envelope.payloadJson ?: return null
        return runCatching { gson.fromJson(payload, clazz) }.getOrNull()
    }
}

package com.rokid.lyrics.contracts

import com.google.gson.annotations.SerializedName
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}

data class DeviceStatus(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val statusLabel: String = "Waiting for the phone runtime.",
    val bluetoothClientCount: Int = 0,
    val notificationAccessEnabled: Boolean = false,
    val lastError: String? = null,
)

enum class LyricsSessionState {
    IDLE,
    LOADING,
    READY,
    PLAYING,
    ERROR,
}

data class LyricsLine(
    val startTimeMs: Long = 0L,
    val text: String = "",
)

data class LyricsSnapshot(
    val sessionState: LyricsSessionState = LyricsSessionState.IDLE,
    val mediaKey: String = "",
    val revision: Long = 0L,
    val trackTitle: String = "",
    val artistName: String = "",
    val albumName: String = "",
    val durationSeconds: Int? = null,
    val provider: String = "",
    val sourceSummary: String = "Waiting for active media playback on the phone.",
    val synced: Boolean = false,
    val progressMs: Long = 0L,
    val capturedAtEpochMs: Long = 0L,
    val currentLineIndex: Int = -1,
    val lines: List<LyricsLine> = emptyList(),
    val plainLyrics: String = "",
    val errorMessage: String? = null,
)

data class LyricsPlaybackSync(
    val sessionState: LyricsSessionState = LyricsSessionState.IDLE,
    val mediaKey: String = "",
    val revision: Long = 0L,
    val progressMs: Long = 0L,
    val capturedAtEpochMs: Long = 0L,
    val currentLineIndex: Int = -1,
)

data class MediaPlaybackHint(
    val source: String = "GLASSES_AVRCP",
    val trackId: String = "",
    val title: String = "",
    val artistName: String = "",
    val albumName: String = "",
    val durationSeconds: Int? = null,
    val progressMs: Long = 0L,
    val capturedAtEpochMs: Long = 0L,
    val isPlaying: Boolean = false,
)

data class LyricsWindowLine(
    @SerializedName("s") val startTimeMs: Long = 0L,
    @SerializedName("t") val text: String = "",
) {
    fun toLyricsLine(): LyricsLine = LyricsLine(
        startTimeMs = startTimeMs,
        text = text,
    )
}

data class LyricsWindowSnapshot(
    @SerializedName("s") val sessionState: LyricsSessionState = LyricsSessionState.IDLE,
    @SerializedName("m") val mediaKey: String = "",
    @SerializedName("r") val revision: Long = 0L,
    @SerializedName("t") val trackTitle: String = "",
    @SerializedName("a") val artistName: String = "",
    @SerializedName("v") val provider: String = "",
    @SerializedName("p") val progressMs: Long = 0L,
    @SerializedName("c") val capturedAtEpochMs: Long = 0L,
    @SerializedName("i") val currentLineIndex: Int = -1,
    @SerializedName("l") val lines: List<LyricsWindowLine> = emptyList(),
)

data class LyricsScriptSnapshot(
    @SerializedName("s") val sessionState: LyricsSessionState = LyricsSessionState.IDLE,
    @SerializedName("m") val mediaKey: String = "",
    @SerializedName("r") val revision: Long = 0L,
    @SerializedName("t") val trackTitle: String = "",
    @SerializedName("a") val artistName: String = "",
    @SerializedName("v") val provider: String = "",
    @SerializedName("p") val progressMs: Long = 0L,
    @SerializedName("c") val capturedAtEpochMs: Long = 0L,
    @SerializedName("i") val currentLineIndex: Int = -1,
    @SerializedName("e") val encoding: String = SCRIPT_ENCODING_PLAIN,
    @SerializedName("b") val body: String = "",
) {
    fun toLines(): List<LyricsLine> =
        decodedBody().lineSequence()
            .mapNotNull { encoded ->
                val separator = encoded.indexOf('\t')
                if (separator <= 0) return@mapNotNull null
                val startTimeMs = encoded.substring(0, separator).toLongOrNull(36) ?: return@mapNotNull null
                LyricsLine(
                    startTimeMs = startTimeMs,
                    text = encoded.substring(separator + 1),
                )
            }
            .toList()

    private fun decodedBody(): String {
        if (encoding != SCRIPT_ENCODING_ZLIB_BASE64) return body
        return runCatching {
            val compressed = Base64.getDecoder().decode(body)
            InflaterInputStream(ByteArrayInputStream(compressed), Inflater(true)).use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrDefault("")
    }

    companion object {
        const val SCRIPT_ENCODING_PLAIN = "plain"
        const val SCRIPT_ENCODING_ZLIB_BASE64 = "zlib64"
    }
}

data class ProtocolHello(
    val protocolVersion: Int = TransportConstants.PROTOCOL_VERSION,
    val appVersion: String = "",
    val capabilities: List<String> = emptyList(),
)

data class ProtocolHelloAck(
    val protocolVersion: Int = TransportConstants.PROTOCOL_VERSION,
    val appVersion: String = "",
    val capabilities: List<String> = emptyList(),
)

sealed interface GlassesToPhoneMessage {
    data class Hello(val hello: ProtocolHello) : GlassesToPhoneMessage
    data class MediaHint(val hint: MediaPlaybackHint) : GlassesToPhoneMessage
    data object RequestSnapshot : GlassesToPhoneMessage
    data object RequestStatus : GlassesToPhoneMessage
    data object TogglePlayback : GlassesToPhoneMessage
}

sealed interface LyricsEvent {
    data class Snapshot(val snapshot: LyricsSnapshot) : LyricsEvent
    data class Window(val snapshot: LyricsWindowSnapshot) : LyricsEvent
    data class Script(val snapshot: LyricsScriptSnapshot) : LyricsEvent
    data class Sync(val sync: LyricsPlaybackSync) : LyricsEvent
    data class Error(val message: String) : LyricsEvent
}

sealed interface PhoneToGlassesMessage {
    data class HelloAck(val ack: ProtocolHelloAck) : PhoneToGlassesMessage
    data class Status(val status: DeviceStatus) : PhoneToGlassesMessage
    data class Lyrics(val event: LyricsEvent) : PhoneToGlassesMessage
    data class Error(val message: String) : PhoneToGlassesMessage
}

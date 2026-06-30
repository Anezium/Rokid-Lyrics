package com.rokid.lyrics.contracts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

class WireProtocolTest {
    @Test
    fun glassesHello_roundTripsThroughWireProtocol() {
        val message = GlassesToPhoneMessage.Hello(
            ProtocolHello(
                protocolVersion = TransportConstants.PROTOCOL_VERSION,
                appVersion = "glasses-0.1.0",
                capabilities = listOf("request_status", "toggle_playback"),
            )
        )

        val decoded = WireProtocol.decodeGlassesMessageOrNull(WireProtocol.encodeGlassesMessage(message))

        assertEquals(message, decoded)
    }

    @Test
    fun glassesMediaHint_roundTripsThroughWireProtocol() {
        val message = GlassesToPhoneMessage.MediaHint(
            MediaPlaybackHint(
                title = "GIVENCHY BAG",
                artistName = "34murphy",
                albumName = "CRYSTAL PIEGE",
                durationSeconds = 148,
                progressMs = 53_722L,
                capturedAtEpochMs = 1_782_770_472_571L,
                isPlaying = true,
            )
        )

        val decoded = WireProtocol.decodeGlassesMessageOrNull(WireProtocol.encodeGlassesMessage(message))

        assertEquals(message, decoded)
    }

    @Test
    fun phoneHelloAck_roundTripsThroughWireProtocol() {
        val message = PhoneToGlassesMessage.HelloAck(
            ProtocolHelloAck(
                protocolVersion = TransportConstants.PROTOCOL_VERSION,
                appVersion = "phone-0.1.0",
                capabilities = listOf("lyrics_snapshot", "lyrics_window", "lyrics_script", "lyrics_sync"),
            )
        )

        val decoded = WireProtocol.decodePhoneMessageOrNull(WireProtocol.encodePhoneMessage(message))

        assertEquals(message, decoded)
    }

    @Test
    fun lyricsWindow_roundTripsThroughWireProtocol() {
        val message = PhoneToGlassesMessage.Lyrics(
            LyricsEvent.Window(
                LyricsWindowSnapshot(
                    sessionState = LyricsSessionState.PLAYING,
                    mediaKey = "spotify|track-1",
                    revision = 42L,
                    trackTitle = "DUNYA",
                    artistName = "Rounhaa",
                    provider = "SPOTIFY",
                    progressMs = 106_790L,
                    capturedAtEpochMs = 1_782_769_793_699L,
                    currentLineIndex = 0,
                    lines = listOf(
                        LyricsWindowLine(startTimeMs = 106_550L, text = "Le mal ou le bien"),
                        LyricsWindowLine(startTimeMs = 108_970L, text = "Ouh-ouh, ouh-ouh"),
                    ),
                )
            )
        )

        val decoded = WireProtocol.decodePhoneMessageOrNull(WireProtocol.encodePhoneMessage(message))

        assertEquals(message, decoded)
        decoded as PhoneToGlassesMessage.Lyrics
        val decodedWindow = (decoded.event as LyricsEvent.Window).snapshot
        assertEquals("spotify|track-1", decodedWindow.mediaKey)
        assertEquals(42L, decodedWindow.revision)
        assertEquals("SPOTIFY", decodedWindow.provider)
    }

    @Test
    fun lyricsScript_roundTripsThroughWireProtocol() {
        val script = LyricsScriptSnapshot(
            sessionState = LyricsSessionState.PLAYING,
            mediaKey = "spotify|track-1",
            revision = 42L,
            trackTitle = "Blinding Lights",
            artistName = "The Weeknd",
            provider = "MUSIXMATCH",
            progressMs = 15_416L,
            capturedAtEpochMs = 1_782_770_472_571L,
            currentLineIndex = 1,
            body = "31g\tYeah\n3aw\t(instrumental)\nddo\tI've been tryna call",
        )
        val message = PhoneToGlassesMessage.Lyrics(LyricsEvent.Script(script))

        val decoded = WireProtocol.decodePhoneMessageOrNull(WireProtocol.encodePhoneMessage(message))

        assertEquals(message, decoded)
        decoded as PhoneToGlassesMessage.Lyrics
        val decodedScript = (decoded.event as LyricsEvent.Script).snapshot
        assertEquals("spotify|track-1", decodedScript.mediaKey)
        assertEquals(42L, decodedScript.revision)
        assertEquals("MUSIXMATCH", decodedScript.provider)
        assertEquals(
            listOf(
                LyricsLine(startTimeMs = 3_940L, text = "Yeah"),
                LyricsLine(startTimeMs = 4_280L, text = "(instrumental)"),
                LyricsLine(startTimeMs = 17_340L, text = "I've been tryna call"),
            ),
            decodedScript.toLines(),
        )
    }

    @Test
    fun lyricsSync_roundTripsMediaIdentity() {
        val sync = LyricsPlaybackSync(
            sessionState = LyricsSessionState.PLAYING,
            mediaKey = "spotify|track-1",
            revision = 42L,
            progressMs = 12_000L,
            capturedAtEpochMs = 1_782_770_472_571L,
            currentLineIndex = 3,
        )
        val message = PhoneToGlassesMessage.Lyrics(LyricsEvent.Sync(sync))

        val decoded = WireProtocol.decodePhoneMessageOrNull(WireProtocol.encodePhoneMessage(message))

        assertEquals(message, decoded)
    }

    @Test
    fun compressedLyricsScript_decodesLines() {
        val body = "31g\tYeah\n3aw\t(instrumental)\nddo\tI've been tryna call"
        val compressedBody = ByteArrayOutputStream().use { output ->
            DeflaterOutputStream(output, Deflater(Deflater.DEFAULT_COMPRESSION, true)).use { stream ->
                stream.write(body.toByteArray(Charsets.UTF_8))
            }
            Base64.getEncoder().encodeToString(output.toByteArray())
        }
        val script = LyricsScriptSnapshot(
            encoding = LyricsScriptSnapshot.SCRIPT_ENCODING_ZLIB_BASE64,
            body = compressedBody,
        )

        assertEquals(
            listOf(
                LyricsLine(startTimeMs = 3_940L, text = "Yeah"),
                LyricsLine(startTimeMs = 4_280L, text = "(instrumental)"),
                LyricsLine(startTimeMs = 17_340L, text = "I've been tryna call"),
            ),
            script.toLines(),
        )
    }

    @Test
    fun mismatchedProtocolVersion_isDetectableInDecodedHandshake() {
        val message = GlassesToPhoneMessage.Hello(
            ProtocolHello(
                protocolVersion = TransportConstants.PROTOCOL_VERSION + 1,
                appVersion = "future-glasses",
            )
        )

        val decoded = WireProtocol.decodeGlassesMessageOrNull(WireProtocol.encodeGlassesMessage(message))

        assertTrue(decoded is GlassesToPhoneMessage.Hello)
        decoded as GlassesToPhoneMessage.Hello
        assertEquals(TransportConstants.PROTOCOL_VERSION + 1, decoded.hello.protocolVersion)
    }
}

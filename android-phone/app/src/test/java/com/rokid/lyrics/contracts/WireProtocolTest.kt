package com.rokid.lyrics.contracts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun phoneHelloAck_roundTripsThroughWireProtocol() {
        val message = PhoneToGlassesMessage.HelloAck(
            ProtocolHelloAck(
                protocolVersion = TransportConstants.PROTOCOL_VERSION,
                appVersion = "phone-0.1.0",
                capabilities = listOf("lyrics_snapshot", "lyrics_sync"),
            )
        )

        val decoded = WireProtocol.decodePhoneMessageOrNull(WireProtocol.encodePhoneMessage(message))

        assertEquals(message, decoded)
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

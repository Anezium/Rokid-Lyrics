package com.rokid.lyrics.phone

import com.rokid.lyrics.contracts.ConnectionState
import com.rokid.lyrics.contracts.DeviceStatus
import com.rokid.lyrics.contracts.LyricsSnapshot

data class LyricsPhoneViewState(
    val deviceStatus: DeviceStatus = DeviceStatus(
        connectionState = ConnectionState.CONNECTING,
        statusLabel = "Bluetooth server starting...",
    ),
    val lyrics: LyricsSnapshot = LyricsSnapshot(),
)

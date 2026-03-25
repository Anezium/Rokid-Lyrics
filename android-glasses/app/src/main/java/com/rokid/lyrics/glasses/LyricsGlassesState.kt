package com.rokid.lyrics.glasses

import com.rokid.lyrics.contracts.ConnectionState
import com.rokid.lyrics.contracts.LyricsLine
import com.rokid.lyrics.contracts.LyricsSessionState

data class LyricsGlassesState(
    val connectionState: ConnectionState = ConnectionState.CONNECTING,
    val statusLabel: String = "Searching for the phone over Bluetooth...",
    val lyricsSessionState: LyricsSessionState = LyricsSessionState.IDLE,
    val trackTitle: String = "",
    val artistName: String = "",
    val albumName: String = "",
    val provider: String = "LRCLIB",
    val sourceSummary: String = "Waiting for the phone runtime.",
    val progressMs: Long = 0L,
    val capturedAtEpochMs: Long = 0L,
    val receivedAtElapsedMs: Long = 0L,
    val currentLineIndex: Int = -1,
    val lines: List<LyricsLine> = emptyList(),
    val synced: Boolean = false,
    val plainLyrics: String = "",
    val errorMessage: String? = null,
)

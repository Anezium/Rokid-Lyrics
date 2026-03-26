package com.rokid.lyrics.glasses

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.rokid.lyrics.contracts.GlassesToPhoneMessage
import com.rokid.lyrics.contracts.LyricsEvent
import com.rokid.lyrics.contracts.LyricsLine
import com.rokid.lyrics.contracts.LyricsSessionState
import com.rokid.lyrics.contracts.PhoneToGlassesMessage
import com.rokid.lyrics.glasses.bluetooth.LyricsBluetoothBridge

private const val MAX_TRANSPORT_DELAY_COMPENSATION_MS = 5_000L

class LyricsGlassesStateStore(
    private val bridge: LyricsBluetoothBridge,
) {
    private val lock = Any()
    private val listeners = linkedSetOf<(LyricsGlassesState) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var state = LyricsGlassesState()

    init {
        bridge.subscribe(::handleMessage)
    }

    fun current(): LyricsGlassesState = state

    fun subscribe(listener: (LyricsGlassesState) -> Unit): () -> Unit {
        val snapshot = synchronized(lock) {
            listeners += listener
            state
        }
        dispatch(listener, snapshot)
        return { synchronized(lock) { listeners -= listener } }
    }

    fun requestSnapshot() {
        bridge.send(GlassesToPhoneMessage.RequestStatus)
        bridge.send(GlassesToPhoneMessage.RequestSnapshot)
    }

    fun togglePlayback() {
        bridge.send(GlassesToPhoneMessage.TogglePlayback)
    }

    private fun handleMessage(message: PhoneToGlassesMessage) {
        val nextState: LyricsGlassesState
        val listenersSnapshot: List<(LyricsGlassesState) -> Unit>
        synchronized(lock) {
            nextState = when (message) {
                is PhoneToGlassesMessage.Status -> state.copy(
                    connectionState = message.status.connectionState,
                    statusLabel = message.status.statusLabel,
                    errorMessage = message.status.lastError ?: state.errorMessage,
                )

                is PhoneToGlassesMessage.Lyrics -> handleLyricsEvent(message.event)

                is PhoneToGlassesMessage.Error -> state.copy(
                    statusLabel = message.message,
                    errorMessage = message.message,
                )
            }
            state = nextState
            listenersSnapshot = listeners.toList()
        }
        listenersSnapshot.forEach { listener -> dispatch(listener, nextState) }
    }

    private fun handleLyricsEvent(event: LyricsEvent): LyricsGlassesState = when (event) {
        is LyricsEvent.Snapshot -> {
            val receivedAt = SystemClock.elapsedRealtime()
            val normalizedProgress = normalizedProgress(
                progressMs = event.snapshot.progressMs,
                capturedAtEpochMs = event.snapshot.capturedAtEpochMs,
                sessionState = event.snapshot.sessionState,
            )
            state.copy(
                lyricsSessionState = event.snapshot.sessionState,
                trackTitle = event.snapshot.trackTitle,
                artistName = event.snapshot.artistName,
                albumName = event.snapshot.albumName,
                provider = event.snapshot.provider,
                sourceSummary = event.snapshot.sourceSummary,
                progressMs = normalizedProgress,
                capturedAtEpochMs = event.snapshot.capturedAtEpochMs,
                receivedAtElapsedMs = receivedAt,
                currentLineIndex = resolvedLineIndex(
                    lines = event.snapshot.lines,
                    synced = event.snapshot.synced,
                    progressMs = normalizedProgress,
                    fallbackIndex = event.snapshot.currentLineIndex,
                ),
                lines = event.snapshot.lines,
                synced = event.snapshot.synced,
                plainLyrics = event.snapshot.plainLyrics,
                errorMessage = event.snapshot.errorMessage,
            )
        }

        is LyricsEvent.Sync -> {
            val receivedAt = SystemClock.elapsedRealtime()
            val normalizedProgress = normalizedProgress(
                progressMs = event.sync.progressMs,
                capturedAtEpochMs = event.sync.capturedAtEpochMs,
                sessionState = event.sync.sessionState,
            )
            state.copy(
                lyricsSessionState = event.sync.sessionState,
                progressMs = normalizedProgress,
                capturedAtEpochMs = event.sync.capturedAtEpochMs,
                receivedAtElapsedMs = receivedAt,
                currentLineIndex = resolvedLineIndex(
                    lines = state.lines,
                    synced = state.synced,
                    progressMs = normalizedProgress,
                    fallbackIndex = event.sync.currentLineIndex,
                ),
            )
        }

        is LyricsEvent.Error -> state.copy(
            lyricsSessionState = LyricsSessionState.ERROR,
            errorMessage = event.message,
            statusLabel = event.message,
        )
    }

    private fun normalizedProgress(
        progressMs: Long,
        capturedAtEpochMs: Long,
        sessionState: LyricsSessionState,
    ): Long {
        if (sessionState != LyricsSessionState.PLAYING) {
            return progressMs.coerceAtLeast(0L)
        }
        val transportDelay = if (capturedAtEpochMs > 0L) {
            (System.currentTimeMillis() - capturedAtEpochMs).coerceIn(0L, MAX_TRANSPORT_DELAY_COMPENSATION_MS)
        } else {
            0L
        }
        return (progressMs + transportDelay).coerceAtLeast(0L)
    }

    private fun resolvedLineIndex(
        lines: List<LyricsLine>,
        synced: Boolean,
        progressMs: Long,
        fallbackIndex: Int,
    ): Int {
        if (!synced || lines.isEmpty()) return fallbackIndex
        var candidate = -1
        for (index in lines.indices) {
            if (lines[index].startTimeMs <= progressMs) {
                candidate = index
            } else {
                break
            }
        }
        return candidate
    }

    private fun dispatch(
        listener: (LyricsGlassesState) -> Unit,
        state: LyricsGlassesState,
    ) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listener(state)
        } else {
            mainHandler.post { listener(state) }
        }
    }
}

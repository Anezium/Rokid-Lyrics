package com.rokid.lyrics.glasses

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.rokid.lyrics.contracts.GlassesToPhoneMessage
import com.rokid.lyrics.contracts.LyricsEvent
import com.rokid.lyrics.contracts.LyricsLine
import com.rokid.lyrics.contracts.LyricsSessionState
import com.rokid.lyrics.contracts.PhoneToGlassesMessage
import com.rokid.lyrics.glasses.transport.LyricsPhoneBridge

class LyricsGlassesStateStore(
    private val bridge: LyricsPhoneBridge,
) {
    private data class StateUpdate(
        val state: LyricsGlassesState,
        val requestSnapshot: Boolean = false,
    )

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

    private fun handleMessage(message: PhoneToGlassesMessage) {
        val nextState: LyricsGlassesState
        val listenersSnapshot: List<(LyricsGlassesState) -> Unit>
        val shouldRequestSnapshot: Boolean
        synchronized(lock) {
            val previousState = state
            val update = when (message) {
                is PhoneToGlassesMessage.HelloAck -> StateUpdate(state)
                is PhoneToGlassesMessage.Status -> StateUpdate(
                    state.copy(
                        connectionState = message.status.connectionState,
                        statusLabel = message.status.statusLabel,
                        errorMessage = message.status.lastError,
                    )
                )

                is PhoneToGlassesMessage.Lyrics -> handleLyricsEvent(message.event)

                is PhoneToGlassesMessage.Error -> StateUpdate(
                    state.copy(
                        statusLabel = message.message,
                        errorMessage = message.message,
                    )
                )
            }
            nextState = update.state
            shouldRequestSnapshot = update.requestSnapshot
            if (nextState == previousState) {
                listenersSnapshot = emptyList()
            } else {
                state = nextState
                listenersSnapshot = listeners.toList()
            }
        }
        if (shouldRequestSnapshot) {
            requestSnapshot()
        }
        listenersSnapshot.forEach { listener -> dispatch(listener, nextState) }
    }

    private fun handleLyricsEvent(event: LyricsEvent): StateUpdate = when (event) {
        is LyricsEvent.Snapshot -> if (!shouldAcceptContentUpdate(event.snapshot.mediaKey, event.snapshot.revision)) {
            StateUpdate(state)
        } else {
            val receivedAt = SystemClock.elapsedRealtime()
            val normalizedProgress = normalizedProgress(
                progressMs = event.snapshot.progressMs,
            )
            StateUpdate(
                state.copy(
                    lyricsSessionState = event.snapshot.sessionState,
                    mediaKey = event.snapshot.mediaKey,
                    revision = event.snapshot.revision,
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
            )
        }

        is LyricsEvent.Window -> if (!shouldAcceptContentUpdate(event.snapshot.mediaKey, event.snapshot.revision)) {
            StateUpdate(state)
        } else {
            val receivedAt = SystemClock.elapsedRealtime()
            val lines = event.snapshot.lines.map { it.toLyricsLine() }
            val normalizedProgress = normalizedProgress(
                progressMs = event.snapshot.progressMs,
            )
            StateUpdate(
                state.copy(
                    lyricsSessionState = event.snapshot.sessionState,
                    mediaKey = event.snapshot.mediaKey,
                    revision = event.snapshot.revision,
                    trackTitle = event.snapshot.trackTitle,
                    artistName = event.snapshot.artistName,
                    albumName = "",
                    provider = event.snapshot.provider,
                    sourceSummary = "",
                    progressMs = normalizedProgress,
                    capturedAtEpochMs = event.snapshot.capturedAtEpochMs,
                    receivedAtElapsedMs = receivedAt,
                    currentLineIndex = resolvedLineIndex(
                        lines = lines,
                        synced = true,
                        progressMs = normalizedProgress,
                        fallbackIndex = event.snapshot.currentLineIndex,
                    ),
                    lines = lines,
                    synced = true,
                    plainLyrics = "",
                    errorMessage = null,
                )
            )
        }

        is LyricsEvent.Script -> if (!shouldAcceptContentUpdate(event.snapshot.mediaKey, event.snapshot.revision)) {
            StateUpdate(state)
        } else {
            val receivedAt = SystemClock.elapsedRealtime()
            val lines = event.snapshot.toLines()
            val normalizedProgress = normalizedProgress(
                progressMs = event.snapshot.progressMs,
            )
            StateUpdate(
                state.copy(
                    lyricsSessionState = event.snapshot.sessionState,
                    mediaKey = event.snapshot.mediaKey,
                    revision = event.snapshot.revision,
                    trackTitle = event.snapshot.trackTitle,
                    artistName = event.snapshot.artistName,
                    albumName = "",
                    provider = event.snapshot.provider,
                    sourceSummary = "",
                    progressMs = normalizedProgress,
                    capturedAtEpochMs = event.snapshot.capturedAtEpochMs,
                    receivedAtElapsedMs = receivedAt,
                    currentLineIndex = resolvedLineIndex(
                        lines = lines,
                        synced = true,
                        progressMs = normalizedProgress,
                        fallbackIndex = event.snapshot.currentLineIndex,
                    ),
                    lines = lines,
                    synced = true,
                    plainLyrics = "",
                    errorMessage = null,
                )
            )
        }

        is LyricsEvent.Sync -> handleSyncEvent(event.sync)

        is LyricsEvent.Error -> StateUpdate(
            state.copy(
                lyricsSessionState = LyricsSessionState.ERROR,
                errorMessage = event.message,
                statusLabel = event.message,
            )
        )
    }

    private fun handleSyncEvent(sync: com.rokid.lyrics.contracts.LyricsPlaybackSync): StateUpdate {
        if (sync.mediaKey.isNotBlank() && sync.mediaKey != state.mediaKey) {
            return if (isPotentiallyNewerMedia(sync.mediaKey, sync.revision, sync.capturedAtEpochMs)) {
                StateUpdate(loadingStateForMissingMedia(sync), requestSnapshot = true)
            } else {
                StateUpdate(state)
            }
        }
        if (!shouldAcceptSync(sync)) {
            return StateUpdate(state)
        }

        val receivedAt = SystemClock.elapsedRealtime()
        val normalizedProgress = normalizedProgress(
            progressMs = sync.progressMs,
        )
        return StateUpdate(
            state.copy(
                lyricsSessionState = sync.sessionState,
                mediaKey = sync.mediaKey.ifBlank { state.mediaKey },
                revision = maxOf(state.revision, sync.revision),
                progressMs = normalizedProgress,
                capturedAtEpochMs = sync.capturedAtEpochMs,
                receivedAtElapsedMs = receivedAt,
                currentLineIndex = resolvedLineIndex(
                    lines = state.lines,
                    synced = state.synced,
                    progressMs = normalizedProgress,
                    fallbackIndex = sync.currentLineIndex,
                ),
            )
        )
    }

    private fun loadingStateForMissingMedia(sync: com.rokid.lyrics.contracts.LyricsPlaybackSync): LyricsGlassesState {
        val receivedAt = SystemClock.elapsedRealtime()
        val normalizedProgress = normalizedProgress(
            progressMs = sync.progressMs,
        )
        return state.copy(
            lyricsSessionState = LyricsSessionState.LOADING,
            mediaKey = sync.mediaKey,
            revision = maxOf(state.revision, sync.revision),
            trackTitle = "",
            artistName = "",
            albumName = "",
            provider = "",
            sourceSummary = "",
            progressMs = normalizedProgress,
            capturedAtEpochMs = sync.capturedAtEpochMs,
            receivedAtElapsedMs = receivedAt,
            currentLineIndex = -1,
            lines = emptyList(),
            synced = false,
            plainLyrics = "",
            errorMessage = null,
        )
    }

    private fun shouldAcceptContentUpdate(
        mediaKey: String,
        revision: Long,
    ): Boolean {
        if (mediaKey.isBlank()) return true
        if (state.mediaKey.isBlank()) return true
        if (mediaKey != state.mediaKey) {
            return state.revision <= 0L || revision >= state.revision
        }
        if (revision <= 0L) return true
        return revision >= state.revision
    }

    private fun shouldAcceptSync(sync: com.rokid.lyrics.contracts.LyricsPlaybackSync): Boolean {
        if (sync.mediaKey.isBlank()) {
            if (state.mediaKey.isNotBlank()) return false
        } else if (state.mediaKey.isBlank() || sync.mediaKey != state.mediaKey) {
            return false
        }
        if (sync.capturedAtEpochMs <= 0L || state.capturedAtEpochMs <= 0L) return true
        return sync.capturedAtEpochMs >= state.capturedAtEpochMs
    }

    private fun isPotentiallyNewerMedia(
        mediaKey: String,
        revision: Long,
        capturedAtEpochMs: Long,
    ): Boolean {
        if (mediaKey.isBlank()) return false
        if (state.mediaKey.isBlank()) return true
        if (revision > 0L && state.revision > 0L) return revision >= state.revision
        if (state.revision > 0L) return false
        return capturedAtEpochMs <= 0L || capturedAtEpochMs >= state.capturedAtEpochMs
    }

    private fun normalizedProgress(progressMs: Long): Long {
        return progressMs.coerceAtLeast(0L)
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

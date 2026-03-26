package com.rokid.lyrics.phone.lyrics

import android.os.SystemClock
import com.rokid.lyrics.contracts.LyricsLine
import com.rokid.lyrics.contracts.LyricsSessionState
import com.rokid.lyrics.contracts.LyricsSnapshot
import com.rokid.lyrics.phone.LyricsPhoneStateStore
import com.rokid.lyrics.phone.media.MediaPlaybackSnapshot
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LyricsRuntimeEngine(
    private val stateStore: LyricsPhoneStateStore,
    private val lyricsClient: LrcLibLyricsClient = LrcLibLyricsClient(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lookupJob: Job? = null
    private var activeMediaSnapshot: MediaPlaybackSnapshot? = null
    private var activeMediaLookupKey: String? = null
    @Volatile private var lookupInFlightKey: String? = null
    private val lookupGeneration = AtomicLong(0L)
    @Volatile private var lastLookupErrorKey: String? = null
    @Volatile private var lastLookupErrorAtMs: Long = 0L

    fun destroy() {
        lookupJob?.cancel()
        scope.cancel()
    }

    fun refresh() {
        val mediaSnapshot = activeMediaSnapshot
        if (mediaSnapshot != null) {
            lookup(
                request = LyricsLookupRequest(
                    title = mediaSnapshot.title,
                    artist = mediaSnapshot.artist,
                    album = mediaSnapshot.album,
                    durationSeconds = mediaSnapshot.durationMs?.div(1000L)?.toInt(),
                ),
                mediaKey = mediaLookupKey(mediaSnapshot),
                fromMedia = true,
                force = true,
            )
            return
        }

        val current = stateStore.current().lyrics
        if (current.trackTitle.isBlank() || current.artistName.isBlank()) {
            stateStore.updateStatus { it.copy(statusLabel = "Nothing to refresh yet. Start Spotify on the phone.") }
            return
        }
        lookup(
            request = LyricsLookupRequest(
                title = current.trackTitle,
                artist = current.artistName,
                album = current.albumName,
                durationSeconds = current.durationSeconds,
            ),
            mediaKey = activeMediaLookupKey,
            fromMedia = activeMediaSnapshot != null,
            force = true,
        )
    }

    fun onMediaStatus(message: String) {
        stateStore.updateStatus { current ->
            current.copy(statusLabel = message)
        }
    }

    fun onMediaPlaybackSnapshot(snapshot: MediaPlaybackSnapshot?) {
        if (snapshot == null) {
            activeMediaSnapshot = null
            activeMediaLookupKey = null
            cancelLookup()
            val current = stateStore.current().lyrics
            if (current.trackTitle.isBlank()) {
                stateStore.updateLyrics(
                    current.copy(
                        sessionState = LyricsSessionState.IDLE,
                        sourceSummary = "Start Spotify or another media app on the phone.",
                        errorMessage = null,
                    )
                )
            } else {
                stateStore.updateLyrics(
                    current.copy(
                        sessionState = if (current.lines.isEmpty()) LyricsSessionState.IDLE else LyricsSessionState.READY,
                        sourceSummary = "No active media session. Resume playback on the phone.",
                        errorMessage = current.errorMessage,
                    )
                )
            }
            return
        }

        activeMediaSnapshot = snapshot
        val current = stateStore.current().lyrics
        val lookupKey = mediaLookupKey(snapshot)
        val matchesLoaded = normalized(current.trackTitle) == normalized(snapshot.title) &&
            normalized(current.artistName) == normalized(snapshot.artist)
        if (shouldLookupForCurrentTrack(current, lookupKey, matchesLoaded)) {
            activeMediaLookupKey = lookupKey
            lookup(
                request = LyricsLookupRequest(
                    title = snapshot.title,
                    artist = snapshot.artist,
                    album = snapshot.album,
                    durationSeconds = snapshot.durationMs?.div(1000L)?.toInt(),
                ),
                mediaKey = lookupKey,
                fromMedia = true,
            )
            return
        }

        stateStore.updateStatus {
            it.copy(statusLabel = "Tracking ${sourceLabel(snapshot.packageName)} - ${snapshot.title} / ${snapshot.artist}")
        }
        stateStore.updateLyrics(applyMediaSnapshot(current, snapshot))
    }

    private fun lookup(
        request: LyricsLookupRequest,
        mediaKey: String?,
        fromMedia: Boolean,
        force: Boolean = false,
    ) {
        val title = request.title.trim()
        val artist = request.artist.trim()
        if (title.isBlank() || artist.isBlank()) {
            stateStore.updateLyrics(
                stateStore.current().lyrics.copy(
                    sessionState = LyricsSessionState.ERROR,
                    errorMessage = "Lyrics lookup requires both title and artist.",
                    sourceSummary = "The current media session does not expose enough metadata.",
                )
            )
            return
        }

        val normalizedRequest = request.copy(
            title = title,
            artist = artist,
            album = request.album.trim(),
        )
        val requestKey = mediaKey ?: lookupRequestKey(normalizedRequest)
        if (!force && requestKey == lookupInFlightKey) {
            return
        }

        val generation = lookupGeneration.incrementAndGet()
        lookupInFlightKey = requestKey
        lookupJob?.cancel()

        stateStore.updateLyrics(
            stateStore.current().lyrics.copy(
                sessionState = LyricsSessionState.LOADING,
                trackTitle = title,
                artistName = artist,
                albumName = normalizedRequest.album,
                durationSeconds = request.durationSeconds,
                errorMessage = null,
                sourceSummary = if (fromMedia) {
                    "Resolving lyrics for ${sourceLabel(activeMediaSnapshot?.packageName)}..."
                } else {
                    "Querying LRCLIB..."
                },
            )
        )

        lookupJob = scope.launch {
            try {
                val result = lyricsClient.fetch(normalizedRequest)
                if (!shouldCommitLookup(generation, requestKey, mediaKey)) {
                    return@launch
                }
                var next = LyricsSnapshot(
                    sessionState = LyricsSessionState.READY,
                    trackTitle = result.trackTitle,
                    artistName = result.artistName,
                    albumName = result.albumName,
                    durationSeconds = result.durationSeconds,
                    provider = result.provider,
                    sourceSummary = result.sourceSummary,
                    synced = result.synced,
                    progressMs = 0L,
                    currentLineIndex = initialLineIndex(result.lines),
                    lines = result.lines,
                    plainLyrics = result.plainLyrics,
                    errorMessage = null,
                )
                val latestMedia = activeMediaSnapshot
                if (mediaKey != null && latestMedia != null && mediaLookupKey(latestMedia) == mediaKey) {
                    next = applyMediaSnapshot(next, latestMedia)
                }
                clearLookupError(requestKey)
                stateStore.updateLyrics(next)
                stateStore.updateStatus {
                    it.copy(
                        statusLabel = if (next.synced) {
                            "Lyrics loaded for ${next.trackTitle}."
                        } else {
                            "Track resolved without timed lyrics."
                        },
                        lastError = null,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!shouldCommitLookup(generation, requestKey, mediaKey)) {
                    return@launch
                }
                markLookupError(requestKey)
                stateStore.updateLyrics(
                    stateStore.current().lyrics.copy(
                        sessionState = LyricsSessionState.ERROR,
                        errorMessage = error.message ?: "Lyrics lookup failed.",
                        sourceSummary = "LRCLIB did not return a usable lyrics payload.",
                        lines = emptyList(),
                        plainLyrics = "",
                        progressMs = 0L,
                        currentLineIndex = -1,
                        synced = false,
                    )
                )
                stateStore.updateStatus {
                    it.copy(
                        statusLabel = "Lyrics lookup failed for $title / $artist.",
                        lastError = error.message,
                    )
                }
            } finally {
                if (lookupGeneration.get() == generation) {
                    lookupInFlightKey = null
                }
            }
        }
    }

    private fun shouldLookupForCurrentTrack(
        current: LyricsSnapshot,
        lookupKey: String,
        matchesLoaded: Boolean,
    ): Boolean {
        if (lookupKey != activeMediaLookupKey) return true
        if (lookupInFlightKey == lookupKey) return false
        if (!matchesLoaded) return true
        if (current.sessionState == LyricsSessionState.ERROR) {
            return canRetryFailedLookup(lookupKey)
        }
        if (current.lines.isNotEmpty() || current.plainLyrics.isNotBlank()) return false
        return current.sessionState == LyricsSessionState.IDLE
    }

    private fun cancelLookup() {
        lookupGeneration.incrementAndGet()
        lookupInFlightKey = null
        lookupJob?.cancel()
    }

    private fun shouldCommitLookup(
        generation: Long,
        requestKey: String,
        mediaKey: String?,
    ): Boolean {
        if (lookupGeneration.get() != generation) return false
        if (lookupInFlightKey != requestKey) return false
        if (mediaKey == null) return true
        if (activeMediaLookupKey != mediaKey) return false
        val latestMedia = activeMediaSnapshot ?: return false
        return mediaLookupKey(latestMedia) == mediaKey
    }

    private fun markLookupError(requestKey: String) {
        lastLookupErrorKey = requestKey
        lastLookupErrorAtMs = SystemClock.elapsedRealtime()
    }

    private fun clearLookupError(requestKey: String) {
        if (lastLookupErrorKey == requestKey) {
            lastLookupErrorKey = null
            lastLookupErrorAtMs = 0L
        }
    }

    private fun canRetryFailedLookup(requestKey: String): Boolean {
        if (lastLookupErrorKey != requestKey) return true
        return SystemClock.elapsedRealtime() - lastLookupErrorAtMs >= LOOKUP_ERROR_RETRY_MS
    }

    private fun lookupRequestKey(request: LyricsLookupRequest): String =
        listOf(
            normalized(request.title),
            normalized(request.artist),
            normalized(request.album),
            request.durationSeconds?.toString().orEmpty(),
        ).joinToString("|")

    private fun initialLineIndex(@Suppress("UNUSED_PARAMETER") lines: List<LyricsLine>): Int = -1

    private fun applyMediaSnapshot(
        base: LyricsSnapshot,
        snapshot: MediaPlaybackSnapshot,
    ): LyricsSnapshot {
        val progressMs = snapshot.positionMs.coerceAtLeast(0L)
        return base.copy(
            sessionState = if (snapshot.isPlaying) LyricsSessionState.PLAYING else LyricsSessionState.READY,
            progressMs = progressMs,
            currentLineIndex = indexForProgress(base.lines, progressMs),
            sourceSummary = "Tracking ${sourceLabel(snapshot.packageName)} via Android media session.",
            errorMessage = null,
        )
    }

    private fun indexForProgress(lines: List<LyricsLine>, progressMs: Long): Int {
        if (lines.isEmpty()) return -1
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

    private fun mediaLookupKey(snapshot: MediaPlaybackSnapshot): String =
        listOf(
            snapshot.packageName,
            normalized(snapshot.title),
            normalized(snapshot.artist),
            normalized(snapshot.album),
            snapshot.durationMs?.div(1000L)?.toString().orEmpty(),
        ).joinToString("|")

    private fun normalized(value: String): String =
        value.trim().lowercase()

    private fun sourceLabel(packageName: String?): String = when (packageName) {
        "com.spotify.music" -> "Spotify"
        null, "" -> "media"
        else -> packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    private companion object {
        private const val LOOKUP_ERROR_RETRY_MS = 15_000L
    }
}

package com.rokid.lyrics.glasses

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.rokid.lyrics.contracts.MediaPlaybackHint
import kotlin.math.abs

class GlassesMediaHintMonitor(
    context: Context,
    private val onHint: (MediaPlaybackHint) -> Unit,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionManager = appContext.getSystemService(MediaSessionManager::class.java)
    private var running = false
    private var disabledBySecurity = false
    private var lastEmittedKey: String? = null
    private var lastEmittedProgressMs = Long.MIN_VALUE

    private val poller = object : Runnable {
        override fun run() {
            if (!running) return
            poll()
            if (running && !disabledBySecurity) {
                mainHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    fun start() {
        if (running || disabledBySecurity) return
        running = true
        mainHandler.removeCallbacks(poller)
        mainHandler.post(poller)
    }

    fun stop() {
        running = false
        mainHandler.removeCallbacks(poller)
    }

    private fun poll() {
        val controller = activeBluetoothController() ?: return
        val metadata = controller.metadata ?: return
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().trim()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            .orEmpty()
            .ifBlank { metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty() }
            .trim()
        if (title.isBlank() || artist.isBlank()) return

        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty().trim()
        val durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0L }
        val playbackState = controller.playbackState
        val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
        val progressMs = playbackState?.effectiveProgressMs(isPlaying).orZero()
        val durationSeconds = durationMs?.let { (it / 1000L).toInt() }
        val key = listOf(title, artist, album, durationSeconds?.toString().orEmpty(), isPlaying.toString())
            .joinToString("|")

        if (key == lastEmittedKey && abs(progressMs - lastEmittedProgressMs) < PROGRESS_EMIT_DELTA_MS) {
            return
        }
        lastEmittedKey = key
        lastEmittedProgressMs = progressMs
        onHint(
            MediaPlaybackHint(
                source = "GLASSES_AVRCP",
                title = title,
                artistName = artist,
                albumName = album,
                durationSeconds = durationSeconds,
                progressMs = progressMs,
                capturedAtEpochMs = System.currentTimeMillis(),
                isPlaying = isPlaying,
            )
        )
    }

    private fun activeBluetoothController(): MediaController? {
        val sessions = try {
            sessionManager.getActiveSessions(null)
        } catch (error: SecurityException) {
            disabledBySecurity = true
            running = false
            Log.w(TAG, "Media session access denied; falling back to iPhone Spotify polling.")
            return null
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to read media sessions.", error)
            return null
        }
        return sessions.firstOrNull { it.packageName == BLUETOOTH_PACKAGE && it.metadata != null }
            ?: sessions.firstOrNull { it.metadata != null }
    }

    private fun PlaybackState.effectiveProgressMs(isPlaying: Boolean): Long {
        val base = position.coerceAtLeast(0L)
        if (!isPlaying || lastPositionUpdateTime <= 0L) return base
        val elapsedMs = (SystemClock.elapsedRealtime() - lastPositionUpdateTime).coerceAtLeast(0L)
        val adjusted = base + (elapsedMs * playbackSpeed.coerceAtLeast(0f)).toLong()
        return adjusted.coerceAtLeast(0L)
    }

    private fun Long?.orZero(): Long = this ?: 0L

    private companion object {
        private const val TAG = "RokidLyricsMediaHint"
        private const val BLUETOOTH_PACKAGE = "com.android.bluetooth"
        private const val POLL_INTERVAL_MS = 750L
        private const val PROGRESS_EMIT_DELTA_MS = 2_000L
    }
}

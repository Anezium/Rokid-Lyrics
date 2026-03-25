package com.rokid.lyrics.glasses

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.rokid.lyrics.contracts.ConnectionState
import com.rokid.lyrics.contracts.LyricsLine
import com.rokid.lyrics.contracts.LyricsSessionState

class LyricsHudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val titleView: TextView
    private val metaView: TextView
    private val stateView: TextView
    private val currentLineView: TextView
    private val nextLineView: TextView
    private val summaryView: TextView
    private val hintView: TextView

    private var lastRenderedState: LyricsGlassesState? = null
    private var playbackClockTrackKey: String? = null
    private var playbackClockState: LyricsSessionState = LyricsSessionState.IDLE
    private var playbackClockBaseProgressMs: Long = 0L
    private var playbackClockAnchorElapsedMs: Long = 0L

    private val playbackTicker = object : Runnable {
        override fun run() {
            val state = lastRenderedState ?: return
            if (!shouldAnimate(state)) return
            renderContent(state)
            postDelayed(this, PLAYBACK_TICK_MS)
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#99000000"))
        setPadding(px(14), px(12), px(14), px(12))

        titleView = monoText(20f, Color.WHITE).apply {
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        }
        metaView = monoText(12f, COLOR_DIM).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = px(4)
            }
        }
        stateView = monoText(12f, COLOR_SECONDARY).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = px(10)
                bottomMargin = px(10)
            }
        }
        currentLineView = monoText(22f, COLOR_PRIMARY).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = px(18)
            }
        }
        nextLineView = monoText(17f, COLOR_SECONDARY).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = px(16)
            }
        }
        summaryView = monoText(13f, COLOR_DIM).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = px(18)
            }
        }
        hintView = monoText(11f, COLOR_DIM).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = px(18)
            }
        }

        addView(titleView)
        addView(metaView)
        addView(divider())
        addView(stateView)
        addView(currentLineView)
        addView(nextLineView)
        addView(summaryView)
        addView(hintView)
    }

    fun render(state: LyricsGlassesState) {
        visibility = VISIBLE
        reconcilePlaybackClock(state)
        lastRenderedState = state
        removeCallbacks(playbackTicker)
        renderContent(state)
        if (shouldAnimate(state)) {
            postDelayed(playbackTicker, PLAYBACK_TICK_MS)
        }
    }

    private fun renderContent(state: LyricsGlassesState) {
        titleView.text = when {
            state.trackTitle.isNotBlank() && state.artistName.isNotBlank() ->
                "${state.trackTitle}  /  ${state.artistName}"
            state.trackTitle.isNotBlank() -> state.trackTitle
            else -> "Lyrics"
        }

        metaView.text = buildString {
            append(state.provider.ifBlank { "LRCLIB" })
            if (state.synced) append("  synced")
            if (state.albumName.isNotBlank()) append("  /  ${state.albumName}")
        }

        stateView.text = buildStateLabel(state)

        val body = bodyCopy(state)
        currentLineView.text = body.currentLine
        nextLineView.text = body.nextLine
        summaryView.text = body.summary
        hintView.text = body.hint
    }

    private fun bodyCopy(state: LyricsGlassesState): BodyCopy {
        if (state.connectionState != ConnectionState.CONNECTED && state.trackTitle.isBlank()) {
            return BodyCopy(
                currentLine = "Waiting for the phone Bluetooth link.",
                nextLine = "Pair the phone app, then start playback.",
                summary = state.statusLabel,
                hint = "Enter = refresh from phone",
            )
        }

        if (state.lyricsSessionState == LyricsSessionState.LOADING) {
            return BodyCopy(
                currentLine = "Loading lyrics...",
                nextLine = "Querying LRCLIB from the phone.",
                summary = state.sourceSummary,
                hint = "Enter = retry lookup",
            )
        }

        state.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
            return BodyCopy(
                currentLine = error,
                nextLine = "Press Enter to retry the lookup.",
                summary = state.sourceSummary.ifBlank { state.statusLabel },
                hint = "Back = exit",
            )
        }

        if (state.synced && state.lines.isNotEmpty()) {
            val resolvedIndex = resolvedCurrentLineIndex(state)
            val currentLine = state.lines.getOrNull(resolvedIndex)?.text
                ?.takeIf { it.isNotBlank() }
                ?: "Waiting for the first line..."
            val nextLine = state.lines.getOrNull(resolvedIndex + 1)?.text
                ?.takeIf { it.isNotBlank() }
                ?: "Next line will appear here."
            return BodyCopy(
                currentLine = currentLine,
                nextLine = nextLine,
                summary = state.sourceSummary,
                hint = "Enter = refresh from phone  /  Back = exit",
            )
        }

        val plainLines = state.plainLyrics.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (plainLines.isNotEmpty()) {
            return BodyCopy(
                currentLine = plainLines.first(),
                nextLine = plainLines.getOrNull(1) ?: "Untimed lyrics only for this track.",
                summary = state.sourceSummary,
                hint = "No synced timestamps for this track",
            )
        }

        if (state.trackTitle.isBlank()) {
            return BodyCopy(
                currentLine = "Start music on the phone.",
                nextLine = "This app mirrors the phone player over Bluetooth.",
                summary = state.statusLabel,
                hint = "Enter = refresh from phone",
            )
        }

        return BodyCopy(
            currentLine = "No synced lyrics for this track.",
            nextLine = "Try another song or refresh the lookup.",
            summary = state.sourceSummary,
            hint = "Enter = retry lookup",
        )
    }

    private fun buildStateLabel(state: LyricsGlassesState): String {
        val transport = when (state.connectionState) {
            ConnectionState.CONNECTED -> "Bluetooth connected"
            ConnectionState.CONNECTING -> "Bluetooth connecting"
            ConnectionState.DISCONNECTED -> "Bluetooth offline"
        }
        val playback = when (state.lyricsSessionState) {
            LyricsSessionState.IDLE -> "Idle"
            LyricsSessionState.LOADING -> "Loading"
            LyricsSessionState.READY -> "Ready"
            LyricsSessionState.PLAYING -> "Playing"
            LyricsSessionState.ERROR -> "Error"
        }
        return "$transport  /  $playback"
    }

    private fun shouldAnimate(state: LyricsGlassesState): Boolean =
        state.lyricsSessionState == LyricsSessionState.PLAYING &&
            state.synced &&
            state.lines.isNotEmpty()

    private fun resolvedCurrentLineIndex(state: LyricsGlassesState): Int {
        if (!state.synced || state.lines.isEmpty()) return state.currentLineIndex
        return indexForProgress(state.lines, effectiveProgressMs(state))
    }

    private fun effectiveProgressMs(state: LyricsGlassesState): Long =
        when (playbackClockState) {
            LyricsSessionState.PLAYING -> localPlaybackProgressMs(SystemClock.elapsedRealtime())
            else -> playbackClockBaseProgressMs
        }

    private fun reconcilePlaybackClock(state: LyricsGlassesState) {
        val now = SystemClock.elapsedRealtime()
        val trackKey = playbackTrackKey(state)
        if (trackKey == null || !state.synced || state.lines.isEmpty()) {
            applyPlaybackClockSnapshot(state, trackKey, now)
            return
        }
        if (playbackClockTrackKey != trackKey) {
            applyPlaybackClockSnapshot(state, trackKey, now)
            return
        }
        if (state.lyricsSessionState != LyricsSessionState.PLAYING) {
            applyPlaybackClockSnapshot(state, trackKey, now)
            return
        }
        if (playbackClockState != LyricsSessionState.PLAYING || playbackClockAnchorElapsedMs <= 0L) {
            applyPlaybackClockSnapshot(state, trackKey, now)
            return
        }

        val predictedNow = localPlaybackProgressMs(now)
        val driftMs = state.progressMs - predictedNow
        val incomingIndex = state.currentLineIndex
        val predictedIndex = indexForProgress(state.lines, predictedNow)
        val backwardsSeek = incomingIndex >= 0 && predictedIndex >= 0 && incomingIndex + 1 < predictedIndex

        when {
            backwardsSeek || driftMs <= -HARD_RESYNC_THRESHOLD_MS || driftMs >= HARD_RESYNC_THRESHOLD_MS -> {
                applyPlaybackClockSnapshot(state, trackKey, now)
            }

            driftMs >= SOFT_FORWARD_CORRECTION_MS -> {
                playbackClockBaseProgressMs = predictedNow + (driftMs / 2L)
                playbackClockAnchorElapsedMs = now
                playbackClockState = state.lyricsSessionState
            }

            else -> playbackClockState = state.lyricsSessionState
        }
    }

    private fun applyPlaybackClockSnapshot(
        state: LyricsGlassesState,
        trackKey: String?,
        now: Long,
    ) {
        playbackClockTrackKey = trackKey
        playbackClockState = state.lyricsSessionState
        playbackClockBaseProgressMs = state.progressMs
        playbackClockAnchorElapsedMs = state.receivedAtElapsedMs.takeIf { it > 0L } ?: now
    }

    private fun localPlaybackProgressMs(now: Long): Long {
        val elapsed = (now - playbackClockAnchorElapsedMs).coerceIn(0L, MAX_LOCAL_PLAYBACK_GAP_MS)
        return playbackClockBaseProgressMs + elapsed
    }

    private fun playbackTrackKey(state: LyricsGlassesState): String? {
        if (state.lines.isEmpty() && state.plainLyrics.isBlank()) return null
        val lastLineStartMs = state.lines.lastOrNull()?.startTimeMs ?: 0L
        return listOf(
            state.trackTitle,
            state.artistName,
            state.albumName,
            state.provider,
            state.lines.size.toString(),
            lastLineStartMs.toString(),
            state.plainLyrics.take(64),
        ).joinToString("|")
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

    private fun divider() = View(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, px(1))
        setBackgroundColor(COLOR_DIVIDER)
    }

    private fun monoText(sizeSp: Float, color: Int) = TextView(context).apply {
        textSize = sizeSp
        setTextColor(color)
        typeface = Typeface.MONOSPACE
    }

    private fun px(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    private data class BodyCopy(
        val currentLine: String,
        val nextLine: String,
        val summary: String,
        val hint: String,
    )

    private companion object {
        private const val PLAYBACK_TICK_MS = 80L
        private const val MAX_LOCAL_PLAYBACK_GAP_MS = 60_000L
        private const val HARD_RESYNC_THRESHOLD_MS = 2_000L
        private const val SOFT_FORWARD_CORRECTION_MS = 400L
        private val COLOR_PRIMARY = Color.parseColor("#FFE7A3")
        private val COLOR_SECONDARY = Color.parseColor("#C8A85B")
        private val COLOR_DIM = Color.parseColor("#7A6A4A")
        private val COLOR_DIVIDER = Color.parseColor("#3A2B14")
    }
}

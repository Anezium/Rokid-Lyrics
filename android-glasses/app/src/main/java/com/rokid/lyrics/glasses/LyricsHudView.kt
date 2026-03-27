package com.rokid.lyrics.glasses

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.text.Layout
import android.text.TextUtils
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
    private val headerContainer: LinearLayout
    private val titleView: TextView
    private val metaView: TextView
    private val stateView: TextView
    private val currentLineView: TextView
    private val nextLineView: TextView
    private val bottomSpacer: View
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
        gravity = Gravity.TOP
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(px(12), px(8), px(12), px(6))

        headerContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        titleView = monoText(18f, Color.WHITE).apply {
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        metaView = monoText(11f, COLOR_DIM).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = px(2)
            }
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        stateView = monoText(11f, COLOR_SECONDARY).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = px(6)
            }
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        currentLineView = monoText(24f, COLOR_PRIMARY).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            textAlignment = TEXT_ALIGNMENT_CENTER
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = px(14)
            }
            maxLines = 5
            ellipsize = TextUtils.TruncateAt.END
        }
        nextLineView = monoText(18f, COLOR_SECONDARY).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            textAlignment = TEXT_ALIGNMENT_CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = px(10)
            }
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        bottomSpacer = View(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }
        hintView = monoText(10.5f, COLOR_HINT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            textAlignment = TEXT_ALIGNMENT_CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = px(12)
            }
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        headerContainer.addView(titleView)
        headerContainer.addView(metaView)
        headerContainer.addView(stateView)

        addView(headerContainer)
        addView(currentLineView)
        addView(nextLineView)
        addView(bottomSpacer)
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

    fun suspendTicker() {
        removeCallbacks(playbackTicker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(playbackTicker)
        super.onDetachedFromWindow()
    }

    private fun renderContent(state: LyricsGlassesState) {
        val title = buildTitle(state)
        val meta = buildMeta(state)
        val status = buildStatus(state)
        val body = bodyCopy(state)

        titleView.text = title
        titleView.visibility = if (title.isBlank()) GONE else VISIBLE

        metaView.text = meta
        metaView.visibility = if (meta.isBlank()) GONE else VISIBLE

        stateView.text = status
        stateView.visibility = if (status.isBlank()) GONE else VISIBLE
        headerContainer.visibility =
            if (titleView.visibility == GONE && metaView.visibility == GONE && stateView.visibility == GONE) {
                GONE
            } else {
                VISIBLE
            }

        currentLineView.text = body.currentLine
        nextLineView.text = body.nextLine
        nextLineView.visibility = if (body.nextLine.isBlank()) GONE else VISIBLE
        hintView.text = body.hint
    }

    private fun buildTitle(state: LyricsGlassesState): String = when {
        state.trackTitle.isNotBlank() && state.artistName.isNotBlank() ->
            "${state.trackTitle}  /  ${state.artistName}"
        state.trackTitle.isNotBlank() -> state.trackTitle
        else -> ""
    }

    private fun buildMeta(state: LyricsGlassesState): String {
        if (state.trackTitle.isBlank() && state.artistName.isBlank()) return ""
        return buildString {
            append(state.provider.ifBlank { "LRCLIB" })
            if (state.synced) append("  synced")
            if (state.albumName.isNotBlank()) append("  /  ${state.albumName}")
        }
    }

    private fun buildStatus(state: LyricsGlassesState): String {
        return when {
            state.connectionState != ConnectionState.CONNECTED -> state.statusLabel
            state.lyricsSessionState == LyricsSessionState.LOADING -> "Loading lyrics..."
            else -> ""
        }
    }

    private fun bodyCopy(state: LyricsGlassesState): BodyCopy {
        state.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
            return BodyCopy(
                currentLine = error,
                nextLine = "Playback control still works from the glasses.",
                hint = CONTROL_HINT,
            )
        }

        if (state.connectionState != ConnectionState.CONNECTED && state.trackTitle.isBlank()) {
            return BodyCopy(
                currentLine = "Waiting for the phone Bluetooth link.",
                nextLine = "Open the phone app, then start playback.",
                hint = CONTROL_HINT,
            )
        }

        if (state.lyricsSessionState == LyricsSessionState.LOADING) {
            return BodyCopy(
                currentLine = "Loading lyrics...",
                nextLine = "",
                hint = CONTROL_HINT,
            )
        }

        if (state.synced && state.lines.isNotEmpty()) {
            val resolvedIndex = resolvedCurrentLineIndex(state)
            val currentLine = state.lines.getOrNull(resolvedIndex)?.text
                ?.takeIf { it.isNotBlank() }
                ?: "Waiting for the first line..."
            val nextLine = state.lines.getOrNull(resolvedIndex + 1)?.text
                ?.takeIf { it.isNotBlank() }
                .orEmpty()
            return BodyCopy(
                currentLine = currentLine,
                nextLine = nextLine,
                hint = CONTROL_HINT,
            )
        }

        val plainLines = state.plainLyrics.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (plainLines.isNotEmpty()) {
            return BodyCopy(
                currentLine = plainLines.first(),
                nextLine = plainLines.getOrNull(1).orEmpty(),
                hint = CONTROL_HINT,
            )
        }

        if (state.trackTitle.isBlank()) {
            return BodyCopy(
                currentLine = "Start music on the phone.",
                nextLine = "The glasses only wake up the Bluetooth link while this screen is open.",
                hint = CONTROL_HINT,
            )
        }

        return BodyCopy(
            currentLine = "No synced lyrics for this track.",
            nextLine = "Try another song or keep playback on the phone.",
            hint = CONTROL_HINT,
        )
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

    private fun monoText(sizeSp: Float, color: Int) = TextView(context).apply {
        textSize = sizeSp
        setTextColor(color)
        typeface = Typeface.MONOSPACE
        includeFontPadding = false
        isSingleLine = false
        setHorizontallyScrolling(false)
        setShadowLayer(shadowRadiusPx(), 0f, 0f, COLOR_SHADOW)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            breakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        }
    }

    private fun px(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun shadowRadiusPx(): Float =
        6f * resources.displayMetrics.density

    private data class BodyCopy(
        val currentLine: String,
        val nextLine: String,
        val hint: String,
    )

    private companion object {
        private const val PLAYBACK_TICK_MS = 80L
        private const val MAX_LOCAL_PLAYBACK_GAP_MS = 60_000L
        private const val HARD_RESYNC_THRESHOLD_MS = 2_000L
        private const val SOFT_FORWARD_CORRECTION_MS = 400L
        private const val CONTROL_HINT = "Enter play/pause   Back exit"
        private val COLOR_PRIMARY = Color.parseColor("#FFE7A3")
        private val COLOR_SECONDARY = Color.parseColor("#D5BB7A")
        private val COLOR_DIM = Color.parseColor("#A48B59")
        private val COLOR_HINT = Color.parseColor("#8F7A52")
        private val COLOR_SHADOW = Color.parseColor("#CC000000")
    }
}

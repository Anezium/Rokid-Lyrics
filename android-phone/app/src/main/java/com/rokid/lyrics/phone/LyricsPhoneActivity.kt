package com.rokid.lyrics.phone

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import com.rokid.lyrics.contracts.LyricsSessionState

class LyricsPhoneActivity : AppCompatActivity() {

    private lateinit var viewBtDot: View
    private lateinit var tvBtCount: TextView
    private lateinit var tvPlayingBadge: TextView
    private lateinit var tvTrackTitle: TextView
    private lateinit var tvArtistName: TextView
    private lateinit var tvSourcePill: TextView
    private lateinit var viewProgress: View
    private lateinit var tvPastLine: TextView
    private lateinit var tvCurrentLine: TextView
    private lateinit var tvNextLine1: TextView
    private lateinit var tvNextLine2: TextView
    private lateinit var tvNotifStatus: TextView
    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvClientCount: TextView
    private lateinit var tvStatusLabel: TextView
    private var unsubscribe: (() -> Unit)? = null
    private var btPulseAnimator: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lyrics_phone)

        viewBtDot = findViewById(R.id.viewBtDot)
        tvBtCount = findViewById(R.id.tvBtCount)
        tvPlayingBadge = findViewById(R.id.tvPlayingBadge)
        tvTrackTitle = findViewById(R.id.tvTrackTitle)
        tvArtistName = findViewById(R.id.tvArtistName)
        tvSourcePill = findViewById(R.id.tvSourcePill)
        viewProgress = findViewById(R.id.viewProgress)
        tvPastLine = findViewById(R.id.tvPastLine)
        tvCurrentLine = findViewById(R.id.tvCurrentLine)
        tvNextLine1 = findViewById(R.id.tvNextLine1)
        tvNextLine2 = findViewById(R.id.tvNextLine2)
        tvNotifStatus = findViewById(R.id.tvNotifStatus)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        tvClientCount = findViewById(R.id.tvClientCount)
        tvStatusLabel = findViewById(R.id.tvStatusLabel)

        viewProgress.pivotX = 0f

        findViewById<Button>(R.id.btnNotificationAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btnBluetoothSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }

        btPulseAnimator = ObjectAnimator.ofFloat(viewBtDot, "alpha", 1f, 0.15f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
        }
        btPulseAnimator?.start()

        LyricsPhoneGraph.initialize(applicationContext)
        startForegroundService(this, Intent(this, LyricsPhoneService::class.java))
        requestRuntimePermissionsIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        unsubscribe = LyricsPhoneGraph.stateStore.subscribe(::render)
    }

    override fun onResume() {
        super.onResume()
        if (hasRuntimePermissions()) {
            LyricsPhoneGraph.start(applicationContext)
            LyricsPhoneGraph.refresh()
        }
    }

    override fun onStop() {
        unsubscribe?.invoke()
        unsubscribe = null
        super.onStop()
    }

    override fun onDestroy() {
        btPulseAnimator?.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && hasRuntimePermissions()) {
            LyricsPhoneGraph.start(applicationContext)
            LyricsPhoneGraph.refresh()
        }
    }

    private fun render(state: LyricsPhoneViewState) {
        val clientCount = state.deviceStatus.bluetoothClientCount
        val isConnected = clientCount > 0

        // BT dot — pulse only when connected
        if (isConnected) {
            if (btPulseAnimator?.isRunning == false) btPulseAnimator?.start()
        } else {
            btPulseAnimator?.cancel()
            viewBtDot.alpha = 0.2f
        }
        tvBtCount.text = if (isConnected) {
            "$clientCount DEVICE${if (clientCount > 1) "S" else ""}"
        } else {
            "NO DEVICE"
        }

        // Playing badge
        val isPlaying = state.lyrics.sessionState == LyricsSessionState.PLAYING
        tvPlayingBadge.text = when (state.lyrics.sessionState) {
            LyricsSessionState.PLAYING -> "> PLAYING"
            LyricsSessionState.READY -> "|| PAUSED"
            LyricsSessionState.LOADING -> ".. LOADING"
            LyricsSessionState.ERROR -> "!! ERROR"
            LyricsSessionState.IDLE -> "[] IDLE"
        }
        tvPlayingBadge.setTextColor(
            ContextCompat.getColor(
                this,
                if (isPlaying) R.color.phosphor_primary else R.color.phosphor_dim,
            )
        )

        // Track info
        tvTrackTitle.text = when {
            state.lyrics.trackTitle.isNotBlank() -> state.lyrics.trackTitle
            else -> "Waiting for music…"
        }
        tvArtistName.text = state.lyrics.artistName.ifBlank { "Play something on your phone" }

        // Source pill
        tvSourcePill.text = when {
            state.lyrics.errorMessage != null -> "ERROR"
            state.lyrics.sourceSummary.isNotBlank() -> shortenSource(state.lyrics.sourceSummary)
            else -> "NO SOURCE"
        }
        tvSourcePill.setTextColor(
            ContextCompat.getColor(
                this,
                if (state.lyrics.errorMessage != null) R.color.phosphor_error else R.color.phosphor_dim,
            )
        )

        // Progress bar — approximate from line index / total lines
        val lineCount = state.lyrics.lines.size
        viewProgress.scaleX = if (lineCount > 0) {
            (state.lyrics.currentLineIndex.toFloat() / lineCount.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

        // Lyrics (4-line cascade)
        val idx = state.lyrics.currentLineIndex
        tvPastLine.text = lineText(state, idx - 1)
        tvCurrentLine.text = lineText(state, idx, "No active line yet.")
        tvNextLine1.text = lineText(state, idx + 1)
        tvNextLine2.text = lineText(state, idx + 2)

        // Status bar
        val notifOk = state.deviceStatus.notificationAccessEnabled
        tvNotifStatus.text = if (notifOk) "NOTIF ✓" else "NOTIF ✗"
        tvNotifStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (notifOk) R.color.phosphor_primary else R.color.phosphor_error,
            )
        )
        tvConnectionStatus.text = "BT: ${state.deviceStatus.connectionState.name}"
        tvClientCount.text = "$clientCount CLI"
        tvStatusLabel.text = state.deviceStatus.statusLabel
    }

    private fun shortenSource(summary: String): String {
        val lines = Regex("with (\\d+) timed").find(summary)?.groupValues?.get(1)
        return if (lines != null) "LRCLIB · $lines♪" else summary.take(18)
    }

    private fun lineText(state: LyricsPhoneViewState, index: Int, fallback: String = ""): String {
        val line = state.lyrics.lines.getOrNull(index)?.text
        return when {
            !line.isNullOrBlank() -> line
            state.lyrics.plainLyrics.isNotBlank() && index == state.lyrics.currentLineIndex ->
                state.lyrics.plainLyrics.lineSequence().firstOrNull().orEmpty().ifBlank { fallback }
            else -> fallback
        }
    }

    private fun requestRuntimePermissionsIfNeeded() {
        val missing = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this@LyricsPhoneActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this@LyricsPhoneActivity, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.BLUETOOTH_SCAN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@LyricsPhoneActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    private fun hasRuntimePermissions(): Boolean {
        val bluetoothGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val scanGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        return bluetoothGranted && scanGranted
    }

    private companion object {
        private const val REQUEST_PERMISSIONS = 3001
    }
}

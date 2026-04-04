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
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
    private lateinit var btnMusixmatchSettings: Button
    private lateinit var tvMusixmatchStatus: TextView
    private lateinit var tvNeteaseStatus: TextView
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
        btnMusixmatchSettings = findViewById(R.id.btnMusixmatchSettings)
        tvMusixmatchStatus = findViewById(R.id.tvMusixmatchStatus)
        tvNeteaseStatus = findViewById(R.id.tvNeteaseStatus)

        viewProgress.pivotX = 0f

        findViewById<Button>(R.id.btnNotificationAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btnBluetoothSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        btnMusixmatchSettings.setOnClickListener { showMusixmatchDialog() }

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
            ),
        )

        tvTrackTitle.text = when {
            state.lyrics.trackTitle.isNotBlank() -> state.lyrics.trackTitle
            else -> "Waiting for music..."
        }
        tvArtistName.text = state.lyrics.artistName.ifBlank { "Play something on your phone" }

        tvSourcePill.text = when {
            state.lyrics.errorMessage != null -> "ERROR"
            state.lyrics.sourceSummary.isNotBlank() -> sourcePillText(
                state.lyrics.provider,
                state.lyrics.sourceSummary,
                state.lyrics.synced,
            )
            else -> "NO SOURCE"
        }
        tvSourcePill.setTextColor(
            ContextCompat.getColor(
                this,
                if (state.lyrics.errorMessage != null) R.color.phosphor_error else R.color.phosphor_dim,
            ),
        )

        val lineCount = state.lyrics.lines.size
        viewProgress.scaleX = if (lineCount > 0) {
            (state.lyrics.currentLineIndex.toFloat() / lineCount.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

        val idx = state.lyrics.currentLineIndex
        tvPastLine.text = lineText(state, idx - 1)
        tvCurrentLine.text = currentLineText(state)
        tvNextLine1.text = lineText(state, idx + 1)
        tvNextLine2.text = lineText(state, idx + 2)

        val notifOk = state.deviceStatus.notificationAccessEnabled
        tvNotifStatus.text = if (notifOk) "NOTIF OK" else "NOTIF OFF"
        tvNotifStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (notifOk) R.color.phosphor_primary else R.color.phosphor_error,
            ),
        )

        btnMusixmatchSettings.text = if (state.providers.musixmatchConfigured) {
            getString(R.string.button_musixmatch_ready)
        } else {
            getString(R.string.button_musixmatch_sign_in)
        }
        tvMusixmatchStatus.text = state.providers.musixmatchStatusLabel
        tvNeteaseStatus.text = state.providers.neteaseStatusLabel
        tvConnectionStatus.text = "BT: ${state.deviceStatus.connectionState.name}"
        tvClientCount.text = "$clientCount CLI"
        tvStatusLabel.text = state.deviceStatus.statusLabel
    }

    private fun sourcePillText(provider: String, summary: String, synced: Boolean): String {
        if (!synced && summary.startsWith("No synced lyrics found", ignoreCase = true)) {
            return "NO SYNC"
        }
        if (!synced && summary.startsWith("Track resolved without timed lyrics", ignoreCase = true)) {
            return "NO SYNC"
        }
        if (provider.isBlank()) {
            return when {
                summary.startsWith("Resolving lyrics", ignoreCase = true) -> "LOOKUP"
                summary.startsWith("Querying lyrics providers", ignoreCase = true) -> "LOOKUP"
                summary.startsWith("Tracking Spotify", ignoreCase = true) -> "SPOTIFY"
                summary.startsWith("Tracking ", ignoreCase = true) -> "MEDIA"
                else -> "NO SOURCE"
            }
        }
        return provider.ifBlank { "SOURCE" }.take(14)
    }

    private fun currentLineText(state: LyricsPhoneViewState): String {
        if (state.lyrics.sessionState == LyricsSessionState.LOADING) {
            return "Searching..."
        }
        if (showsNoLyricsFound(state)) {
            return "No lyrics found"
        }
        if (state.lyrics.synced && state.lyrics.lines.isNotEmpty() && state.lyrics.currentLineIndex < 0) {
            return "..."
        }
        return lineText(state, state.lyrics.currentLineIndex)
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

    private fun showsNoLyricsFound(state: LyricsPhoneViewState): Boolean {
        val lyrics = state.lyrics
        if (lyrics.errorMessage != null) return false
        if (lyrics.trackTitle.isBlank()) return false
        if (lyrics.synced || lyrics.lines.isNotEmpty() || lyrics.plainLyrics.isNotBlank()) return false
        return lyrics.sourceSummary.startsWith("No synced lyrics found", ignoreCase = true) ||
            lyrics.sourceSummary.startsWith("Track resolved without timed lyrics", ignoreCase = true)
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

    private fun showMusixmatchDialog() {
        val existing = LyricsPhoneGraph.musixmatchCredentials()
        val dialogView = layoutInflater.inflate(R.layout.dialog_musixmatch_settings, null)
        val emailField = dialogView.findViewById<EditText>(R.id.editMusixmatchEmail)
        val passwordField = dialogView.findViewById<EditText>(R.id.editMusixmatchPassword)

        emailField.setText(existing?.email.orEmpty())
        passwordField.setText(existing?.password.orEmpty())

        val dialogBuilder = AlertDialog.Builder(this, R.style.Theme_PhosphorDialog)
            .setTitle(R.string.musixmatch_dialog_title)
            .setView(dialogView)
            .setPositiveButton(R.string.musixmatch_dialog_save, null)
            .setNegativeButton(android.R.string.cancel, null)

        if (existing != null) {
            dialogBuilder.setNeutralButton(R.string.musixmatch_dialog_clear) { _, _ ->
                LyricsPhoneGraph.clearMusixmatchCredentials()
                Toast.makeText(
                    this,
                    R.string.musixmatch_cleared_toast,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        val dialog = dialogBuilder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val email = emailField.text?.toString()?.trim().orEmpty()
                val password = passwordField.text?.toString().orEmpty()
                if (email.isBlank()) {
                    emailField.error = getString(R.string.musixmatch_dialog_email_required)
                    return@setOnClickListener
                }
                if (password.isBlank()) {
                    passwordField.error = getString(R.string.musixmatch_dialog_password_required)
                    return@setOnClickListener
                }
                LyricsPhoneGraph.saveMusixmatchCredentials(email, password)
                Toast.makeText(
                    this,
                    R.string.musixmatch_saved_toast,
                    Toast.LENGTH_SHORT,
                ).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private companion object {
        private const val REQUEST_PERMISSIONS = 3001
    }
}

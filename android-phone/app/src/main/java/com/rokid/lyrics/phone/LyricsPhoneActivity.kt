package com.rokid.lyrics.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService

class LyricsPhoneActivity : AppCompatActivity() {
    private lateinit var tvPermissions: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvConnection: TextView
    private lateinit var tvTrack: TextView
    private lateinit var tvSummary: TextView
    private lateinit var tvCurrentLine: TextView
    private lateinit var tvNextLine: TextView
    private var unsubscribe: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lyrics_phone)

        tvPermissions = findViewById(R.id.tvPermissions)
        tvStatus = findViewById(R.id.tvStatus)
        tvConnection = findViewById(R.id.tvConnection)
        tvTrack = findViewById(R.id.tvTrack)
        tvSummary = findViewById(R.id.tvSummary)
        tvCurrentLine = findViewById(R.id.tvCurrentLine)
        tvNextLine = findViewById(R.id.tvNextLine)

        findViewById<Button>(R.id.btnNotificationAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btnBluetoothSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            LyricsPhoneGraph.refreshLyrics()
        }

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
        tvPermissions.text = buildString {
            append(
                if (state.deviceStatus.notificationAccessEnabled) {
                    "Notification access: enabled"
                } else {
                    "Notification access: missing"
                }
            )
            append("  |  Bluetooth clients: ${state.deviceStatus.bluetoothClientCount}")
        }
        tvStatus.text = state.deviceStatus.statusLabel
        tvConnection.text = getString(
            R.string.bluetooth_connection_state,
            state.deviceStatus.connectionState.name.lowercase(),
        )
        tvTrack.text = when {
            state.lyrics.trackTitle.isNotBlank() && state.lyrics.artistName.isNotBlank() ->
                "${state.lyrics.trackTitle} / ${state.lyrics.artistName}"
            state.lyrics.trackTitle.isNotBlank() -> state.lyrics.trackTitle
            else -> "Waiting for music on the phone"
        }
        tvSummary.text = state.lyrics.errorMessage ?: state.lyrics.sourceSummary
        tvCurrentLine.text = lineText(state, state.lyrics.currentLineIndex, "No active line yet.")
        tvNextLine.text = lineText(state, state.lyrics.currentLineIndex + 1, "No next line yet.")
    }

    private fun lineText(
        state: LyricsPhoneViewState,
        index: Int,
        fallback: String,
    ): String {
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
            ) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this@LyricsPhoneActivity, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@LyricsPhoneActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
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

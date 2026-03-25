package com.rokid.lyrics.glasses

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.rokid.lyrics.glasses.bluetooth.LyricsBluetoothBridge

class LyricsGlassesActivity : AppCompatActivity() {
    private lateinit var connectionStatusView: TextView
    private lateinit var lyricsHudView: LyricsHudView
    private lateinit var bluetoothBridge: LyricsBluetoothBridge
    private lateinit var stateStore: LyricsGlassesStateStore
    private var unsubscribe: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lyrics_glasses)

        connectionStatusView = findViewById(R.id.tvConnectionStatus)
        lyricsHudView = findViewById(R.id.lyricsHudView)

        bluetoothBridge = LyricsBluetoothBridge(applicationContext)
        stateStore = LyricsGlassesStateStore(bluetoothBridge)
        requestRuntimePermissionsIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        unsubscribe = stateStore.subscribe(::renderState)
    }

    override fun onResume() {
        super.onResume()
        if (hasRuntimePermissions()) {
            stateStore.requestSnapshot()
        }
    }

    override fun onStop() {
        unsubscribe?.invoke()
        unsubscribe = null
        super.onStop()
    }

    override fun onDestroy() {
        bluetoothBridge.close()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    finish()
                    return true
                }

                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_CENTER -> {
                    stateStore.requestRefresh()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && hasRuntimePermissions()) {
            stateStore.requestSnapshot()
        }
    }

    private fun renderState(state: LyricsGlassesState) {
        connectionStatusView.text = state.statusLabel
        lyricsHudView.render(state)
    }

    private fun requestRuntimePermissionsIfNeeded() {
        val missing = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this@LyricsGlassesActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this@LyricsGlassesActivity, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.BLUETOOTH_SCAN)
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
        private const val REQUEST_PERMISSIONS = 4001
    }
}

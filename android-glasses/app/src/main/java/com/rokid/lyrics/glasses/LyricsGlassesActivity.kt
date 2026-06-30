package com.rokid.lyrics.glasses

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.rokid.lyrics.contracts.GlassesToPhoneMessage
import com.rokid.lyrics.glasses.transport.LyricsHybridBridge

class LyricsGlassesActivity : AppCompatActivity() {
    private lateinit var lyricsHudView: LyricsHudView
    private var bridge: LyricsHybridBridge? = null
    private var stateStore: LyricsGlassesStateStore? = null
    private var mediaHintMonitor: GlassesMediaHintMonitor? = null
    private var unsubscribe: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lyrics_glasses)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        lyricsHudView = findViewById(R.id.lyricsHudView)
        lyricsHudView.keepScreenOn = true
        requestRuntimePermissionsIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        Log.i(TAG, "onStart")
        startBridgeIfReady()
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume")
    }

    override fun onPause() {
        Log.i(TAG, "onPause")
        super.onPause()
    }

    override fun onStop() {
        Log.i(TAG, "onStop")
        unsubscribe?.invoke()
        unsubscribe = null
        mediaHintMonitor?.stop()
        lyricsHudView.suspendTicker()
        bridge?.hibernate()
        super.onStop()
    }

    override fun onDestroy() {
        bridge?.close()
        mediaHintMonitor?.stop()
        mediaHintMonitor = null
        bridge = null
        stateStore = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && hasRuntimePermissions()) {
            startBridgeIfReady()
        }
    }

    private fun renderState(state: LyricsGlassesState) {
        lyricsHudView.render(state)
    }

    private fun startBridgeIfReady() {
        if (!hasRuntimePermissions()) return
        if (stateStore == null || bridge == null) {
            val bridge = LyricsHybridBridge(applicationContext)
            val store = LyricsGlassesStateStore(bridge)
            this.bridge = bridge
            stateStore = store
            mediaHintMonitor = GlassesMediaHintMonitor(applicationContext) { hint ->
                this.bridge?.send(GlassesToPhoneMessage.MediaHint(hint))
            }
        }
        if (unsubscribe == null) {
            unsubscribe = stateStore?.subscribe(::renderState)
        }
        bridge?.resume()
        stateStore?.requestSnapshot()
        mediaHintMonitor?.start()
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
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this@LyricsGlassesActivity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
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
        val locationGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return bluetoothGranted && scanGranted && locationGranted
    }

    private companion object {
        private const val TAG = "RokidLyricsActivity"
        private const val REQUEST_PERMISSIONS = 4001
    }
}

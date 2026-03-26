package com.rokid.lyrics.phone

import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.rokid.lyrics.phone.bluetooth.LyricsBluetoothServer
import com.rokid.lyrics.phone.lyrics.LyricsRuntimeEngine
import com.rokid.lyrics.phone.media.MediaSessionMonitor

object LyricsPhoneGraph {
    val stateStore = LyricsPhoneStateStore()

    @Volatile private var initialized = false
    lateinit var lyricsRuntimeEngine: LyricsRuntimeEngine
        private set
    lateinit var mediaSessionMonitor: MediaSessionMonitor
        private set
    lateinit var bluetoothServer: LyricsBluetoothServer
        private set
    private lateinit var appContext: Context

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        lyricsRuntimeEngine = LyricsRuntimeEngine(stateStore)
        mediaSessionMonitor = MediaSessionMonitor(
            context = appContext,
            onPlaybackSnapshot = lyricsRuntimeEngine::onMediaPlaybackSnapshot,
            onStatusChanged = { message ->
                lyricsRuntimeEngine.onMediaStatus(message)
                syncNotificationAccessFlag()
            },
        )
        bluetoothServer = LyricsBluetoothServer(
            bluetoothAdapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter,
            stateStore = stateStore,
            lyricsRuntimeEngine = lyricsRuntimeEngine,
        )
        initialized = true
        syncNotificationAccessFlag()
    }

    @Synchronized
    fun start(context: Context) {
        initialize(context)
        syncNotificationAccessFlag()
        bluetoothServer.startServing()
        mediaSessionMonitor.start()
    }

    fun refresh() {
        if (!initialized) return
        syncNotificationAccessFlag()
        mediaSessionMonitor.refresh()
    }

    fun togglePlayback() {
        if (!initialized) return
        mediaSessionMonitor.togglePlayback()
    }

    @Synchronized
    fun destroy() {
        if (!initialized) return
        runCatching { mediaSessionMonitor.stop() }
        runCatching { bluetoothServer.stopServing() }
        runCatching { lyricsRuntimeEngine.destroy() }
        initialized = false
    }

    private fun syncNotificationAccessFlag() {
        if (!initialized) return
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(appContext)
            .contains(appContext.packageName)
        stateStore.updateStatus { current ->
            current.copy(
                notificationAccessEnabled = enabled,
                statusLabel = current.statusLabel.ifBlank {
                    if (enabled) {
                        "Waiting for active media playback."
                    } else {
                        "Enable notification access so Lyrics can read Spotify/media sessions."
                    }
                },
            )
        }
    }
}

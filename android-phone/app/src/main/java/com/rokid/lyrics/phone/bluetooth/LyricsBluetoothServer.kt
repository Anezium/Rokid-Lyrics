package com.rokid.lyrics.phone.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import com.rokid.lyrics.contracts.ConnectionState
import com.rokid.lyrics.contracts.DeviceStatus
import com.rokid.lyrics.contracts.GlassesToPhoneMessage
import com.rokid.lyrics.contracts.LyricsEvent
import com.rokid.lyrics.contracts.LyricsPlaybackSync
import com.rokid.lyrics.contracts.LyricsSnapshot
import com.rokid.lyrics.contracts.PhoneToGlassesMessage
import com.rokid.lyrics.contracts.TransportConstants
import com.rokid.lyrics.contracts.WireProtocol
import com.rokid.lyrics.phone.LyricsPhoneStateStore
import com.rokid.lyrics.phone.LyricsPhoneViewState
import com.rokid.lyrics.phone.lyrics.LyricsRuntimeEngine
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

private const val LYRICS_SYNC_PROGRESS_INTERVAL_MS = 1_000L

class LyricsBluetoothServer(
    private val bluetoothAdapter: BluetoothAdapter?,
    private val stateStore: LyricsPhoneStateStore,
    private val lyricsRuntimeEngine: LyricsRuntimeEngine,
) {
    private val clients = CopyOnWriteArrayList<ClientSession>()
    @Volatile private var running = false
    private var unsubscribe: (() -> Unit)? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var lastSentStatus: DeviceStatus? = null
    private var lastSentLyricsSnapshot: LyricsSnapshot? = null
    private var lastSentLyricsSync: LyricsPlaybackSync? = null

    private data class ClientSession(
        val socket: BluetoothSocket,
        val writer: BufferedWriter,
    )

    fun startServing() {
        if (running) return
        running = true
        updateConnectionStatus("Bluetooth server starting...")
        unsubscribe = stateStore.subscribe(::broadcastViewState)
        startServerLoop()
    }

    fun stopServing() {
        running = false
        unsubscribe?.invoke()
        unsubscribe = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        clients.forEach { session ->
            try {
                session.socket.close()
            } catch (_: Exception) {
            }
        }
        clients.clear()
        updateConnectionStatus("Bluetooth server stopped.")
    }

    @SuppressLint("MissingPermission")
    private fun startServerLoop() {
        Thread {
            val adapter = bluetoothAdapter
            if (adapter == null) {
                updateConnectionStatus("Bluetooth adapter unavailable.", "Bluetooth adapter unavailable.")
                return@Thread
            }
            val uuid = UUID.fromString(TransportConstants.SPP_UUID)

            while (running) {
                try {
                    serverSocket = adapter.listenUsingInsecureRfcommWithServiceRecord(
                        TransportConstants.BLUETOOTH_SERVICE_NAME,
                        uuid,
                    )
                    updateConnectionStatus("Bluetooth server ready. Waiting for the glasses to connect.")
                    while (running) {
                        val client = serverSocket?.accept() ?: break
                        val writer = BufferedWriter(OutputStreamWriter(client.outputStream, Charsets.UTF_8))
                        val session = ClientSession(client, writer)
                        clients += session
                        updateConnectionStatus("Glasses connected over Bluetooth.")
                        sendViewState(session, stateStore.current())
                        startReader(session)
                    }
                } catch (t: Throwable) {
                    if (running) {
                        updateConnectionStatus(
                            "Bluetooth server error: ${t.message ?: "unknown"}",
                            t.message ?: "Bluetooth server failure",
                        )
                    }
                } finally {
                    try {
                        serverSocket?.close()
                    } catch (_: Exception) {
                    }
                    serverSocket = null
                }
                if (running) {
                    Thread.sleep(RESTART_DELAY_MS)
                }
            }
        }.start()
    }

    private fun startReader(session: ClientSession) {
        Thread {
            try {
                val reader = BufferedReader(InputStreamReader(session.socket.inputStream, Charsets.UTF_8))
                while (running) {
                    val line = reader.readLine() ?: break
                    when (val message = WireProtocol.decodeGlassesMessageOrNull(line)) {
                        GlassesToPhoneMessage.RequestSnapshot -> sendViewState(session, stateStore.current())
                        GlassesToPhoneMessage.RequestStatus -> {
                            send(session.writer, PhoneToGlassesMessage.Status(stateStore.current().deviceStatus))
                        }

                        GlassesToPhoneMessage.RefreshLyrics -> lyricsRuntimeEngine.refresh()
                        null -> Unit
                    }
                }
            } catch (_: Exception) {
            } finally {
                clients.remove(session)
                try {
                    session.socket.close()
                } catch (_: Exception) {
                }
                updateConnectionStatus("Glasses disconnected. Waiting for Bluetooth reconnection.")
            }
        }.start()
    }

    private fun broadcastViewState(state: LyricsPhoneViewState) {
        if (clients.isEmpty()) {
            lastSentStatus = state.deviceStatus
            return
        }
        if (state.deviceStatus != lastSentStatus) {
            broadcastPhoneMessage(PhoneToGlassesMessage.Status(state.deviceStatus))
            lastSentStatus = state.deviceStatus
        }

        val lyricsSnapshot = lyricsSnapshotPayload(state.lyrics)
        val lyricsSync = lyricsPlaybackSync(state.lyrics)
        var sentSnapshot = false
        if (lyricsSnapshot != lastSentLyricsSnapshot) {
            lastSentLyricsSnapshot = lyricsSnapshot
            lastSentLyricsSync = lyricsSync
            broadcastPhoneMessage(PhoneToGlassesMessage.Lyrics(LyricsEvent.Snapshot(state.lyrics)))
            sentSnapshot = true
        }
        if (!sentSnapshot && shouldSendLyricsSync(lyricsSync)) {
            lastSentLyricsSync = lyricsSync
            broadcastPhoneMessage(PhoneToGlassesMessage.Lyrics(LyricsEvent.Sync(lyricsSync)))
        }
    }

    private fun sendViewState(session: ClientSession, state: LyricsPhoneViewState) {
        send(session.writer, PhoneToGlassesMessage.Status(state.deviceStatus))
        send(session.writer, PhoneToGlassesMessage.Lyrics(LyricsEvent.Snapshot(state.lyrics)))
    }

    private fun broadcastPhoneMessage(message: PhoneToGlassesMessage) {
        val dead = mutableListOf<ClientSession>()
        clients.forEach { session ->
            if (!send(session.writer, message)) {
                dead += session
            }
        }
        dead.forEach { session ->
            clients.remove(session)
            try {
                session.socket.close()
            } catch (_: Exception) {
            }
        }
        if (dead.isNotEmpty()) {
            updateConnectionStatus("Bluetooth client disconnected. Waiting for reconnection.")
        }
    }

    private fun send(writer: BufferedWriter, message: PhoneToGlassesMessage): Boolean {
        return try {
            writer.write(WireProtocol.encodePhoneMessage(message))
            writer.newLine()
            writer.flush()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun lyricsSnapshotPayload(snapshot: LyricsSnapshot): LyricsSnapshot =
        snapshot.copy(
            progressMs = 0L,
            capturedAtEpochMs = 0L,
            currentLineIndex = -1,
        )

    private fun lyricsPlaybackSync(snapshot: LyricsSnapshot): LyricsPlaybackSync =
        LyricsPlaybackSync(
            sessionState = snapshot.sessionState,
            progressMs = snapshot.progressMs,
            capturedAtEpochMs = snapshot.capturedAtEpochMs,
            currentLineIndex = snapshot.currentLineIndex,
        )

    private fun shouldSendLyricsSync(sync: LyricsPlaybackSync): Boolean {
        val previous = lastSentLyricsSync ?: return true
        if (sync.sessionState != previous.sessionState) return true
        if (sync.currentLineIndex != previous.currentLineIndex) return true
        return abs(sync.progressMs - previous.progressMs) >= LYRICS_SYNC_PROGRESS_INTERVAL_MS
    }

    private fun updateConnectionStatus(message: String, lastError: String? = null) {
        stateStore.updateStatus { current ->
            current.copy(
                connectionState = when {
                    clients.isNotEmpty() -> ConnectionState.CONNECTED
                    running -> ConnectionState.CONNECTING
                    else -> ConnectionState.DISCONNECTED
                },
                bluetoothClientCount = clients.size,
                statusLabel = message,
                lastError = lastError ?: current.lastError,
            )
        }
    }

    private companion object {
        private const val RESTART_DELAY_MS = 2500L
    }
}

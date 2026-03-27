package com.rokid.lyrics.phone.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import com.rokid.lyrics.contracts.ConnectionState
import com.rokid.lyrics.contracts.DeviceStatus
import com.rokid.lyrics.contracts.GlassesToPhoneMessage
import com.rokid.lyrics.contracts.LyricsEvent
import com.rokid.lyrics.contracts.LyricsSessionState
import com.rokid.lyrics.contracts.LyricsPlaybackSync
import com.rokid.lyrics.contracts.LyricsSnapshot
import com.rokid.lyrics.contracts.PhoneToGlassesMessage
import com.rokid.lyrics.contracts.ProtocolHelloAck
import com.rokid.lyrics.contracts.TransportConstants
import com.rokid.lyrics.contracts.WireProtocol
import com.rokid.lyrics.phone.LyricsPhoneGraph
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

private const val LYRICS_SYNC_DRIFT_TOLERANCE_MS = 1_500L
private const val BROADCAST_DEBOUNCE_MS = 100L

class LyricsBluetoothServer(
    private val bluetoothAdapter: BluetoothAdapter?,
    private val stateStore: LyricsPhoneStateStore,
    private val lyricsRuntimeEngine: LyricsRuntimeEngine,
    private val appVersion: String,
) {
    private val clients = CopyOnWriteArrayList<ClientSession>()
    @Volatile private var running = false
    private var unsubscribe: (() -> Unit)? = null
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    private var lastSentStatus: DeviceStatus? = null
    private var lastSentLyricsSnapshot: LyricsSnapshot? = null
    private var lastSentLyricsSync: LyricsPlaybackSync? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var pendingBroadcastState: LyricsPhoneViewState? = null
    private val scheduledBroadcast = Runnable {
        pendingBroadcastState?.let { executeBroadcast(it) }
        pendingBroadcastState = null
    }

    private class ClientSession(
        val socket: BluetoothSocket,
        val writer: BufferedWriter,
        @Volatile var handshakeComplete: Boolean = false,
    )

    @Synchronized
    fun startServing() {
        if (running) return
        running = true
        updateConnectionStatus("Bluetooth server starting...")
        unsubscribe = stateStore.subscribe(::broadcastViewState)
        startServerLoop()
    }

    @Synchronized
    fun stopServing() {
        running = false
        unsubscribe?.invoke()
        unsubscribe = null
        mainHandler.removeCallbacks(scheduledBroadcast)
        pendingBroadcastState = null
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
                running = false
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
                        updateConnectionStatus("Bluetooth client connected. Negotiating protocol...")
                        startHandshakeTimeout(session)
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
                        is GlassesToPhoneMessage.Hello -> {
                            if (!completeHandshake(session, message.hello.protocolVersion)) {
                                return@Thread
                            }
                        }

                        GlassesToPhoneMessage.RequestSnapshot -> {
                            if (!session.handshakeComplete) {
                                disconnectSession(session, PROTOCOL_MISMATCH_STATUS, PROTOCOL_MISMATCH_ERROR)
                                return@Thread
                            }
                            sendViewState(session, stateStore.current())
                        }

                        GlassesToPhoneMessage.RequestStatus -> {
                            if (!session.handshakeComplete) {
                                disconnectSession(session, PROTOCOL_MISMATCH_STATUS, PROTOCOL_MISMATCH_ERROR)
                                return@Thread
                            }
                            send(session.writer, PhoneToGlassesMessage.Status(stateStore.current().deviceStatus))
                        }

                        GlassesToPhoneMessage.TogglePlayback -> {
                            if (!session.handshakeComplete) {
                                disconnectSession(session, PROTOCOL_MISMATCH_STATUS, PROTOCOL_MISMATCH_ERROR)
                                return@Thread
                            }
                            LyricsPhoneGraph.togglePlayback()
                        }

                        null -> {
                            disconnectSession(session, INVALID_MESSAGE_STATUS, INVALID_MESSAGE_ERROR)
                            return@Thread
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                disconnectSession(session, "Glasses disconnected. Waiting for Bluetooth reconnection.")
            }
        }.start()
    }

    private fun completeHandshake(session: ClientSession, remoteProtocolVersion: Int): Boolean {
        if (remoteProtocolVersion != TransportConstants.PROTOCOL_VERSION) {
            disconnectSession(
                session,
                "Incompatible glasses protocol version.",
                "Update the phone and glasses apps to the same version.",
            )
            return false
        }
        session.handshakeComplete = true
        val ack = PhoneToGlassesMessage.HelloAck(
            ProtocolHelloAck(
                protocolVersion = TransportConstants.PROTOCOL_VERSION,
                appVersion = appVersion,
                capabilities = PHONE_CAPABILITIES,
            )
        )
        if (!send(session.writer, ack)) {
            disconnectSession(session, "Protocol handshake failed.", "Unable to reply to the glasses handshake.")
            return false
        }
        updateConnectionStatus("Glasses connected over Bluetooth.")
        sendViewState(session, stateStore.current())
        return true
    }

    private fun broadcastViewState(state: LyricsPhoneViewState) {
        pendingBroadcastState = state
        mainHandler.removeCallbacks(scheduledBroadcast)
        mainHandler.postDelayed(scheduledBroadcast, BROADCAST_DEBOUNCE_MS)
    }

    private fun executeBroadcast(state: LyricsPhoneViewState) {
        if (connectedClientCount() == 0) {
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
            if (!session.handshakeComplete) return@forEach
            if (!send(session.writer, message)) {
                dead += session
            }
        }
        dead.forEach { session ->
            disconnectSession(session)
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
        if (sync.sessionState != LyricsSessionState.PLAYING) return false
        val elapsedAtSourceMs = sync.capturedAtEpochMs - previous.capturedAtEpochMs
        if (elapsedAtSourceMs <= 0L) return true
        val progressDeltaMs = sync.progressMs - previous.progressMs
        return abs(progressDeltaMs - elapsedAtSourceMs) >= LYRICS_SYNC_DRIFT_TOLERANCE_MS
    }

    private fun updateConnectionStatus(message: String, lastError: String? = null) {
        val connectedClients = connectedClientCount()
        stateStore.updateStatus { current ->
            current.copy(
                connectionState = when {
                    connectedClients > 0 -> ConnectionState.CONNECTED
                    running -> ConnectionState.CONNECTING
                    else -> ConnectionState.DISCONNECTED
                },
                bluetoothClientCount = connectedClients,
                statusLabel = message,
                lastError = lastError,
            )
        }
    }

    private fun connectedClientCount(): Int =
        clients.count { it.handshakeComplete }

    private fun disconnectSession(
        session: ClientSession,
        statusMessage: String? = null,
        lastError: String? = null,
    ) {
        val removed = clients.remove(session)
        runCatching { session.socket.close() }
        if (removed && statusMessage != null) {
            updateConnectionStatus(statusMessage, lastError)
        }
    }

    private fun startHandshakeTimeout(session: ClientSession) {
        Thread {
            try {
                Thread.sleep(HANDSHAKE_TIMEOUT_MS)
            } catch (_: InterruptedException) {
            }
            if (!running || session.handshakeComplete || !clients.contains(session)) {
                return@Thread
            }
            send(
                session.writer,
                PhoneToGlassesMessage.Error(PROTOCOL_MISMATCH_ERROR),
            )
            disconnectSession(session, PROTOCOL_MISMATCH_STATUS, PROTOCOL_MISMATCH_ERROR)
        }.start()
    }

    private companion object {
        private const val RESTART_DELAY_MS = 2500L
        private const val HANDSHAKE_TIMEOUT_MS = 3500L
        private const val PROTOCOL_MISMATCH_STATUS = "Bluetooth client uses an incompatible Lyrics protocol."
        private const val PROTOCOL_MISMATCH_ERROR = "Update the phone and glasses apps to the same version."
        private const val INVALID_MESSAGE_STATUS = "Bluetooth client sent an invalid message."
        private const val INVALID_MESSAGE_ERROR = "Reconnect the glasses after updating both apps."
        private val PHONE_CAPABILITIES = listOf(
            "status",
            "lyrics_snapshot",
            "lyrics_sync",
            "toggle_playback",
        )
    }
}

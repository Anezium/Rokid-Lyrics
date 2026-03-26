package com.rokid.lyrics.glasses.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.rokid.lyrics.contracts.ConnectionState
import com.rokid.lyrics.contracts.DeviceStatus
import com.rokid.lyrics.contracts.GlassesToPhoneMessage
import com.rokid.lyrics.contracts.PhoneToGlassesMessage
import com.rokid.lyrics.contracts.TransportConstants
import com.rokid.lyrics.contracts.WireProtocol
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

class LyricsBluetoothBridge(
    private val context: Context,
) {
    private val listeners = CopyOnWriteArraySet<(PhoneToGlassesMessage) -> Unit>()
    private val pendingMessages = CopyOnWriteArrayList<GlassesToPhoneMessage>()
    private val controlLock = Object()
    @Volatile private var closed = false
    @Volatile private var active = false
    @Volatile private var hibernating = false
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var connectThread: Thread? = null

    fun resume() {
        if (closed) return
        active = true
        hibernating = false
        ensureConnectLoop()
        connectThread?.interrupt()
        synchronized(controlLock) {
            controlLock.notifyAll()
        }
    }

    fun pause() {
        active = false
        hibernating = false
        closeSocket()
        connectThread?.interrupt()
    }

    fun hibernate() {
        if (closed) return
        active = false
        hibernating = true
        closeSocket()
        ensureConnectLoop()
        connectThread?.interrupt()
        synchronized(controlLock) {
            controlLock.notifyAll()
        }
    }

    fun send(message: GlassesToPhoneMessage) {
        if (closed) return
        if (!trySend(message) && shouldQueue(message)) {
            pendingMessages += message
        }
    }

    fun subscribe(listener: (PhoneToGlassesMessage) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun close() {
        closed = true
        active = false
        hibernating = false
        closeSocket()
        synchronized(controlLock) {
            controlLock.notifyAll()
        }
        connectThread?.interrupt()
    }

    @SuppressLint("MissingPermission")
    private fun ensureConnectLoop() {
        val existing = connectThread
        if (existing?.isAlive == true) return
        connectThread = Thread(
            Runnable {
                val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val adapter = bluetoothManager?.adapter
                if (adapter == null) {
                    emitStatus(ConnectionState.DISCONNECTED, "Bluetooth adapter unavailable on the glasses.")
                    return@Runnable
                }

                while (!closed) {
                    if (!waitUntilConnectAllowed()) break
                    if (!active && hibernating) {
                        sleep(HIBERNATE_REFRESH_INTERVAL_MS)
                        if (closed) break
                        if (active || !hibernating) continue
                    }
                    if (socket != null && writer != null) {
                        sleep(if (active) RECONNECT_DELAY_MS else HIBERNATE_CONNECTED_WINDOW_MS)
                        continue
                    }

                    adapter.cancelDiscovery()
                    val candidate = preferredDevices(adapter).firstNotNullOfOrNull(::tryConnect)
                    if (candidate == null) {
                        if (active && !closed) {
                            emitStatus(
                                ConnectionState.CONNECTING,
                                "No paired phone accepted the Lyrics Bluetooth link yet.",
                            )
                        }
                        sleep(RECONNECT_DELAY_MS)
                        continue
                    }

                    if ((!active && !hibernating) || closed) {
                        runCatching { candidate.close() }
                        continue
                    }

                    socket = candidate
                    writer = BufferedWriter(OutputStreamWriter(candidate.outputStream, Charsets.UTF_8))
                    if (active) {
                        emitStatus(
                            ConnectionState.CONNECTED,
                            "Connected to ${safeName(candidate.remoteDevice)} via Bluetooth.",
                        )
                    }
                    flushPending()
                    startReader(candidate)
                    if (active) {
                        sleep(RECONNECT_DELAY_MS)
                    } else {
                        sleep(HIBERNATE_CONNECTED_WINDOW_MS)
                        if (!active) {
                            closeSocket()
                        }
                    }
                }
            },
            "LyricsBluetoothBridge",
        ).also { it.start() }
    }

    private fun waitUntilConnectAllowed(): Boolean {
        if (closed) return false
        if (active || hibernating) return true
        return try {
            synchronized(controlLock) {
                while (!active && !hibernating && !closed) {
                    controlLock.wait()
                }
            }
            !closed
        } catch (_: InterruptedException) {
            !closed && (active || hibernating)
        }
    }

    private fun sleep(delayMs: Long) {
        try {
            Thread.sleep(delayMs)
        } catch (_: InterruptedException) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun preferredDevices(adapter: BluetoothAdapter): List<BluetoothDevice> {
        val bonded = adapter.bondedDevices?.toList().orEmpty()
        val phones = bonded.filter { device ->
            device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE
        }
        return if (phones.isNotEmpty()) phones else bonded
    }

    @SuppressLint("MissingPermission")
    private fun tryConnect(device: BluetoothDevice): BluetoothSocket? {
        val uuid = UUID.fromString(TransportConstants.SPP_UUID)
        val factories = listOf<() -> BluetoothSocket>(
            { device.createInsecureRfcommSocketToServiceRecord(uuid) },
            { device.createRfcommSocketToServiceRecord(uuid) },
            {
                val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                method.invoke(device, 1) as BluetoothSocket
            },
        )
        factories.forEach { factory ->
            runCatching {
                factory().also { it.connect() }
            }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun startReader(activeSocket: BluetoothSocket) {
        Thread {
            try {
                val reader = BufferedReader(InputStreamReader(activeSocket.inputStream, Charsets.UTF_8))
                while (!closed) {
                    val line = reader.readLine() ?: break
                    WireProtocol.decodePhoneMessageOrNull(line)?.let { message ->
                        listeners.forEach { it(message) }
                    }
                }
            } catch (_: Exception) {
            } finally {
                handleDisconnect()
            }
        }.start()
    }

    private fun trySend(message: GlassesToPhoneMessage): Boolean {
        if (!active) return false
        val activeWriter = writer ?: return false
        return try {
            activeWriter.write(WireProtocol.encodeGlassesMessage(message))
            activeWriter.newLine()
            activeWriter.flush()
            true
        } catch (_: Exception) {
            handleDisconnect()
            false
        }
    }

    private fun shouldQueue(message: GlassesToPhoneMessage): Boolean = when (message) {
        GlassesToPhoneMessage.RequestSnapshot,
        GlassesToPhoneMessage.RequestStatus -> true
        GlassesToPhoneMessage.TogglePlayback -> false
    }

    private fun flushPending() {
        if ((!active && !hibernating) || pendingMessages.isEmpty()) return
        val drain = pendingMessages.toList()
        pendingMessages.clear()
        drain.forEach { message ->
            if (!trySend(message)) {
                pendingMessages += message
            }
        }
    }

    private fun handleDisconnect() {
        closeSocket()
        if (!closed && active) {
            emitStatus(ConnectionState.CONNECTING, "Bluetooth link lost. Reconnecting to the phone...")
        }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        writer = null
    }

    private fun emitStatus(connectionState: ConnectionState, label: String) {
        val message = PhoneToGlassesMessage.Status(
            DeviceStatus(
                connectionState = connectionState,
                statusLabel = label,
                bluetoothClientCount = if (connectionState == ConnectionState.CONNECTED) 1 else 0,
            ),
        )
        listeners.forEach { it(message) }
    }

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice): String =
        runCatching { device.name }.getOrNull().orEmpty().ifBlank { device.address }

    private companion object {
        private const val RECONNECT_DELAY_MS = 2500L
        private const val HIBERNATE_REFRESH_INTERVAL_MS = 20_000L
        private const val HIBERNATE_CONNECTED_WINDOW_MS = 3_500L
    }
}

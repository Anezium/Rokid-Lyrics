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
    @Volatile private var running = true
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var writer: BufferedWriter? = null

    init {
        startConnectLoop()
    }

    fun send(message: GlassesToPhoneMessage) {
        if (!trySend(message)) {
            pendingMessages += message
        }
    }

    fun subscribe(listener: (PhoneToGlassesMessage) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun close() {
        running = false
        closeSocket()
        emitStatus(ConnectionState.DISCONNECTED, "Bluetooth bridge stopped.")
    }

    @SuppressLint("MissingPermission")
    private fun startConnectLoop() {
        Thread {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bluetoothManager?.adapter
            if (adapter == null) {
                emitStatus(ConnectionState.DISCONNECTED, "Bluetooth adapter unavailable on the glasses.")
                return@Thread
            }

            emitStatus(ConnectionState.CONNECTING, "Searching for the phone over Bluetooth...")
            while (running) {
                if (socket != null && writer != null) {
                    Thread.sleep(RECONNECT_DELAY_MS)
                    continue
                }

                adapter.cancelDiscovery()
                val candidate = preferredDevices(adapter).firstNotNullOfOrNull(::tryConnect)
                if (candidate == null) {
                    emitStatus(ConnectionState.CONNECTING, "No paired phone accepted the Lyrics Bluetooth link yet.")
                    Thread.sleep(RECONNECT_DELAY_MS)
                    continue
                }

                socket = candidate
                writer = BufferedWriter(OutputStreamWriter(candidate.outputStream, Charsets.UTF_8))
                emitStatus(
                    ConnectionState.CONNECTED,
                    "Connected to ${safeName(candidate.remoteDevice)} via Bluetooth.",
                )
                flushPending()
                send(GlassesToPhoneMessage.RequestStatus)
                send(GlassesToPhoneMessage.RequestSnapshot)
                startReader(candidate)
                Thread.sleep(RECONNECT_DELAY_MS)
            }
        }.start()
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
                while (running) {
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

    private fun flushPending() {
        if (pendingMessages.isEmpty()) return
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
        if (running) {
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
    }
}

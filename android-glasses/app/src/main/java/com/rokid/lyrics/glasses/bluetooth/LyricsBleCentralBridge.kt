package com.rokid.lyrics.glasses.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.rokid.lyrics.contracts.BleWireFramer
import com.rokid.lyrics.contracts.ConnectionState
import com.rokid.lyrics.contracts.DeviceStatus
import com.rokid.lyrics.contracts.GlassesToPhoneMessage
import com.rokid.lyrics.contracts.PhoneToGlassesMessage
import com.rokid.lyrics.contracts.ProtocolHello
import com.rokid.lyrics.contracts.TransportConstants
import com.rokid.lyrics.contracts.WireProtocol
import com.rokid.lyrics.glasses.transport.LyricsPhoneBridge
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ThreadLocalRandom

class LyricsBleCentralBridge(
    private val context: Context,
) : LyricsPhoneBridge {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val listeners = CopyOnWriteArraySet<(PhoneToGlassesMessage) -> Unit>()
    private val pendingMessages = CopyOnWriteArrayList<GlassesToPhoneMessage>()
    private val outgoingFrames = ArrayDeque<ByteArray>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reassembler = BleWireFramer.Reassembler()

    @Volatile private var closed = false
    @Volatile private var active = false
    @Volatile private var hibernating = false
    @Volatile private var scanning = false
    @Volatile private var handshakeComplete = false
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var rxCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var txCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var discoveringServices = false
    @Volatile private var subscribingToNotifications = false
    @Volatile private var notificationsSubscribed = false
    @Volatile private var writeInFlight = false
    @Volatile private var inFlightFrame: ByteArray? = null
    @Volatile private var inFlightRetries = 0
    @Volatile private var maxPacketSize = DEFAULT_PACKET_SIZE
    @Volatile private var nextMessageId = randomMessageId()
    @Volatile private var remoteDeviceName: String? = null

    val isProtocolReady: Boolean
        get() = !closed && handshakeComplete

    private val reconnectRunnable = Runnable {
        if (!closed && (active || hibernating) && gatt == null) {
            startScan()
        }
    }

    private val scanTimeoutRunnable = Runnable {
        if (!scanning) return@Runnable
        stopScan()
        if (active && gatt == null) {
            emitStatus(ConnectionState.CONNECTING, "No iPhone BLE Lyrics advertiser found yet.")
        }
        scheduleReconnect(if (active) RECONNECT_DELAY_MS else HIBERNATE_REFRESH_INTERVAL_MS)
    }

    override fun subscribe(listener: (PhoneToGlassesMessage) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun resume() {
        if (closed) return
        active = true
        hibernating = false
        startScan()
    }

    fun pause() {
        active = false
        hibernating = false
        stopScan()
        closeGatt()
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(scanTimeoutRunnable)
    }

    fun hibernate() {
        if (closed) return
        active = false
        hibernating = true
        stopScan()
        closeGatt()
        startScan()
    }

    fun close() {
        closed = true
        active = false
        hibernating = false
        stopScan()
        closeGatt()
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        pendingMessages.clear()
    }

    override fun send(message: GlassesToPhoneMessage) {
        if (closed) return
        if (!trySend(message) && shouldQueue(message)) {
            queuePending(message)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (closed || (!active && !hibernating) || scanning || gatt != null) return
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || adapter?.isEnabled != true) {
            if (active) {
                emitStatus(ConnectionState.DISCONNECTED, "Bluetooth LE scanner unavailable on the glasses.")
            }
            scheduleReconnect(RECONNECT_DELAY_MS)
            return
        }

        scanning = true
        emitStatus(ConnectionState.CONNECTING, "Scanning for iPhone BLE Lyrics advertiser.")
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(UUID.fromString(TransportConstants.BLE_SERVICE_UUID)))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(if (active) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        runCatching {
            scanner.startScan(listOf(filter), settings, scanCallback)
            mainHandler.removeCallbacks(scanTimeoutRunnable)
            mainHandler.postDelayed(scanTimeoutRunnable, SCAN_WINDOW_MS)
        }.onFailure {
            scanning = false
            emitStatus(ConnectionState.DISCONNECTED, "BLE scan failed: ${it.message.orEmpty()}", it.message)
            scheduleReconnect(RECONNECT_DELAY_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        runCatching {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        stopScan()
        remoteDeviceName = safeName(device)
        emitStatus(ConnectionState.CONNECTING, "Found iPhone BLE Lyrics advertiser. Connecting...")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, false, gattCallback)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (closed || (!active && !hibernating) || gatt != null) return
            connect(result.device)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.firstOrNull()?.let { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            emitStatus(ConnectionState.DISCONNECTED, "BLE scan failed with code $errorCode.", "BLE scan failed: $errorCode")
            val retryDelay = if (errorCode == SCAN_FAILED_SCANNING_TOO_FREQUENTLY) {
                SCAN_THROTTLE_RETRY_DELAY_MS
            } else {
                RECONNECT_DELAY_MS
            }
            scheduleReconnect(retryDelay)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!isCurrent(gatt)) {
                runCatching { gatt.close() }
                return
            }

            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                emitStatus(ConnectionState.CONNECTING, "BLE connected to iPhone. Discovering Lyrics service...")
                val mtuRequested = runCatching { gatt.requestMtu(REQUESTED_MTU) }.getOrDefault(false)
                if (!mtuRequested) {
                    discoveringServices = true
                    gatt.discoverServices()
                }
                return
            }

            handleDisconnect("BLE iPhone link disconnected. Scanning again.")
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (!isCurrent(gatt)) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                maxPacketSize = (mtu - ATT_HEADER_BYTES).coerceIn(DEFAULT_PACKET_SIZE, MAX_PACKET_SIZE)
            }
            if (discoveringServices || rxCharacteristic != null || txCharacteristic != null) return
            discoveringServices = true
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!isCurrent(gatt)) return
            discoveringServices = false
            if (subscribingToNotifications || notificationsSubscribed) return
            Log.i(TAG, "onServicesDiscovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleDisconnect("BLE Lyrics service discovery failed: $status")
                return
            }

            val service = gatt.getService(UUID.fromString(TransportConstants.BLE_SERVICE_UUID))
            val rx = service?.getCharacteristic(UUID.fromString(TransportConstants.BLE_RX_CHARACTERISTIC_UUID))
            val tx = service?.getCharacteristic(UUID.fromString(TransportConstants.BLE_TX_CHARACTERISTIC_UUID))
            if (service == null || rx == null || tx == null) {
                refreshGattCache(gatt)
                handleDisconnect(
                    "iPhone BLE Lyrics service is not ready yet. Retrying shortly.",
                    SERVICE_MISSING_RETRY_DELAY_MS,
                )
                return
            }

            rxCharacteristic = rx
            txCharacteristic = tx
            subscribingToNotifications = true
            Log.i(TAG, "Lyrics service ready; enabling notifications")
            emitStatus(ConnectionState.CONNECTING, "BLE Lyrics service found. Subscribing...")
            if (!gatt.setCharacteristicNotification(tx, true)) {
                subscribingToNotifications = false
                handleDisconnect("Unable to enable BLE Lyrics notifications.")
                return
            }
            val descriptor = tx.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
            if (descriptor == null) {
                subscribingToNotifications = false
                handleDisconnect("BLE Lyrics notification descriptor missing.")
                return
            }
            mainHandler.postDelayed(
                {
                    Log.i(
                        TAG,
                        "Delayed CCCD write current=${isCurrent(gatt)} subscribing=$subscribingToNotifications subscribed=$notificationsSubscribed",
                    )
                    if (!isCurrent(gatt) || !subscribingToNotifications || notificationsSubscribed) return@postDelayed
                    writeDescriptor(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                },
                NOTIFICATION_DESCRIPTOR_WRITE_DELAY_MS,
            )
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (!isCurrent(gatt)) return
            if (descriptor.uuid != CLIENT_CHARACTERISTIC_CONFIG_UUID) return
            Log.i(TAG, "onDescriptorWrite status=$status")
            subscribingToNotifications = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleDisconnect("BLE Lyrics notification subscribe failed: $status")
                return
            }
            notificationsSubscribed = true
            handleSubscribed()
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (!isCurrent(gatt) || characteristic.uuid != UUID.fromString(TransportConstants.BLE_TX_CHARACTERISTIC_UUID)) return
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            handleIncoming(value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (!isCurrent(gatt) || characteristic.uuid != UUID.fromString(TransportConstants.BLE_TX_CHARACTERISTIC_UUID)) return
            handleIncoming(value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (!isCurrent(gatt)) return
            Log.i(TAG, "onCharacteristicWrite uuid=${characteristic.uuid} status=$status")
            writeInFlight = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val retryFrame = inFlightFrame
                if (retryFrame != null && inFlightRetries < MAX_FRAME_RETRIES) {
                    inFlightRetries += 1
                    outgoingFrames.addFirst(retryFrame)
                    inFlightFrame = null
                    mainHandler.postDelayed(::pumpWriteQueue, FRAME_RETRY_DELAY_MS)
                    return
                }
                inFlightFrame = null
                inFlightRetries = 0
                outgoingFrames.clear()
                emitStatus(ConnectionState.CONNECTING, "BLE write failed: $status", "BLE write failed: $status")
                return
            }
            inFlightFrame = null
            inFlightRetries = 0
            pumpWriteQueue()
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray) {
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == 0
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
        Log.i(TAG, "writeDescriptor accepted=$accepted uuid=${descriptor.uuid}")
        if (!accepted) {
            handleDisconnect("Unable to write BLE Lyrics notification descriptor.")
        }
    }

    private fun handleSubscribed() {
        Log.i(TAG, "handleSubscribed handshakeComplete=$handshakeComplete")
        if (handshakeComplete) {
            emitStatus(
                ConnectionState.CONNECTED,
                "Connected to iPhone via inverted BLE.",
            )
            flushPending()
            return
        }
        handshakeComplete = false
        reassembler.clear()
        outgoingFrames.clear()
        writeInFlight = false
        inFlightFrame = null
        inFlightRetries = 0
        nextMessageId = randomMessageId()
        emitStatus(ConnectionState.CONNECTING, "BLE subscribed. Negotiating Lyrics protocol...")
        if (!sendRaw(handshakeMessage())) {
            handleDisconnect("Unable to start BLE Lyrics handshake.")
            return
        }
        startHandshakeTimeout(gatt)
    }

    private fun handleIncoming(value: ByteArray) {
        val line = reassembler.accept(value) ?: return
        val message = WireProtocol.decodePhoneMessageOrNull(line)
        if (message == null) {
            emitStatus(
                ConnectionState.DISCONNECTED,
                "Received an unsupported BLE phone message.",
                "Update the phone and glasses apps to matching versions.",
            )
            return
        }
        Log.i(TAG, "received ${message.javaClass.simpleName} via inverted BLE")
        if (!handshakeComplete) {
            if (!handlePreHandshakeMessage(message)) {
                closeGatt()
            }
            return
        }
        listeners.forEach { it(message) }
    }

    private fun handlePreHandshakeMessage(message: PhoneToGlassesMessage): Boolean =
        when (message) {
            is PhoneToGlassesMessage.HelloAck -> {
                if (message.ack.protocolVersion != TransportConstants.PROTOCOL_VERSION) {
                    emitStatus(
                        ConnectionState.DISCONNECTED,
                        "Connected iPhone app uses an incompatible Lyrics protocol.",
                        "Update the phone and glasses apps to the same version.",
                    )
                    false
                } else {
                    handshakeComplete = true
                    emitStatus(
                        ConnectionState.CONNECTED,
                        "Connected to iPhone via inverted BLE.",
                    )
                    flushPending()
                    true
                }
            }

            is PhoneToGlassesMessage.Error -> {
                emitStatus(ConnectionState.DISCONNECTED, message.message, message.message)
                false
            }

            else -> {
                emitStatus(
                    ConnectionState.DISCONNECTED,
                    "Connected iPhone app uses an incompatible Lyrics protocol.",
                    "Update the phone and glasses apps to the same version.",
                )
                false
            }
        }

    private fun trySend(message: GlassesToPhoneMessage): Boolean {
        if ((!active && !hibernating) || !handshakeComplete) return false
        return sendRaw(message)
    }

    private fun sendRaw(message: GlassesToPhoneMessage): Boolean {
        val activeGatt = gatt ?: return false
        val rx = rxCharacteristic ?: return false
        if (rx.uuid != UUID.fromString(TransportConstants.BLE_RX_CHARACTERISTIC_UUID)) return false

        val json = WireProtocol.encodeGlassesMessage(message)
        val frames = BleWireFramer.encode(json, nextMessageId, maxPacketSize)
        nextMessageId = randomMessageId(nextMessageId)
        if (frames.isEmpty()) return false
        Log.i(TAG, "sendRaw ${message.javaClass.simpleName} bytes=${json.length} frames=${frames.size} packetSize=$maxPacketSize")
        outgoingFrames.addAll(frames)
        pumpWriteQueue()
        return true
    }

    @SuppressLint("MissingPermission")
    private fun pumpWriteQueue() {
        val activeGatt = gatt ?: return
        val rx = rxCharacteristic ?: return
        if (writeInFlight || outgoingFrames.isEmpty()) return

        val frame = outgoingFrames.removeFirst()
        inFlightFrame = frame
        writeInFlight = true
        Log.i(TAG, "write RX frame bytes=${frame.size} remaining=${outgoingFrames.size}")
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activeGatt.writeCharacteristic(
                rx,
                frame,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == 0
        } else {
            @Suppress("DEPRECATION")
            rx.value = frame
            rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            activeGatt.writeCharacteristic(rx)
        }

        if (!accepted) {
            Log.w(TAG, "writeCharacteristic accepted=false")
            writeInFlight = false
            outgoingFrames.addFirst(frame)
            inFlightFrame = null
            mainHandler.postDelayed(::pumpWriteQueue, FRAME_RETRY_DELAY_MS)
        }
    }

    private fun shouldQueue(message: GlassesToPhoneMessage): Boolean = when (message) {
        is GlassesToPhoneMessage.Hello -> false
        is GlassesToPhoneMessage.MediaHint -> false
        GlassesToPhoneMessage.RequestSnapshot,
        GlassesToPhoneMessage.RequestStatus -> true
        GlassesToPhoneMessage.TogglePlayback -> false
    }

    private fun queuePending(message: GlassesToPhoneMessage) {
        val existingIndex = pendingMessages.indexOfFirst { it::class == message::class }
        if (existingIndex >= 0) {
            pendingMessages[existingIndex] = message
        } else {
            pendingMessages += message
        }
        while (pendingMessages.size > MAX_PENDING_MESSAGES) {
            pendingMessages.removeAt(0)
        }
    }

    private fun flushPending() {
        if ((!active && !hibernating) || !handshakeComplete || pendingMessages.isEmpty()) return
        val drain = pendingMessages.toList()
        pendingMessages.clear()
        drain.forEach { message ->
            if (!trySend(message)) {
                queuePending(message)
            }
        }
    }

    private fun handleDisconnect(label: String, reconnectDelayMs: Long? = null) {
        val shouldReconnect = !closed && (active || hibernating)
        closeGatt()
        if (shouldReconnect) {
            if (active) {
                emitStatus(ConnectionState.CONNECTING, label)
            }
            scheduleReconnect(reconnectDelayMs ?: if (active) RECONNECT_DELAY_MS else HIBERNATE_REFRESH_INTERVAL_MS)
        }
    }

    @SuppressLint("PrivateApi")
    private fun refreshGattCache(gatt: BluetoothGatt) {
        runCatching {
            val refreshMethod = BluetoothGatt::class.java.getMethod("refresh")
            refreshMethod.invoke(gatt)
        }.onFailure {
            Log.d(TAG, "Unable to refresh BLE GATT cache", it)
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        val activeGatt = gatt
        gatt = null
        rxCharacteristic = null
        txCharacteristic = null
        discoveringServices = false
        subscribingToNotifications = false
        notificationsSubscribed = false
        handshakeComplete = false
        writeInFlight = false
        inFlightFrame = null
        inFlightRetries = 0
        maxPacketSize = DEFAULT_PACKET_SIZE
        nextMessageId = randomMessageId()
        outgoingFrames.clear()
        reassembler.clear()
        if (activeGatt != null) {
            runCatching {
                activeGatt.disconnect()
                activeGatt.close()
            }
        }
    }

    private fun scheduleReconnect(delayMs: Long) {
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, delayMs)
    }

    private fun startHandshakeTimeout(activeGatt: BluetoothGatt?) {
        mainHandler.postDelayed(
            {
                if (closed || handshakeComplete || gatt != activeGatt) return@postDelayed
                emitStatus(
                    ConnectionState.DISCONNECTED,
                    "Connected iPhone did not complete the BLE Lyrics handshake.",
                    "Update the phone and glasses apps to the same version.",
                )
                handleDisconnect("BLE handshake timed out. Scanning again.")
            },
            HANDSHAKE_TIMEOUT_MS,
        )
    }

    private fun isCurrent(candidate: BluetoothGatt): Boolean =
        gatt === candidate

    private fun emitStatus(
        connectionState: ConnectionState,
        label: String,
        lastError: String? = null,
    ) {
        Log.i(TAG, "status $connectionState $label")
        val message = PhoneToGlassesMessage.Status(
            DeviceStatus(
                connectionState = connectionState,
                statusLabel = label,
                bluetoothClientCount = if (connectionState == ConnectionState.CONNECTED) 1 else 0,
                lastError = lastError,
            ),
        )
        listeners.forEach { it(message) }
    }

    private fun handshakeMessage(): GlassesToPhoneMessage =
        GlassesToPhoneMessage.Hello(
            ProtocolHello(
                protocolVersion = TransportConstants.PROTOCOL_VERSION,
                appVersion = appVersionName(),
                capabilities = GLASSES_CAPABILITIES,
            )
        )

    private fun appVersionName(): String =
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "unknown" }

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice): String =
        runCatching { device.name }.getOrNull().orEmpty().ifBlank { device.address }

    private companion object {
        private const val TAG = "RokidLyricsBleCentral"
        private const val RECONNECT_DELAY_MS = 2_500L
        private const val SERVICE_MISSING_RETRY_DELAY_MS = 10_000L
        private const val SCAN_THROTTLE_RETRY_DELAY_MS = 30_000L
        private const val SCAN_FAILED_SCANNING_TOO_FREQUENTLY = 6
        private const val HIBERNATE_REFRESH_INTERVAL_MS = 20_000L
        private const val SCAN_WINDOW_MS = 8_000L
        private const val HANDSHAKE_TIMEOUT_MS = 5_000L
        private const val FRAME_RETRY_DELAY_MS = 50L
        private const val NOTIFICATION_DESCRIPTOR_WRITE_DELAY_MS = 200L
        private const val MAX_FRAME_RETRIES = 4
        private const val MAX_PENDING_MESSAGES = 2
        private const val REQUESTED_MTU = 517
        private const val ATT_HEADER_BYTES = 3
        private const val DEFAULT_PACKET_SIZE = 20
        private const val MAX_PACKET_SIZE = 512
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val GLASSES_CAPABILITIES = listOf(
            "request_status",
            "request_snapshot",
            "media_hint",
            "lyrics_window",
            "lyrics_script",
            "lyrics_sync",
            "toggle_playback",
            "ble_gatt_central",
        )

        private fun randomMessageId(previous: Int? = null): Int {
            var next = ThreadLocalRandom.current().nextInt(1, Int.MAX_VALUE)
            if (previous != null && next == previous) {
                next = if (next == Int.MAX_VALUE - 1) 1 else next + 1
            }
            return next
        }
    }
}

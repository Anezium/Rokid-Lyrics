package com.rokid.lyrics.glasses.cxr

import android.content.Context
import android.util.Base64
import android.util.Log
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps
import com.rokid.lyrics.contracts.ConnectionState
import com.rokid.lyrics.contracts.DeviceStatus
import com.rokid.lyrics.contracts.GlassesToPhoneMessage
import com.rokid.lyrics.contracts.PhoneToGlassesMessage
import com.rokid.lyrics.contracts.ProtocolHello
import com.rokid.lyrics.contracts.TransportConstants
import com.rokid.lyrics.contracts.WireProtocol
import com.rokid.lyrics.glasses.transport.LyricsPhoneBridge
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

class LyricsCxrBridge(
    private val context: Context,
) : LyricsPhoneBridge {
    private val bridge = CXRServiceBridge()
    private val listeners = CopyOnWriteArraySet<(PhoneToGlassesMessage) -> Unit>()
    private val pendingMessages = CopyOnWriteArrayList<GlassesToPhoneMessage>()

    @Volatile private var closed = false
    @Volatile private var active = false
    @Volatile private var handshakeComplete = false
    @Volatile private var connectedDeviceName: String? = null
    @Volatile private var handshakeThread: Thread? = null

    val isProtocolReady: Boolean
        get() = !closed && handshakeComplete

    init {
        Log.i(
            TAG,
            "caps sample=" + Base64.encodeToString(
                Caps().apply {
                    write("json")
                    write("{}")
                }.serialize(),
                Base64.NO_WRAP,
            ),
        )
        Log.i(
            TAG,
            "caps sample long=" + Base64.encodeToString(
                Caps().apply {
                    write("json")
                    write("x".repeat(300))
                }.serialize(),
                Base64.NO_WRAP,
            ),
        )
        bridge.setStatusListener(
            object : CXRServiceBridge.StatusListener {
                override fun onConnected(name: String?, address: String?, type: Int) {
                    Log.i(TAG, "status connected name=$name address=$address type=$type")
                    connectedDeviceName = name?.ifBlank { null } ?: address?.ifBlank { null }
                    handshakeComplete = false
                    emitStatus(
                        ConnectionState.CONNECTING,
                        "CXR-L connected to ${connectedDeviceName.orEmpty().ifBlank { "Rokid glasses" }}. Negotiating Lyrics protocol...",
                    )
                    sendRaw(handshakeMessage())
                }

                override fun onDisconnected() {
                    Log.i(TAG, "status disconnected")
                    connectedDeviceName = null
                    handshakeComplete = false
                    if (!closed) {
                        emitStatus(ConnectionState.CONNECTING, "CXR-L phone link disconnected.")
                    }
                }

                override fun onConnecting(name: String?, address: String?, type: Int) {
                    Log.i(TAG, "status connecting name=$name address=$address type=$type")
                    connectedDeviceName = name?.ifBlank { null } ?: address?.ifBlank { null }
                    if (!closed) {
                        emitStatus(ConnectionState.CONNECTING, "CXR-L connecting to phone runtime...")
                    }
                }

                override fun onARTCStatus(score: Float, healthy: Boolean) = Unit

                override fun onRokidAccountChanged(account: String?) = Unit
            }
        )

        val callback = object : CXRServiceBridge.MsgCallback {
            override fun onReceive(name: String?, args: Caps?, bytes: ByteArray?) {
                handleIncoming(name, args, bytes)
            }
        }
        listOf(
            TransportConstants.CXR_PHONE_TO_GLASSES_COMMAND,
            TransportConstants.CXR_GLASSES_TO_PHONE_COMMAND,
        ).distinct().forEach { command ->
            val subscriptionResult = bridge.subscribe(command, callback)
            Log.i(TAG, "subscribe $command result=$subscriptionResult")
            if (subscriptionResult != 0) {
                emitStatus(
                    ConnectionState.DISCONNECTED,
                    "CXR-L subscription failed with code $subscriptionResult.",
                    "CXR-L subscription failed: $subscriptionResult",
                )
            }
        }
    }

    fun resume() {
        if (closed) return
        Log.i(TAG, "resume")
        active = true
        if (!handshakeComplete) {
            emitStatus(ConnectionState.CONNECTING, "Waiting for CXR-L phone runtime.")
        }
        ensureHandshakeLoop()
    }

    fun pause() {
        Log.i(TAG, "pause")
        active = false
    }

    fun close() {
        closed = true
        active = false
        handshakeComplete = false
        pendingMessages.clear()
        handshakeThread?.interrupt()
    }

    override fun send(message: GlassesToPhoneMessage) {
        if (closed) return
        if (!trySend(message) && shouldQueue(message)) {
            queuePending(message)
        }
    }

    override fun subscribe(listener: (PhoneToGlassesMessage) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    private fun handleIncoming(name: String?, caps: Caps?, payload: ByteArray?) {
        if (name != null && name != TransportConstants.CXR_PHONE_TO_GLASSES_COMMAND) {
            Log.i(TAG, "ignoring CXR-L message on non-phone channel name=$name")
            return
        }
        Log.i(
            TAG,
            "onReceive name=$name capsSize=${caps?.size() ?: -1} caps=${describeCaps(caps)} " +
                "bytesSize=${payload?.size ?: -1} bytesHex=${payload?.toHex(MAX_LOG_BYTES).orEmpty()}",
        )
        val json = payloadToText(caps, payload)
        val message = WireProtocol.decodePhoneMessageOrNull(json)
        if (message == null) {
            Log.w(TAG, "Dropped unsupported CXR-L payload len=${json.length} value=${json.take(96)}")
            emitStatus(
                ConnectionState.DISCONNECTED,
                "Received an unsupported CXR-L phone message.",
                "Update the phone and glasses apps to matching versions.",
            )
            return
        }
        if (!handshakeComplete) {
            if (!handlePreHandshakeMessage(message)) return
            return
        }
        listeners.forEach { it(message) }
    }

    private fun payloadToText(caps: Caps?, payload: ByteArray?): String {
        val candidates = buildList {
            addAll(capsTextCandidates(caps))
            val payloadCaps = runCatching { payload?.let(Caps::fromBytes) }.getOrNull()
            addAll(capsTextCandidates(payloadCaps))
            payload?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }?.let(::add)
        }
        return candidates.firstOrNull(::looksLikeJson)
            ?: candidates.firstOrNull { it.isNotBlank() }
            ?: ""
    }

    private fun capsTextCandidates(caps: Caps?): List<String> {
        if (caps == null || caps.size() == 0) return emptyList()
        return (0 until caps.size()).mapNotNull { index ->
            runCatching { textFromValue(caps.at(index)) }.getOrNull()
        }.filter { it.isNotBlank() }
    }

    private fun textFromValue(value: Caps.Value): String? =
        when (value.type()) {
            Caps.Value.TYPE_STRING -> value.string
            Caps.Value.TYPE_BINARY -> {
                val bytes = value.binary.toByteArray()
                val nestedCaps = runCatching { Caps.fromBytes(bytes) }.getOrNull()
                selectTextCandidate(capsTextCandidates(nestedCaps))
                    ?: bytes.toString(Charsets.UTF_8)
            }
            Caps.Value.TYPE_OBJECT -> selectTextCandidate(capsTextCandidates(value.`object`))
            else -> null
        }

    private fun selectTextCandidate(candidates: List<String>): String? =
        candidates.firstOrNull(::looksLikeJson)
            ?: candidates.firstOrNull { it.isNotBlank() }

    private fun looksLikeJson(value: String): Boolean {
        val trimmed = value.trimStart()
        return trimmed.startsWith("{") || trimmed.startsWith("[")
    }

    private fun Caps.Binary.toByteArray(): ByteArray {
        val bytes = data ?: return ByteArray(0)
        val start = offset.coerceIn(0, bytes.size)
        val count = length.coerceIn(0, bytes.size - start)
        return bytes.copyOfRange(start, start + count)
    }

    private fun describeCaps(caps: Caps?): String {
        if (caps == null) return "null"
        if (caps.size() == 0) return "[]"
        return (0 until caps.size()).joinToString(prefix = "[", postfix = "]") { index ->
            runCatching {
                val value = caps.at(index)
                when (value.type()) {
                    Caps.Value.TYPE_STRING -> {
                        val text = value.string.orEmpty()
                        "$index:S len=${text.length} text=${text.take(MAX_LOG_TEXT)}"
                    }

                    Caps.Value.TYPE_BINARY -> {
                        val bytes = value.binary.toByteArray()
                        "$index:B len=${bytes.size} hex=${bytes.toHex(MAX_LOG_BYTES)} " +
                            "text=${bytes.toUtf8Preview(MAX_LOG_TEXT)}"
                    }

                    else -> "$index:${value.typeLabel()}"
                }
            }.getOrElse {
                "$index:error=${it.javaClass.simpleName}"
            }
        }
    }

    private fun Caps.Value.typeLabel(): String =
        when (type()) {
            Caps.Value.TYPE_VOID -> "V"
            Caps.Value.TYPE_INT32 -> "i"
            Caps.Value.TYPE_UINT32 -> "u"
            Caps.Value.TYPE_INT64 -> "l"
            Caps.Value.TYPE_UINT64 -> "k"
            Caps.Value.TYPE_FLOAT -> "f"
            Caps.Value.TYPE_DOUBLE -> "d"
            Caps.Value.TYPE_STRING -> "S"
            Caps.Value.TYPE_BINARY -> "B"
            Caps.Value.TYPE_OBJECT -> "O"
            else -> type().code.toString()
        }

    private fun ByteArray.toHex(maxBytes: Int): String {
        if (isEmpty()) return ""
        val rendered = take(maxBytes).joinToString(separator = " ") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        return if (size > maxBytes) "$rendered ..." else rendered
    }

    private fun ByteArray.toUtf8Preview(maxChars: Int): String =
        toString(Charsets.UTF_8)
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(maxChars)

    private fun handlePreHandshakeMessage(message: PhoneToGlassesMessage): Boolean =
        when (message) {
            is PhoneToGlassesMessage.HelloAck -> {
                if (message.ack.protocolVersion != TransportConstants.PROTOCOL_VERSION) {
                    emitStatus(
                        ConnectionState.DISCONNECTED,
                        "Connected phone app uses an incompatible Lyrics protocol.",
                        "Update the phone and glasses apps to the same version.",
                    )
                    false
                } else {
                    handshakeComplete = true
                    emitStatus(
                        ConnectionState.CONNECTED,
                        "Connected to iPhone via CXR-L.",
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
                    "Connected phone app uses an incompatible Lyrics protocol.",
                    "Update the phone and glasses apps to the same version.",
                )
                false
            }
        }

    private fun trySend(message: GlassesToPhoneMessage): Boolean {
        if (!active || !handshakeComplete) return false
        return sendRaw(message, updateStatus = true)
    }

    private fun sendRaw(message: GlassesToPhoneMessage, updateStatus: Boolean = false): Boolean {
        val json = WireProtocol.encodeGlassesMessage(message)
        val payload = json.toByteArray(Charsets.UTF_8)
        val caps = Caps().apply {
            write(payload)
        }
        val result = runCatching {
            bridge.sendMessage(TransportConstants.CXR_GLASSES_TO_PHONE_COMMAND, caps, payload)
        }.getOrElse {
            Log.w(TAG, "send failed", it)
            if (updateStatus) {
                emitStatus(ConnectionState.CONNECTING, "CXR-L send failed: ${it.message.orEmpty()}", it.message)
            }
            return false
        }
        if (result != 0) {
            Log.w(TAG, "send result=$result message=${message.javaClass.simpleName}")
            if (updateStatus) {
                emitStatus(ConnectionState.CONNECTING, "CXR-L send failed with code $result.", "CXR-L send failed: $result")
            }
            return false
        }
        Log.i(TAG, "sent ${message.javaClass.simpleName} len=${payload.size}")
        return true
    }

    private fun ensureHandshakeLoop() {
        val existing = handshakeThread
        if (existing?.isAlive == true) return
        handshakeThread = Thread(
            {
                while (!closed) {
                    if (active && !handshakeComplete) {
                        sendRaw(handshakeMessage())
                    }
                    try {
                        Thread.sleep(HANDSHAKE_RETRY_INTERVAL_MS)
                    } catch (_: InterruptedException) {
                    }
                }
            },
            "LyricsCxrHandshake",
        ).also { it.start() }
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
        if (!active || !handshakeComplete || pendingMessages.isEmpty()) return
        val drain = pendingMessages.toList()
        pendingMessages.clear()
            drain.forEach { message ->
                if (!trySend(message)) {
                    queuePending(message)
                }
            }
        }

    private fun emitStatus(
        connectionState: ConnectionState,
        label: String,
        lastError: String? = null,
    ) {
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

    private companion object {
        private const val TAG = "RokidLyricsCXR"
        private const val HANDSHAKE_RETRY_INTERVAL_MS = 2_000L
        private const val MAX_LOG_BYTES = 64
        private const val MAX_LOG_TEXT = 96
        private const val MAX_PENDING_MESSAGES = 2
        private val GLASSES_CAPABILITIES = listOf(
            "request_status",
            "request_snapshot",
            "media_hint",
            "lyrics_window",
            "lyrics_script",
            "lyrics_sync",
            "toggle_playback",
            "cxr_l",
        )
    }
}

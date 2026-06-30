package com.rokid.lyrics.glasses.transport

import android.content.Context
import com.rokid.lyrics.contracts.ConnectionState
import com.rokid.lyrics.contracts.GlassesToPhoneMessage
import com.rokid.lyrics.contracts.PhoneToGlassesMessage
import com.rokid.lyrics.glasses.bluetooth.LyricsBleCentralBridge
import com.rokid.lyrics.glasses.bluetooth.LyricsBluetoothBridge
import com.rokid.lyrics.glasses.cxr.LyricsCxrBridge
import java.util.concurrent.CopyOnWriteArraySet

class LyricsHybridBridge(
    context: Context,
) : LyricsPhoneBridge {
    private val cxrBridge = LyricsCxrBridge(context)
    private val bleCentralBridge = LyricsBleCentralBridge(context)
    private val bluetoothBridge = LyricsBluetoothBridge(context)
    private val listeners = CopyOnWriteArraySet<(PhoneToGlassesMessage) -> Unit>()

    @Volatile private var active = false
    @Volatile private var preferBleCentral = false
    @Volatile private var preferCxr = false

    init {
        cxrBridge.subscribe(::handleCxrMessage)
        bleCentralBridge.subscribe(::handleBleCentralMessage)
        bluetoothBridge.subscribe(::handleBluetoothMessage)
    }

    fun resume() {
        active = true
        cxrBridge.resume()
        bleCentralBridge.resume()
        bluetoothBridge.resume()
    }

    fun pause() {
        active = false
        cxrBridge.pause()
        bleCentralBridge.pause()
        bluetoothBridge.pause()
    }

    fun hibernate() {
        active = false
        cxrBridge.pause()
        bleCentralBridge.hibernate()
        bluetoothBridge.hibernate()
    }

    fun close() {
        active = false
        cxrBridge.close()
        bleCentralBridge.close()
        bluetoothBridge.close()
    }

    override fun send(message: GlassesToPhoneMessage) {
        if (bleCentralBridge.isProtocolReady) {
            bleCentralBridge.send(message)
        } else if (preferCxr && cxrBridge.isProtocolReady) {
            cxrBridge.send(message)
        } else {
            bluetoothBridge.send(message)
        }
    }

    override fun subscribe(listener: (PhoneToGlassesMessage) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    private fun handleCxrMessage(message: PhoneToGlassesMessage) {
        if (preferBleCentral && bleCentralBridge.isProtocolReady) return
        val state = (message as? PhoneToGlassesMessage.Status)?.status?.connectionState
        if (state == ConnectionState.CONNECTED) {
            preferCxr = true
        } else if (state == ConnectionState.DISCONNECTED || state == ConnectionState.CONNECTING) {
            if (preferCxr && !cxrBridge.isProtocolReady) {
                preferCxr = false
                if (active) {
                    bluetoothBridge.resume()
                }
            }
        }
        listeners.forEach { it(message) }
    }

    private fun handleBleCentralMessage(message: PhoneToGlassesMessage) {
        val state = (message as? PhoneToGlassesMessage.Status)?.status?.connectionState
        if (state == ConnectionState.CONNECTED) {
            preferBleCentral = true
            preferCxr = false
            cxrBridge.pause()
            bluetoothBridge.pause()
        } else if (state == ConnectionState.DISCONNECTED || state == ConnectionState.CONNECTING) {
            if (preferBleCentral && !bleCentralBridge.isProtocolReady) {
                preferBleCentral = false
                if (active) {
                    cxrBridge.resume()
                    bluetoothBridge.resume()
                }
            }
        }
        listeners.forEach { it(message) }
    }

    private fun handleBluetoothMessage(message: PhoneToGlassesMessage) {
        if ((preferBleCentral || preferCxr) && message is PhoneToGlassesMessage.Status) return
        listeners.forEach { it(message) }
    }
}

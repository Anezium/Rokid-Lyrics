package com.rokid.lyrics.phone

import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.rokid.lyrics.phone.bluetooth.LyricsBluetoothServer
import com.rokid.lyrics.phone.lyrics.CompositeLyricsProvider
import com.rokid.lyrics.phone.lyrics.LrcLibLyricsClient
import com.rokid.lyrics.phone.lyrics.LyricsRuntimeEngine
import com.rokid.lyrics.phone.lyrics.MusixmatchLyricsProvider
import com.rokid.lyrics.phone.lyrics.NeteaseLyricsProvider
import com.rokid.lyrics.phone.lyrics.ProviderAttemptOutcome
import com.rokid.lyrics.phone.lyrics.ProviderAttemptSummary
import com.rokid.lyrics.phone.media.MediaSessionMonitor
import com.rokid.lyrics.phone.settings.LyricsProviderSettingsStore
import com.rokid.lyrics.phone.settings.MusixmatchCredentials

object LyricsPhoneGraph {
    val stateStore = LyricsPhoneStateStore()

    @Volatile private var initialized = false
    lateinit var lyricsRuntimeEngine: LyricsRuntimeEngine
        private set
    lateinit var mediaSessionMonitor: MediaSessionMonitor
        private set
    lateinit var bluetoothServer: LyricsBluetoothServer
        private set
    lateinit var providerSettingsStore: LyricsProviderSettingsStore
        private set
    private lateinit var appContext: Context
    private lateinit var musixmatchLyricsProvider: MusixmatchLyricsProvider
    private lateinit var neteaseLyricsProvider: NeteaseLyricsProvider

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        providerSettingsStore = LyricsProviderSettingsStore(appContext)
        musixmatchLyricsProvider = MusixmatchLyricsProvider(
            credentialsSource = providerSettingsStore,
            sessionCacheSource = providerSettingsStore,
        )
        neteaseLyricsProvider = NeteaseLyricsProvider()
        lyricsRuntimeEngine = LyricsRuntimeEngine(
            stateStore = stateStore,
            lyricsProvider = CompositeLyricsProvider(
                providers = listOf(
                    musixmatchLyricsProvider,
                    neteaseLyricsProvider,
                    LrcLibLyricsClient(),
                ),
            ),
            onLookupStarted = { syncProviderSettingsState(preserveDynamicLabels = false) },
            onAttemptSummaries = ::onProviderAttemptSummaries,
        )
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
            appVersion = appVersionName(appContext),
        )
        initialized = true
        syncProviderSettingsState()
        syncNotificationAccessFlag()
    }

    @Synchronized
    fun start(context: Context) {
        initialize(context)
        syncProviderSettingsState()
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

    fun musixmatchCredentials(): MusixmatchCredentials? {
        if (!initialized) return null
        return providerSettingsStore.getMusixmatchCredentials()
    }

    fun saveMusixmatchCredentials(email: String, password: String) {
        if (!initialized) return
        providerSettingsStore.saveMusixmatchCredentials(email, password)
        musixmatchLyricsProvider.invalidateSession()
        syncProviderSettingsState(preserveDynamicLabels = false)
        lyricsRuntimeEngine.refresh()
    }

    fun clearMusixmatchCredentials() {
        if (!initialized) return
        providerSettingsStore.clearMusixmatchCredentials()
        musixmatchLyricsProvider.invalidateSession()
        syncProviderSettingsState(preserveDynamicLabels = false)
        lyricsRuntimeEngine.refresh()
    }

    @Synchronized
    fun destroy() {
        if (!initialized) return
        runCatching { mediaSessionMonitor.stop() }
        runCatching { bluetoothServer.stopServing() }
        runCatching { lyricsRuntimeEngine.destroy() }
        initialized = false
    }

    private fun syncProviderSettingsState(preserveDynamicLabels: Boolean = true) {
        if (!initialized) return
        val defaults = defaultProviderSettingsViewState()
        stateStore.updateProviders { current ->
            if (!preserveDynamicLabels) {
                defaults
            } else {
                current.copy(
                    musixmatchConfigured = defaults.musixmatchConfigured,
                    musixmatchStatusLabel = current.musixmatchStatusLabel.takeUnless {
                        it.isBlank() ||
                            it.startsWith("Musixmatch is configured.") ||
                            it.startsWith("Musixmatch is not configured yet.") ||
                            it.startsWith("Sign in to Musixmatch")
                    } ?: defaults.musixmatchStatusLabel,
                    neteaseStatusLabel = current.neteaseStatusLabel.takeUnless {
                        it.isBlank() ||
                            it.startsWith("Netease is enabled.")
                    } ?: defaults.neteaseStatusLabel,
                )
            }
        }
    }

    private fun onProviderAttemptSummaries(summaries: List<ProviderAttemptSummary>) {
        if (!initialized || summaries.isEmpty()) return
        val defaults = defaultProviderSettingsViewState()
        stateStore.updateProviders { current ->
            providerStatusViewState(
                current = current,
                defaults = defaults,
                summaries = summaries,
            )
        }
    }

    private fun defaultProviderSettingsViewState(): ProviderSettingsViewState {
        val musixmatchConfigured = providerSettingsStore.hasMusixmatchCredentials()
        return ProviderSettingsViewState(
            musixmatchConfigured = musixmatchConfigured,
            musixmatchStatusLabel = if (musixmatchConfigured) {
                "Musixmatch is configured. Waiting for the next lyrics lookup."
            } else {
                "Sign in to Musixmatch to try line-synced subtitles before LRCLIB."
            },
            neteaseStatusLabel = "Netease is enabled on this phone. No sign-in is required.",
        )
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
                        "Enable notification access so Lyrics can read media sessions."
                    }
                },
            )
        }
    }

    private fun appVersionName(context: Context): String =
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "unknown" }
}

internal fun providerStatusViewState(
    current: ProviderSettingsViewState,
    defaults: ProviderSettingsViewState,
    summaries: List<ProviderAttemptSummary>,
): ProviderSettingsViewState {
    val resolvedProvider = summaries.lastOrNull { it.outcome == ProviderAttemptOutcome.SUCCESS }?.provider
    var next = defaults.copy(
        musixmatchConfigured = current.musixmatchConfigured,
    )
    summaries.forEach { summary ->
        next = when (summary.provider) {
            "MUSIXMATCH" -> next.copy(
                musixmatchStatusLabel = providerStatusLabel(summary, resolvedProvider),
            )
            "NETEASE" -> next.copy(
                neteaseStatusLabel = providerStatusLabel(summary, resolvedProvider),
            )
            else -> next
        }
    }
    return next
}

internal fun providerStatusLabel(
    summary: ProviderAttemptSummary,
    resolvedProvider: String?,
): String {
    if (resolvedProvider != null && summary.provider != resolvedProvider) {
        return when (summary.outcome) {
            ProviderAttemptOutcome.DISABLED -> summary.detail
            ProviderAttemptOutcome.SUCCESS -> "Current track: synced lyrics found."
            ProviderAttemptOutcome.NO_MATCH,
            ProviderAttemptOutcome.ERROR,
            -> "Current track: fallback resolved by $resolvedProvider."
        }
    }
    return when (summary.outcome) {
        ProviderAttemptOutcome.SUCCESS -> "Current track: synced lyrics found."
        ProviderAttemptOutcome.NO_MATCH -> "Current track: no synced lyrics found."
        ProviderAttemptOutcome.DISABLED -> summary.detail
        ProviderAttemptOutcome.ERROR -> "Last lookup failed: ${summary.detail}"
    }
}

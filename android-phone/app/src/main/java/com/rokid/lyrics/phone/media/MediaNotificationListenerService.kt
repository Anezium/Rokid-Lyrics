package com.rokid.lyrics.phone.media

import android.service.notification.NotificationListenerService
import com.rokid.lyrics.phone.LyricsPhoneGraph

class MediaNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        LyricsPhoneGraph.initialize(this)
        LyricsPhoneGraph.mediaSessionMonitor.start()
    }

    override fun onListenerDisconnected() {
        LyricsPhoneGraph.refresh()
        super.onListenerDisconnected()
    }
}

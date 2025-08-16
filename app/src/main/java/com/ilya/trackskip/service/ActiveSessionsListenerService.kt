package com.ilya.trackskip.service

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.media.session.MediaSessionManager
import com.ilya.trackskip.media.MediaControllerProvider

class ActiveSessionsListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        // Ensure provider is initialized even if AccessibilityService has not started yet
        MediaControllerProvider.initialize(
            context = applicationContext,
            mediaSessionManager = getSystemService(MediaSessionManager::class.java)
        )
        val component = ComponentName(this, javaClass)
        MediaControllerProvider.startListening(component)
    }

    override fun onListenerDisconnected() {
        MediaControllerProvider.stopListening()
        super.onListenerDisconnected()
    }
}



package com.ilya.trackskip.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicReference

object MediaControllerProvider : MediaSessionManager.OnActiveSessionsChangedListener {

    private lateinit var appContext: Context
    private lateinit var mediaSessionManager: MediaSessionManager

    private val activeControllersRef: AtomicReference<List<MediaController>> = AtomicReference(emptyList())

    fun initialize(context: Context, mediaSessionManager: MediaSessionManager) {
        this.appContext = context.applicationContext
        this.mediaSessionManager = mediaSessionManager
        // Listener will be set from NotificationListenerService when permission is granted
    }

    fun startListening(componentName: ComponentName) {
        runCatching {
            mediaSessionManager.addOnActiveSessionsChangedListener(this, componentName)
            val initial = mediaSessionManager.getActiveSessions(componentName)
            activeControllersRef.set(initial ?: emptyList())
        }
    }

    fun stopListening() {
        runCatching { mediaSessionManager.removeOnActiveSessionsChangedListener(this) }
    }

    override fun onActiveSessionsChanged(controllers: MutableList<MediaController>?) {
        activeControllersRef.set(controllers?.toList() ?: emptyList())
    }

    fun hasSeekableController(): Boolean {
        return pickBestController() != null
    }

    fun seekBy(millisecondsDelta: Long): Boolean {
        val controller = pickBestController() ?: return false
        val state = controller.playbackState ?: return false

        val currentPosition = estimatePosition(state)
        val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: Long.MAX_VALUE
        val target = (currentPosition + millisecondsDelta).coerceIn(0L, duration)

        return runCatching {
            controller.transportControls.seekTo(target)
            true
        }.getOrElse {
            // Fallback to fastForward/rewind when seekTo fails
            if (millisecondsDelta > 0) {
                runCatching { controller.transportControls.fastForward(); true }.getOrDefault(false)
            } else {
                runCatching { controller.transportControls.rewind(); true }.getOrDefault(false)
            }
        }
    }

    private fun pickBestController(): MediaController? {
        val controllers = activeControllersRef.get()
        if (controllers.isEmpty()) return null

        fun score(c: MediaController): Int {
            val s = c.playbackState?.state ?: PlaybackState.STATE_NONE
            return when (s) {
                PlaybackState.STATE_PLAYING -> 3
                PlaybackState.STATE_PAUSED -> 2
                PlaybackState.STATE_BUFFERING -> 1
                else -> 0
            }
        }

        // Prefer controllers that support SEEK_TO; fallback to FF/REW if needed
        val sorted = controllers.sortedWith(compareByDescending<MediaController> {
            val actions = it.playbackState?.actions ?: 0L
            val seek = (actions and PlaybackState.ACTION_SEEK_TO) != 0L
            val ff = (actions and PlaybackState.ACTION_FAST_FORWARD) != 0L
            val rw = (actions and PlaybackState.ACTION_REWIND) != 0L
            (seek || (ff && rw))
        }.thenByDescending { score(it) })
        return sorted.firstOrNull()
    }

    private fun estimatePosition(state: PlaybackState): Long {
        val basePosition = state.position
        val updateTime = state.lastPositionUpdateTime
        val speed = state.playbackSpeed

        if (state.state != PlaybackState.STATE_PLAYING) {
            return basePosition
        }

        if (updateTime <= 0L || speed == 0f) return basePosition

        val timeDelta = SystemClock.elapsedRealtime() - updateTime
        val advanced = (timeDelta * speed).toLong()
        return (basePosition + advanced).coerceAtLeast(0L)
    }
}



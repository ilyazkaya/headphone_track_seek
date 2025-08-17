package com.ilya.trackskip.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.ilya.trackskip.media.MediaControllerProvider
import com.ilya.trackskip.prefs.AppPrefs
import android.widget.Toast

class HeadsetKeyInterceptorService : AccessibilityService() {

    private lateinit var audioManager: AudioManager
    private lateinit var mediaSessionManager: MediaSessionManager

    @Volatile
    private var isHeadsetOutputActive: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            updateHeadsetConnectedState()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            updateHeadsetConnectedState()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        // Request key filtering
        val info = serviceInfo
        serviceInfo = info.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }

        audioManager = getSystemService(AudioManager::class.java)
        mediaSessionManager = getSystemService(MediaSessionManager::class.java)

        // Track active media sessions
        MediaControllerProvider.initialize(
            context = applicationContext,
            mediaSessionManager = mediaSessionManager
        )

        // Track whether a headset-like output device is connected
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
        updateHeadsetConnectedState()

        // Observe music stream volume changes to infer headset volume button presses
        lastObservedMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            volumeObserver
        )

        // Dynamic register for undocumented but widely available volume change broadcast
        // Only for best-effort detection on devices that send it
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        registerReceiver(volumeChangeReceiver, filter)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
        runCatching { contentResolver.unregisterContentObserver(volumeObserver) }
        runCatching { unregisterReceiver(volumeChangeReceiver) }
        return super.onUnbind(intent)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!AppPrefs.isEnabled(this)) return false
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount > 0) return false

        if (!isHeadsetOutputActive) return false

        // Prefer to act only when key is from an external device (headset / HID)
        val isExternal = isFromExternalInputDevice(event)

        return when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                val step = AppPrefs.getSeekStepMs(this)
                MediaControllerProvider.seekBy(millisecondsDelta = step)
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                val step = AppPrefs.getSeekStepMs(this)
                MediaControllerProvider.seekBy(millisecondsDelta = -step)
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                val step = AppPrefs.getSeekStepMs(this)
                MediaControllerProvider.seekBy(millisecondsDelta = step)
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                val step = AppPrefs.getSeekStepMs(this)
                MediaControllerProvider.seekBy(millisecondsDelta = -step)
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                when (AppPrefs.getMode(this)) {
                    AppPrefs.SeekTriggerMode.SINGLE_PRESS_HEADSET -> {
                        if (isExternal) {
                            val handled = MediaControllerProvider.seekBy(millisecondsDelta = 10_000L)
                            if (handled) return true
                        }
                        // Phone hardware key: mark and ignore in observer
                        if (!isExternal) {
                            lastPhoneVolKeyTimestampMs = SystemClock.uptimeMillis()
                            lastPhoneVolDirectionUp = true
                            // Do not suppress observer long-term; time-based phone gating will handle it
                        }
                        false
                    }
                    AppPrefs.SeekTriggerMode.DOUBLE_PRESS_VOLUME -> {
                        // Only mark for phone hardware key; allow headset changes to be observed
                        if (!isExternal) {
                            lastPhoneVolKeyTimestampMs = SystemClock.uptimeMillis()
                            lastPhoneVolDirectionUp = true
                            // No suppression here
                        }
                        false
                    }
                    AppPrefs.SeekTriggerMode.SINGLE_PRESS_ANY_VOLUME -> {
                        // Do not seek here; let observer handle. Only mark for phone key so we ignore it.
                        if (!isExternal) {
                            lastPhoneVolKeyTimestampMs = SystemClock.uptimeMillis()
                            lastPhoneVolDirectionUp = true
                            // No suppression here; observer will ignore via phone-key window
                        }
                        false
                    }
                }
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                when (AppPrefs.getMode(this)) {
                    AppPrefs.SeekTriggerMode.SINGLE_PRESS_HEADSET -> {
                        if (isExternal) {
                            val handled = MediaControllerProvider.seekBy(millisecondsDelta = -10_000L)
                            if (handled) return true
                        }
                        if (!isExternal) {
                            lastPhoneVolKeyTimestampMs = SystemClock.uptimeMillis()
                            lastPhoneVolDirectionUp = false
                            // No suppression here
                        }
                        false
                    }
                    AppPrefs.SeekTriggerMode.DOUBLE_PRESS_VOLUME -> {
                        if (!isExternal) {
                            lastPhoneVolKeyTimestampMs = SystemClock.uptimeMillis()
                            lastPhoneVolDirectionUp = false
                            // No suppression here
                        }
                        false
                    }
                    AppPrefs.SeekTriggerMode.SINGLE_PRESS_ANY_VOLUME -> {
                        if (!isExternal) {
                            lastPhoneVolKeyTimestampMs = SystemClock.uptimeMillis()
                            lastPhoneVolDirectionUp = false
                            // No suppression here
                        }
                        false
                    }
                }
            }
            else -> false
        }
    }

    private var lastVolumeUpDownTimestampMs: Long = 0L
    private var lastWasUp: Boolean = false
    private val doublePressWindowMs: Long
        get() = AppPrefs.getDoublePressWindowMs(this)

    private fun handleVolumeDoublePress(isUp: Boolean): Boolean {
        val now = SystemClock.uptimeMillis()
        val withinWindow = (now - lastVolumeUpDownTimestampMs) <= doublePressWindowMs
        val isDouble = withinWindow && (lastWasUp == isUp)

        lastVolumeUpDownTimestampMs = now
        lastWasUp = isUp

        return if (isDouble) {
            val step = AppPrefs.getSeekStepMs(this)
            MediaControllerProvider.seekBy(millisecondsDelta = if (isUp) step else -step)
        } else {
            // Allow system to handle single press as volume change
            false
        }
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // No-op. We only filter key events.
    }

    override fun onInterrupt() {
        // No-op
    }

    private fun updateHeadsetConnectedState() {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        isHeadsetOutputActive = outputs.any { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_HEARING_AID -> true
                else -> false
            }
        }
    }

    private fun isFromExternalInputDevice(event: KeyEvent): Boolean {
        val deviceId = event.deviceId
        if (deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD) return false
        val device = InputDevice.getDevice(deviceId) ?: return false

        // Prefer true for physical external HID devices (e.g., Bluetooth headsets with HID media keys)
        if (!device.isExternal) return false

        val sources = device.sources
        val isButtonLike =
            ((sources and InputDevice.SOURCE_CLASS_BUTTON) == InputDevice.SOURCE_CLASS_BUTTON) ||
            ((sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD) ||
            ((sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)

        if (!isButtonLike) return false

        // On newer Android with absolute volume, some volume changes won't arrive as KeyEvents.
        // In those cases we cannot intercept.
        return true
    }

    // Volume change observation to infer headset volume button presses that do not deliver KeyEvents
    private var lastObservedMusicVolume: Int = -1
    private var suppressNextVolumeHandling: Boolean = false
    private var lastPhoneVolKeyTimestampMs: Long = 0L
    private var lastPhoneVolDirectionUp: Boolean = false

    private data class PendingVolChange(
        val timestampMs: Long,
        val directionUp: Boolean,
        val baselineVolume: Int,
        val count: Int
    )
    private var pendingVolChange: PendingVolChange? = null
    private var ignoreObserverUntilMs: Long = 0L

    private val volumeObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            handleVolumeChange()
        }

        override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
            handleVolumeChange()
        }
    }

    private fun handleVolumeChange() {
        if (!AppPrefs.isEnabled(this)) return
        if (!isHeadsetOutputActive) return
        val mode = AppPrefs.getMode(this)
        if (mode != AppPrefs.SeekTriggerMode.DOUBLE_PRESS_VOLUME && mode != AppPrefs.SeekTriggerMode.SINGLE_PRESS_ANY_VOLUME) return

        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (lastObservedMusicVolume < 0) {
            lastObservedMusicVolume = current
            return
        }

        val now = SystemClock.uptimeMillis()

        val previous = lastObservedMusicVolume
        if (current == previous) return

        val directionUp = current > previous

        val fromPhoneKey = (now - lastPhoneVolKeyTimestampMs) <= AppPrefs.getPhoneIgnoreWindowMs(this) && lastPhoneVolDirectionUp == directionUp

        // Heuristic: consider a change "likely headset" if we didn't see a phone key just before
        val likelyHeadset = !fromPhoneKey

        if (mode == AppPrefs.SeekTriggerMode.SINGLE_PRESS_ANY_VOLUME) {
            if (!likelyHeadset) {
                // Treat as phone volume change; just update baseline
                lastObservedMusicVolume = current
                return
            }

            if (!MediaControllerProvider.hasSeekableController()) {
                lastObservedMusicVolume = current
                return
            }
            val step = AppPrefs.getSeekStepMs(this)
            val handled = MediaControllerProvider.seekBy(if (directionUp) step else -step)
            if (handled) {
                // Suppress observer briefly after restoring baseline, using user-configured window
                val suppressMs = AppPrefs.getPostRestoreSuppressMs(this)
                ignoreObserverUntilMs = SystemClock.uptimeMillis() + suppressMs
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, previous, 0)
                lastObservedMusicVolume = previous
                if (AppPrefs.isDebug(this)) {
                    Toast.makeText(this, if (directionUp) "+10s" else "-10s", Toast.LENGTH_SHORT).show()
                }
            } else {
                lastObservedMusicVolume = current
            }
            pendingVolChange = null
            return
        }

        // Double-press detection on volume changes
        val pending = pendingVolChange
        if (pending == null) {
            pendingVolChange = PendingVolChange(
                timestampMs = now,
                directionUp = directionUp,
                baselineVolume = previous,
                count = 1
            )
            lastObservedMusicVolume = current
            return
        }

        val withinWindow = (now - pending.timestampMs) <= doublePressWindowMs
        val sameDirection = pending.directionUp == directionUp

        if (withinWindow && sameDirection) {
            if (!MediaControllerProvider.hasSeekableController()) {
                pendingVolChange = null
                lastObservedMusicVolume = current
                return
            }

            val newCount = pending.count + 1
            if (newCount >= 2) {
                val step = AppPrefs.getSeekStepMs(this)
                val handled = MediaControllerProvider.seekBy(if (directionUp) step else -step)
                if (handled) {
                    val suppressMs = AppPrefs.getPostRestoreSuppressMs(this)
                    ignoreObserverUntilMs = SystemClock.uptimeMillis() + suppressMs
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, pending.baselineVolume, 0)
                    lastObservedMusicVolume = pending.baselineVolume
                    if (AppPrefs.isDebug(this)) {
                        Toast.makeText(this, if (directionUp) "+10s" else "-10s", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    lastObservedMusicVolume = current
                }
                pendingVolChange = null
            } else {
                pendingVolChange = pending.copy(count = newCount)
                lastObservedMusicVolume = current
            }
        } else {
            pendingVolChange = PendingVolChange(
                timestampMs = now,
                directionUp = directionUp,
                baselineVolume = current,
                count = 1
            )
            lastObservedMusicVolume = current
        }
    }

    private val volumeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != "android.media.VOLUME_CHANGED_ACTION") return
            val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
            if (streamType != AudioManager.STREAM_MUSIC) return
            handleVolumeChange()
        }
    }
}



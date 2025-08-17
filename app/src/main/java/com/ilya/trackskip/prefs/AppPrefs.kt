package com.ilya.trackskip.prefs

import android.content.Context
import android.content.SharedPreferences

object AppPrefs {
    private const val PREFS_NAME = "trackskip_prefs"
    private const val KEY_DEBUG = "debug"
    private const val KEY_MODE = "mode"
    private const val KEY_PHONE_IGNORE_MS = "phone_ignore_ms"
    private const val KEY_POST_RESTORE_MS = "post_restore_ms"
    private const val KEY_DOUBLE_PRESS_MS = "double_press_ms"
    private const val KEY_SEEK_MS = "seek_ms"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ALWAYS_ON = "always_on"

    enum class SeekTriggerMode {
        SINGLE_PRESS_HEADSET,
        DOUBLE_PRESS_VOLUME,
        SINGLE_PRESS_ANY_VOLUME
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isDebug(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEBUG, false)

    fun setDebug(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DEBUG, enabled).apply()
    }

    fun getMode(context: Context): SeekTriggerMode {
        val raw = prefs(context).getString(KEY_MODE, SeekTriggerMode.DOUBLE_PRESS_VOLUME.name)
        return runCatching { SeekTriggerMode.valueOf(raw ?: SeekTriggerMode.DOUBLE_PRESS_VOLUME.name) }
            .getOrDefault(SeekTriggerMode.DOUBLE_PRESS_VOLUME)
    }

    fun setMode(context: Context, mode: SeekTriggerMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    // Timing settings
    fun getPhoneIgnoreWindowMs(context: Context): Long {
        return prefs(context).getLong(KEY_PHONE_IGNORE_MS, 1200L)
    }

    fun setPhoneIgnoreWindowMs(context: Context, valueMs: Long) {
        prefs(context).edit().putLong(KEY_PHONE_IGNORE_MS, valueMs).apply()
    }

    fun getPostRestoreSuppressMs(context: Context): Long {
        return prefs(context).getLong(KEY_POST_RESTORE_MS, 600L)
    }

    fun setPostRestoreSuppressMs(context: Context, valueMs: Long) {
        prefs(context).edit().putLong(KEY_POST_RESTORE_MS, valueMs).apply()
    }

    fun getDoublePressWindowMs(context: Context): Long {
        return prefs(context).getLong(KEY_DOUBLE_PRESS_MS, 450L)
    }

    fun setDoublePressWindowMs(context: Context, valueMs: Long) {
        prefs(context).edit().putLong(KEY_DOUBLE_PRESS_MS, valueMs).apply()
    }

    fun getSeekStepMs(context: Context): Long {
        return prefs(context).getLong(KEY_SEEK_MS, 10_000L)
    }

    fun setSeekStepMs(context: Context, valueMs: Long) {
        prefs(context).edit().putLong(KEY_SEEK_MS, valueMs).apply()
    }

    // Enable/disable behavior
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    // Always-on toggle
    fun isAlwaysOn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ALWAYS_ON, false)

    fun setAlwaysOn(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALWAYS_ON, enabled).apply()
    }
}



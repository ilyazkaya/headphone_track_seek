package com.ilya.trackskip

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.ilya.trackskip.ui.theme.TrackSkipTheme
import com.ilya.trackskip.service.ActiveSessionsListenerService
import com.ilya.trackskip.service.HeadsetKeyInterceptorService
import com.ilya.trackskip.prefs.AppPrefs
import androidx.compose.material3.OutlinedButton

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TrackSkipTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SettingsScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isAccessibilityEnabled = remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    val isNotificationAccessGranted = remember { mutableStateOf(isNotificationListenerEnabled(context)) }

    LaunchedEffect(Unit) {
        isAccessibilityEnabled.value = isAccessibilityServiceEnabled(context)
        isNotificationAccessGranted.value = isNotificationListenerEnabled(context)
    }

    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "Headset skip service")
        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) {
            Text(text = if (isAccessibilityEnabled.value) "Accessibility enabled" else "Enable Accessibility")
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(text = "Active sessions access")
        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) {
            Text(text = if (isNotificationAccessGranted.value) "Notification access granted" else "Grant Notification Access")
        }

        Spacer(modifier = Modifier.height(12.dp))

        val debug = remember { mutableStateOf(AppPrefs.isDebug(context)) }
        Button(onClick = {
            val newVal = !debug.value
            AppPrefs.setDebug(context, newVal)
            debug.value = newVal
        }) {
            Text(text = if (debug.value) "Debug: ON" else "Debug: OFF")
        }

        Spacer(modifier = Modifier.height(12.dp))
        val mode = remember { mutableStateOf(AppPrefs.getMode(context)) }
        OutlinedButton(onClick = {
            val newMode = when (mode.value) {
                AppPrefs.SeekTriggerMode.SINGLE_PRESS_HEADSET -> AppPrefs.SeekTriggerMode.DOUBLE_PRESS_VOLUME
                AppPrefs.SeekTriggerMode.DOUBLE_PRESS_VOLUME -> AppPrefs.SeekTriggerMode.SINGLE_PRESS_ANY_VOLUME
                AppPrefs.SeekTriggerMode.SINGLE_PRESS_ANY_VOLUME -> AppPrefs.SeekTriggerMode.SINGLE_PRESS_HEADSET
            }
            AppPrefs.setMode(context, newMode)
            mode.value = newMode
        }) {
            Text(text = when (mode.value) {
                AppPrefs.SeekTriggerMode.SINGLE_PRESS_HEADSET -> "Mode: Single-press (headset HID)"
                AppPrefs.SeekTriggerMode.DOUBLE_PRESS_VOLUME -> "Mode: Double-press (any volume)"
                AppPrefs.SeekTriggerMode.SINGLE_PRESS_ANY_VOLUME -> "Mode: Single-press (any volume)"
            })
        }

        // Timing tuning
        val phoneIgnore = remember { mutableStateOf(AppPrefs.getPhoneIgnoreWindowMs(context).toString()) }
        val postRestore = remember { mutableStateOf(AppPrefs.getPostRestoreSuppressMs(context).toString()) }
        val doublePress = remember { mutableStateOf(AppPrefs.getDoublePressWindowMs(context).toString()) }

        OutlinedTextField(
            value = phoneIgnore.value,
            onValueChange = { phoneIgnore.value = it.filter { ch -> ch.isDigit() } },
            label = { Text("Phone ignore window (ms)") }
        )
        OutlinedTextField(
            value = postRestore.value,
            onValueChange = { postRestore.value = it.filter { ch -> ch.isDigit() } },
            label = { Text("Post-restore suppression (ms)") }
        )
        OutlinedTextField(
            value = doublePress.value,
            onValueChange = { doublePress.value = it.filter { ch -> ch.isDigit() } },
            label = { Text("Double-press window (ms)") }
        )
        Button(onClick = {
            runCatching { AppPrefs.setPhoneIgnoreWindowMs(context, phoneIgnore.value.toLong()) }
            runCatching { AppPrefs.setPostRestoreSuppressMs(context, postRestore.value.toLong()) }
            runCatching { AppPrefs.setDoublePressWindowMs(context, doublePress.value.toLong()) }
        }) {
            Text("Save timing settings")
        }
    }
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = ComponentName(context, HeadsetKeyInterceptorService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
    return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
}

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val cn = ComponentName(context, ActiveSessionsListenerService::class.java)
    val flat = cn.flattenToString()
    val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
    return enabled.split(":").any { it.equals(flat, ignoreCase = true) }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    TrackSkipTheme {
        SettingsScreen()
    }
}
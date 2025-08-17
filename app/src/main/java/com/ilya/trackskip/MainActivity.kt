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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Divider
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ilya.trackskip.ui.theme.TrackSkipTheme
import com.ilya.trackskip.service.ActiveSessionsListenerService
import com.ilya.trackskip.service.HeadsetKeyInterceptorService
import com.ilya.trackskip.prefs.AppPrefs
import com.ilya.trackskip.service.ForegroundKeeperService
import androidx.compose.foundation.layout.width

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

    PermissionsStatusRefresher {
        isAccessibilityEnabled.value = isAccessibilityServiceEnabled(context)
        isNotificationAccessGranted.value = isNotificationListenerEnabled(context)
    }

    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Step 1: Accessibility
        Text(text = "1) Enable Accessibility")
        Text(text = "Allows the app to detect headset volume changes.")
        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) {
            Text(text = if (isAccessibilityEnabled.value) "Accessibility enabled" else "Open Accessibility Settings")
        }
        Row {
            Text(text = "Status: ")
            Text(
                text = if (isAccessibilityEnabled.value) "Granted" else "Not granted",
                color = if (isAccessibilityEnabled.value) Color(0xFF2E7D32) else Color(0xFFC62828)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Step 2: Notification access
        Text(text = "2) Grant Notification Access")
        Text(text = "Lets the app find the active media player to control seeking.")
        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) {
            Text(text = if (isNotificationAccessGranted.value) "Notification access granted" else "Open Notification Access Settings")
        }
        Row {
            Text(text = "Status: ")
            Text(
                text = if (isNotificationAccessGranted.value) "Granted" else "Not granted",
                color = if (isNotificationAccessGranted.value) Color(0xFF2E7D32) else Color(0xFFC62828)
            )
        }

        // Step 2.5: Always-on (foreground service)
        val alwaysOn = remember { mutableStateOf(AppPrefs.isAlwaysOn(context)) }
        Text(text = "Optional: Keep service running (improves reliability on some devices)")
        OutlinedButton(onClick = {
            val newVal = !alwaysOn.value
            AppPrefs.setAlwaysOn(context, newVal)
            alwaysOn.value = newVal
            ForegroundKeeperService.refresh(context)
        }) {
            Text(text = if (alwaysOn.value) "Always-on: ON" else "Always-on: OFF")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Step 3: Choose mode
        val mode = remember { mutableStateOf(AppPrefs.getMode(context)) }
        Text(text = "3) Choose behavior")
        Text(text = "Recommended: Single-press (any volume) — headset volume presses seek; phone buttons change volume.")
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

        // Optional: Debug
        val debug = remember { mutableStateOf(AppPrefs.isDebug(context)) }
        OutlinedButton(onClick = {
            val newVal = !debug.value
            AppPrefs.setDebug(context, newVal)
            debug.value = newVal
        }) {
            Text(text = if (debug.value) "Debug: ON (shows toasts)" else "Debug: OFF")
        }

        // Step 3.5: Enable/Disable behavior
        val enabled = remember { mutableStateOf(AppPrefs.isEnabled(context)) }
        OutlinedButton(onClick = {
            val newVal = !enabled.value
            AppPrefs.setEnabled(context, newVal)
            enabled.value = newVal
        }) {
            Text(text = if (enabled.value) "Turn OFF remapping" else "Turn ON remapping")
        }

        Divider()
        Text(text = "4) Tune timings (optional)")
        Text(text = "If phone buttons seek: increase 'Phone ignore window'. If headset stops seeking: decrease it.")

        // Timing tuning
        val phoneIgnore = remember { mutableStateOf(AppPrefs.getPhoneIgnoreWindowMs(context).toString()) }
        val postRestore = remember { mutableStateOf(AppPrefs.getPostRestoreSuppressMs(context).toString()) }
        val doublePress = remember { mutableStateOf(AppPrefs.getDoublePressWindowMs(context).toString()) }
        val seekStep = remember { mutableStateOf((AppPrefs.getSeekStepMs(context) / 1000L).toString()) }

        TimingSettingRow(
            label = "Phone ignore window",
            valueState = phoneIgnore,
            min = 400,
            max = 2500
        )
        TimingSettingRow(
            label = "Post-restore suppression",
            valueState = postRestore,
            min = 200,
            max = 2000
        )
        Spacer(modifier = Modifier.height(16.dp))
        if (mode.value == AppPrefs.SeekTriggerMode.DOUBLE_PRESS_VOLUME) {
            TimingSettingRow(
                label = "Double-press window",
                valueState = doublePress,
                min = 250,
                max = 1200
            )
        }
        TimingSettingRow(
            label = "Seek step",
            valueState = seekStep,
            min = 1,
            max = 120,
            unitSuffix = "s"
        )
        Button(onClick = {
            runCatching { AppPrefs.setPhoneIgnoreWindowMs(context, phoneIgnore.value.toLong()) }
            runCatching { AppPrefs.setPostRestoreSuppressMs(context, postRestore.value.toLong()) }
            runCatching { AppPrefs.setDoublePressWindowMs(context, doublePress.value.toLong()) }
            runCatching { AppPrefs.setSeekStepMs(context, seekStep.value.toLong() * 1000L) }
        }) {
            Text("Save timing settings")
        }
    }
}

@Composable
private fun PermissionsStatusRefresher(
    onRefresh: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) { onRefresh() }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun TimingSettingRow(
    label: String,
    valueState: androidx.compose.runtime.MutableState<String>,
    min: Int,
    max: Int,
    unitSuffix: String = "ms"
) {
    val clamped = valueState.value.toIntOrNull()?.coerceIn(min, max) ?: min
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = "$label: ${clamped} $unitSuffix")
        Row {
            Slider(
                value = clamped.toFloat(),
                onValueChange = { v -> valueState.value = v.toInt().toString() },
                valueRange = min.toFloat()..max.toFloat(),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = valueState.value,
                onValueChange = { s ->
                    val filtered = s.filter { it.isDigit() }
                    valueState.value = filtered
                },
                label = { Text(unitSuffix) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .padding(start = 12.dp)
                    .width(100.dp)
            )
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
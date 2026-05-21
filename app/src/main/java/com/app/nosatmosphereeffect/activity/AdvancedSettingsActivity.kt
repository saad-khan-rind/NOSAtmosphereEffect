package com.app.nosatmosphereeffect.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AdvancedSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val activeEffect = intent.getStringExtra("ACTIVE_EFFECT_TYPE") ?: "ORIGINAL"
        val isSamsung = intent.getBooleanExtra("IS_SAMSUNG", false)
        val isPlaylistMode = intent.getBooleanExtra("IS_PLAYLIST_MODE", false)

        val defaultDuration = if (activeEffect == "REVERSE" || activeEffect.contains("COLORFILL")) 1500L else if (activeEffect == "ORIGINAL") 2500L else 500L
        val defaultPoll = if (isSamsung) 30000L else 50L
        val defaultDelay = if (isSamsung) 0L else 800L

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AdvancedSettingsScreen(
                        activeEffect = activeEffect,
                        isPlaylistMode = isPlaylistMode,
                        defaultDuration = defaultDuration,
                        defaultPoll = defaultPoll,
                        defaultDelay = defaultDelay,
                        onShowInfo = { title, msg -> showInfoDialog(title, msg) },
                        onFinish = { finish() }
                    )
                }
            }
        }
    }

    private fun showInfoDialog(title: String, message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Got it", null)
            .show()
    }
}

// Extracted completely out of the activity
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(
    activeEffect: String,
    isPlaylistMode: Boolean,
    defaultDuration: Long,
    defaultPoll: Long,
    defaultDelay: Long,
    onShowInfo: (String, String) -> Unit,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    val wpPrefs = context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)

    var pollInterval by remember { mutableStateOf((prefs.getLong("poll_interval", -1L).takeIf { it != -1L } ?: defaultPoll).toString()) }
    var lockDelay by remember { mutableStateOf((prefs.getLong("lock_delay", -1L).takeIf { it != -1L } ?: defaultDelay).toString()) }
    var animDuration by remember { mutableStateOf((prefs.getLong("anim_duration", -1L).takeIf { it != -1L } ?: defaultDuration).toString()) }

    var dotSize by remember { mutableFloatStateOf(prefs.getFloat("halftone_dot_size", 12.0f)) }
    var isGrayscale by remember { mutableStateOf(prefs.getBoolean("halftone_grayscale", false)) }

    var originX by remember { mutableFloatStateOf(prefs.getFloat("origin_x", 0.5f)) }
    var originY by remember { mutableFloatStateOf(prefs.getFloat("origin_y", 0.8f)) }

    var sat by remember { mutableFloatStateOf(prefs.getFloat("blob_saturation", 1.0f)) }
    var con by remember { mutableFloatStateOf(prefs.getFloat("blob_contrast", 1.0f)) }

    var isNoiseEnabled by remember { mutableStateOf(prefs.getBoolean("enable_noise", false)) }
    var noiseScale by remember { mutableStateOf((prefs.getFloat("noise_scale", -1f).takeIf { it != -1f } ?: 2000.0f).toString()) }
    var noiseStrength by remember { mutableStateOf((prefs.getFloat("noise_strength", -1f).takeIf { it != -1f } ?: 0.06f).toString()) }

    val rotationOptions = listOf("System Theme (Light/Dark)", "Every Lock (Instant)", "1 Minute", "15 Minutes", "30 Minutes", "1 Hour", "3 Hours", "6 Hours", "12 Hours", "24 Hours")
    val rotationValues = listOf<Long>(-1, 0, 1, 15, 30, 60, 180, 360, 720, 1440)

    var expandedRotation by remember { mutableStateOf(false) }
    val savedRotation = wpPrefs.getLong("rotation_interval_minutes", 0)
    var selectedRotationIndex by remember { mutableIntStateOf(rotationValues.indexOf(savedRotation).takeIf { it >= 0 } ?: 1) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Advanced Settings", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)

        if (isPlaylistMode) {
            ExposedDropdownMenuBox(
                expanded = expandedRotation,
                onExpandedChange = { expandedRotation = !expandedRotation }
            ) {
                OutlinedTextField(
                    value = rotationOptions[selectedRotationIndex],
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Rotation Interval") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedRotation) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = expandedRotation,
                    onDismissRequest = { expandedRotation = false }
                ) {
                    rotationOptions.forEachIndexed { index, selectionOption ->
                        DropdownMenuItem(
                            text = { Text(selectionOption) },
                            onClick = {
                                selectedRotationIndex = index
                                expandedRotation = false
                            }
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = pollInterval,
            onValueChange = { pollInterval = it },
            label = { Text("Poll Interval (ms)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            trailingIcon = {
                IconButton(onClick = { onShowInfo("Unlock Check Interval", "Controls how frequently the app checks if the device has been unlocked.\n\n• Recommended:\n30000ms for Samsung\n50ms for others.") }) {
                    Icon(Icons.Default.Info, contentDescription = "Info")
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = lockDelay,
            onValueChange = { lockDelay = it },
            label = { Text("Lock Delay (ms)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            trailingIcon = {
                IconButton(onClick = { onShowInfo("Lock Delay", "Adds a pause before the wallpaper resets.\n\n• Recommended:\n0ms for Samsung\n500ms - 800ms for others.") }) {
                    Icon(Icons.Default.Info, contentDescription = "Info")
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = animDuration,
            onValueChange = { animDuration = it },
            label = { Text("Animation Duration (ms)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        if (activeEffect.contains("HALFTONE")) {
            Card(colors = CardDefaults.cardColors(containerColor = Color.DarkGray.copy(0.5f))) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Halftone Settings", color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Dot Size", color = Color.LightGray, fontSize = 12.sp)
                    Slider(value = dotSize, onValueChange = { dotSize = it }, valueRange = 2f..30f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Grayscale Effect", modifier = Modifier.weight(1f), color = Color.White)
                        Switch(checked = isGrayscale, onCheckedChange = { isGrayscale = it })
                    }
                }
            }
        }

        if (activeEffect.contains("COLORFILL") || activeEffect.contains("HALFTONE")) {
            Card(colors = CardDefaults.cardColors(containerColor = Color.DarkGray.copy(0.5f))) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Effect Origin (X / Y)", color = Color.White, fontWeight = FontWeight.Bold)
                    Slider(value = originX, onValueChange = { originX = it }, valueRange = 0f..1f)
                    Slider(value = originY, onValueChange = { originY = it }, valueRange = 0f..1f)
                }
            }
        }

        if (activeEffect == "ORIGINAL" || activeEffect == "REVERSE") {
            Card(colors = CardDefaults.cardColors(containerColor = Color.DarkGray.copy(0.5f))) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Blob Color Tuning", color = Color.White, fontWeight = FontWeight.Bold)
                    Text("Saturation", color = Color.LightGray, fontSize = 12.sp)
                    Slider(value = sat, onValueChange = { sat = it }, valueRange = 0f..3f)
                    Text("Contrast", color = Color.LightGray, fontSize = 12.sp)
                    Slider(value = con, onValueChange = { con = it }, valueRange = 0f..3f)
                }
            }
        }

        if (!activeEffect.contains("HALFTONE") && !activeEffect.contains("COLORFILL")) {
            Card(colors = CardDefaults.cardColors(containerColor = Color.DarkGray.copy(0.5f))) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Enable Static Noise", modifier = Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Bold)
                        Switch(checked = isNoiseEnabled, onCheckedChange = { isNoiseEnabled = it })
                    }
                    if (isNoiseEnabled) {
                        OutlinedTextField(
                            value = noiseScale, onValueChange = { noiseScale = it },
                            label = { Text("Noise Scale") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                        OutlinedTextField(
                            value = noiseStrength, onValueChange = { noiseStrength = it },
                            label = { Text("Noise Strength") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(
                onClick = {
                    prefs.edit { clear() }
                    onFinish()
                },
                modifier = Modifier.weight(1f)
            ) { Text("Reset Defaults") }

            Button(
                onClick = {
                    wpPrefs.edit().putLong("rotation_interval_minutes", rotationValues[selectedRotationIndex]).apply()
                    prefs.edit {
                        putLong("poll_interval", pollInterval.toLongOrNull() ?: defaultPoll)
                        putLong("lock_delay", lockDelay.toLongOrNull() ?: defaultDelay)
                        putLong("anim_duration", animDuration.toLongOrNull() ?: defaultDuration)
                        putBoolean("enable_noise", isNoiseEnabled)
                        putFloat("noise_scale", noiseScale.toFloatOrNull() ?: 2000.0f)
                        putFloat("noise_strength", noiseStrength.toFloatOrNull() ?: 0.06f)
                        putFloat("halftone_dot_size", dotSize)
                        putBoolean("halftone_grayscale", isGrayscale)
                        putFloat("blob_saturation", sat)
                        putFloat("blob_contrast", con)
                        putFloat("origin_x", originX)
                        putFloat("origin_y", originY)
                    }
                    context.sendBroadcast(Intent("com.app.nosatmosphereeffect.UPDATE_CONFIG").setPackage(context.packageName))
                    Toast.makeText(context, "Settings Applied!", Toast.LENGTH_SHORT).show()
                    onFinish()
                },
                modifier = Modifier.weight(1f)
            ) { Text("Apply") }
        }
    }
}
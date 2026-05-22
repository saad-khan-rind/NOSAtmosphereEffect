package com.app.nosatmosphereeffect.activity

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.app.nosatmosphereeffect.ui.theme.AtmoTheme
import com.app.nosatmosphereeffect.ui.theme.BrandPrimary
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AdvancedSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val activeEffect  = intent.getStringExtra("ACTIVE_EFFECT_TYPE") ?: "ORIGINAL"
        val isSamsung     = intent.getBooleanExtra("IS_SAMSUNG", false)
        val isPlaylist    = intent.getBooleanExtra("IS_PLAYLIST_MODE", false)

        setContent {
            AtmoTheme {
                AdvancedSettingsScreen(activeEffect, isSamsung, isPlaylist)
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AdvancedSettingsScreen(
        activeEffect: String,
        isSamsung: Boolean,
        isPlaylistMode: Boolean
    ) {
        // ── Defaults ────────────────────────────────────────────────────
        val defaultDuration = when {
            activeEffect == "REVERSE" || activeEffect.contains("COLORFILL") -> 1500L
            activeEffect == "ORIGINAL" -> 2500L
            else -> 500L
        }
        val defaultPoll  = if (isSamsung) 30000L else 50L
        val defaultDelay = if (isSamsung) 0L     else 800L

        val prefs    = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val wpPrefs  = getSharedPreferences("wallpaper_prefs", MODE_PRIVATE)

        // ── State ────────────────────────────────────────────────────────
        var pollText      by remember { mutableStateOf(prefs.getLong("poll_interval", defaultPoll).toString()) }
        var delayText     by remember { mutableStateOf(prefs.getLong("lock_delay", defaultDelay).toString()) }
        var durationText  by remember { mutableStateOf(prefs.getLong("anim_duration", defaultDuration).toString()) }

        var enableNoise   by remember { mutableStateOf(prefs.getBoolean("enable_noise", false)) }
        var noiseScale    by remember { mutableStateOf(prefs.getFloat("noise_scale", -1f).let { if (it == -1f) "2000.0" else it.toString() }) }
        var noiseStrength by remember { mutableStateOf(prefs.getFloat("noise_strength", -1f).let { if (it == -1f) "0.06" else it.toString() }) }

        var dotSize       by remember { mutableFloatStateOf(prefs.getFloat("halftone_dot_size", 12.0f)) }
        var isGrayscale   by remember { mutableStateOf(prefs.getBoolean("halftone_grayscale", false)) }

        var originX       by remember { mutableFloatStateOf(prefs.getFloat("origin_x", 0.5f)) }
        var originY       by remember { mutableFloatStateOf(prefs.getFloat("origin_y", 0.8f)) }

        var saturation    by remember { mutableFloatStateOf(prefs.getFloat("blob_saturation", 1.0f)) }
        var contrast      by remember { mutableFloatStateOf(prefs.getFloat("blob_contrast", 1.0f)) }

        val rotationOptions = listOf(
            "System Theme (Light/Dark)" to -1L,
            "Every Lock (Instant)"      to 0L,
            "1 Minute"                  to 1L,
            "15 Minutes"                to 15L,
            "30 Minutes"                to 30L,
            "1 Hour"                    to 60L,
            "3 Hours"                   to 180L,
            "6 Hours"                   to 360L,
            "12 Hours"                  to 720L,
            "24 Hours"                  to 1440L
        )
        val savedRotation    = wpPrefs.getLong("rotation_interval_minutes", 0L)
        val defaultRotIdx    = rotationOptions.indexOfFirst { it.second == savedRotation }.takeIf { it >= 0 } ?: 1
        var selectedRotIdx   by remember { mutableIntStateOf(defaultRotIdx) }
        var rotationExpanded by remember { mutableStateOf(false) }

        // ─────────────────────────────────────────────────────────────────
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Advanced Settings",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = BrandPrimary
                )

                // ── Timing section ────────────────────────────────────────
                SectionCard(title = "Timing") {
                    // Poll interval
                    OutlinedTextField(
                        value         = pollText,
                        onValueChange = { pollText = it },
                        label         = { Text("Unlock Check Interval (ms)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier      = Modifier.fillMaxWidth(),
                        trailingIcon  = {
                            IconButton(onClick = { showPollHelp() }) {
                                Icon(Icons.Default.Info, contentDescription = "Help", tint = BrandPrimary)
                            }
                        }
                    )

                    // Lock delay
                    OutlinedTextField(
                        value         = delayText,
                        onValueChange = { delayText = it },
                        label         = { Text("Lock Delay (ms)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier      = Modifier.fillMaxWidth(),
                        trailingIcon  = {
                            IconButton(onClick = { showDelayHelp() }) {
                                Icon(Icons.Default.Info, contentDescription = "Help", tint = BrandPrimary)
                            }
                        }
                    )

                    // Animation duration
                    OutlinedTextField(
                        value         = durationText,
                        onValueChange = { durationText = it },
                        label         = { Text("Animation Duration (ms)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier      = Modifier.fillMaxWidth()
                    )
                }

                // ── Playlist rotation (only when in playlist mode) ─────────
                if (isPlaylistMode) {
                    SectionCard(title = "Playlist Rotation") {
                        ExposedDropdownMenuBox(
                            expanded        = rotationExpanded,
                            onExpandedChange = { rotationExpanded = !rotationExpanded }
                        ) {
                            OutlinedTextField(
                                value           = rotationOptions[selectedRotIdx].first,
                                onValueChange   = {},
                                readOnly        = true,
                                label           = { Text("Rotation Interval") },
                                trailingIcon    = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = rotationExpanded) },
                                modifier        = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded        = rotationExpanded,
                                onDismissRequest = { rotationExpanded = false }
                            ) {
                                rotationOptions.forEachIndexed { idx, (label, _) ->
                                    DropdownMenuItem(
                                        text    = { Text(label) },
                                        onClick = {
                                            selectedRotIdx   = idx
                                            rotationExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Halftone settings ─────────────────────────────────────
                if (activeEffect.contains("HALFTONE")) {
                    SectionCard(title = "Halftone Settings") {
                        Text("Dot Size: ${"%.1f".format(dotSize)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        Slider(
                            value         = dotSize,
                            onValueChange = { dotSize = it },
                            valueRange    = 4f..40f,
                            modifier      = Modifier.fillMaxWidth(),
                            colors        = SliderDefaults.colors(thumbColor = BrandPrimary, activeTrackColor = BrandPrimary)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()) {
                            Text("Grayscale", color = MaterialTheme.colorScheme.onSurface)
                            Switch(
                                checked = isGrayscale,
                                onCheckedChange = { isGrayscale = it },
                                colors  = SwitchDefaults.colors(checkedThumbColor = BrandPrimary)
                            )
                        }
                    }
                }

                // ── Color Fill settings ───────────────────────────────────
                if (activeEffect.contains("COLORFILL")) {
                    SectionCard(title = "Color Fill Settings") {
                        Text("Origin X: ${"%.2f".format(originX)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        Slider(
                            value         = originX,
                            onValueChange = { originX = it },
                            valueRange    = 0f..1f,
                            modifier      = Modifier.fillMaxWidth(),
                            colors        = SliderDefaults.colors(thumbColor = BrandPrimary, activeTrackColor = BrandPrimary)
                        )
                        Text("Origin Y: ${"%.2f".format(originY)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        Slider(
                            value         = originY,
                            onValueChange = { originY = it },
                            valueRange    = 0f..1f,
                            modifier      = Modifier.fillMaxWidth(),
                            colors        = SliderDefaults.colors(thumbColor = BrandPrimary, activeTrackColor = BrandPrimary)
                        )
                    }
                }

                // ── Blob color settings (ORIGINAL / REVERSE only) ─────────
                if (activeEffect == "ORIGINAL" || activeEffect == "REVERSE") {
                    SectionCard(title = "Blob Color") {
                        Text("Saturation: ${"%.2f".format(saturation)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        Slider(
                            value         = saturation,
                            onValueChange = { saturation = it },
                            valueRange    = 0f..3f,
                            modifier      = Modifier.fillMaxWidth(),
                            colors        = SliderDefaults.colors(thumbColor = BrandPrimary, activeTrackColor = BrandPrimary)
                        )
                        Text("Contrast: ${"%.2f".format(contrast)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        Slider(
                            value         = contrast,
                            onValueChange = { contrast = it },
                            valueRange    = 0f..3f,
                            modifier      = Modifier.fillMaxWidth(),
                            colors        = SliderDefaults.colors(thumbColor = BrandPrimary, activeTrackColor = BrandPrimary)
                        )
                    }
                }

                // ── Noise settings (not for HALFTONE / COLORFILL) ─────────
                if (!activeEffect.contains("HALFTONE") && !activeEffect.contains("COLORFILL")) {
                    SectionCard(title = "Noise") {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()) {
                            Text("Enable Noise", color = MaterialTheme.colorScheme.onSurface)
                            Switch(
                                checked = enableNoise,
                                onCheckedChange = { enableNoise = it },
                                colors  = SwitchDefaults.colors(checkedThumbColor = BrandPrimary)
                            )
                        }
                        if (enableNoise) {
                            OutlinedTextField(
                                value         = noiseScale,
                                onValueChange = { noiseScale = it },
                                label         = { Text("Noise Scale") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier      = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value         = noiseStrength,
                                onValueChange = { noiseStrength = it },
                                label         = { Text("Noise Strength") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier      = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // ── Action buttons ────────────────────────────────────────
                Button(
                    onClick = {
                        val poll     = pollText.toLongOrNull()     ?: defaultPoll
                        val delay    = delayText.toLongOrNull()    ?: defaultDelay
                        val duration = durationText.toLongOrNull() ?: defaultDuration
                        val nsVal    = noiseScale.toFloatOrNull()    ?: 2000f
                        val nStrVal  = noiseStrength.toFloatOrNull() ?: 0.06f

                        wpPrefs.edit {
                            putLong(
                                "rotation_interval_minutes",
                                rotationOptions[selectedRotIdx].second
                            )
                        }

                        prefs.edit {
                            putLong("poll_interval", poll)
                            putLong("lock_delay", delay)
                            putLong("anim_duration", duration)
                            putBoolean("enable_noise", enableNoise)
                            putFloat("noise_scale", nsVal)
                            putFloat("noise_strength", nStrVal)
                            putFloat("halftone_dot_size", dotSize)
                            putBoolean("halftone_grayscale", isGrayscale)
                            putFloat("blob_saturation", saturation)
                            putFloat("blob_contrast", contrast)
                            putFloat("origin_x", originX)
                            putFloat("origin_y", originY)
                        }
                        sendUpdateBroadcast()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary)
                ) { Text("Apply Settings", color = MaterialTheme.colorScheme.onPrimary) }

                OutlinedButton(
                    onClick = {
                        prefs.edit {
                            remove("poll_interval"); remove("lock_delay"); remove("anim_duration")
                            remove("enable_noise");  remove("noise_scale"); remove("noise_strength")
                            remove("halftone_dot_size"); remove("halftone_grayscale")
                            remove("blob_saturation"); remove("blob_contrast")
                            remove("origin_x"); remove("origin_y")
                        }
                        // Reset local state
                        pollText      = defaultPoll.toString()
                        delayText     = defaultDelay.toString()
                        durationText  = defaultDuration.toString()
                        enableNoise   = false
                        noiseScale    = "2000.0"
                        noiseStrength = "0.06"
                        dotSize       = 12.0f
                        isGrayscale   = false
                        saturation    = 1.0f
                        contrast      = 1.0f
                        originX       = 0.5f
                        originY       = 0.8f
                        sendUpdateBroadcast()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Reset to Defaults") }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    @Composable
    private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface)
                content()
            }
        }
    }

    // ── Business logic (unchanged) ────────────────────────────────────────

    private fun sendUpdateBroadcast() {
        val i = Intent("com.app.nosatmosphereeffect.UPDATE_CONFIG")
        i.setPackage(packageName)
        sendBroadcast(i)
        Toast.makeText(this, "Settings Applied!", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun showPollHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Unlock Check Interval")
            .setMessage(
                "Controls how frequently the app checks if the device has been unlocked.\n\n" +
                        "• What it solves:\nIf you unlock your phone and the animation starts after a delay, lower this value.\n\n" +
                        "• Recommended:\n30000ms for Samsung and most devices (Saves Battery).\n50ms if you experience delayed animation start."
            )
            .setPositiveButton("Got it", null)
            .show()
    }

    private fun showDelayHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Lock Delay")
            .setMessage(
                "Adds a pause before the wallpaper resets when you lock the phone.\n\n" +
                        "• What it solves:\nIf you see a glimpse of the wallpaper resetting/snapping back before the screen turns fully black, increase this value.\n\n" +
                        "• Recommended:\n0ms for Samsung/Most devices.\n500ms - 800ms if you experience the glitch.\n\n" +
                        "⚠️ Note: If this value is too high, unlocking immediately after locking might show the wallpaper in its previous state."
            )
            .setPositiveButton("Got it", null)
            .show()
    }
}

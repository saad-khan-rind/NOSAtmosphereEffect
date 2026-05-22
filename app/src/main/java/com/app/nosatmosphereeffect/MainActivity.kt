package com.app.nosatmosphereeffect

import android.app.WallpaperManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.app.nosatmosphereeffect.activity.AdvancedSettingsActivity
import com.app.nosatmosphereeffect.activity.BlurToSharpCropActivity
import com.app.nosatmosphereeffect.activity.CropActivity
import com.app.nosatmosphereeffect.activity.EffectSelectionActivity
import com.app.nosatmosphereeffect.activity.PlaylistEditorActivity
import com.app.nosatmosphereeffect.service.AtmosphereService
import com.app.nosatmosphereeffect.service.BlurToSharpService
import com.app.nosatmosphereeffect.service.ColorFillReverseService
import com.app.nosatmosphereeffect.service.ColorFillService
import com.app.nosatmosphereeffect.service.FrostedReverseService
import com.app.nosatmosphereeffect.service.FrostedService
import com.app.nosatmosphereeffect.service.HalftoneReverseService
import com.app.nosatmosphereeffect.service.HalftoneService
import com.app.nosatmosphereeffect.ui.theme.AtmoTheme
import com.app.nosatmosphereeffect.ui.theme.BrandPrimary
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

class MainActivity : AppCompatActivity() {

    // ── Compose-observable state ───────────────────────────────────────────
    private var statusMessage      by mutableStateOf("")
    private var wallpaperActive    by mutableStateOf(false)
    private var dimnessValue       by mutableFloatStateOf(0.2f)
    private var dimnessEnabled     by mutableStateOf(false)
    private var showBlurCard       by mutableStateOf(false)
    private var blurValue          by mutableFloatStateOf(200f)
    private var blurEnabled        by mutableStateOf(false)
    private var notifyColors       by mutableStateOf(true)

    private var isPlaylistModeActive = false

    // ── Image picker launchers (unchanged) ────────────────────────────────
    private val pickSingleImage = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? -> uri?.let { launchCropActivity(it) } }

    private val pickMultipleImages = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()
    ) { uris: List<android.net.Uri> ->
        if (uris.isNotEmpty()) launchMultiCropActivity(ArrayList(uris))
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeSmartDefaults()

        setContent {
            AtmoTheme {
                MainScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkWallpaperStatus()
    }

    // ── Composable UI ─────────────────────────────────────────────────────
    @Composable
    private fun MainScreen() {
        val context = LocalContext.current
        val scrollState = rememberScrollState()

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp, vertical = 48.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // App title
                Text(
                    text = "AtmoEngine",
                    color = BrandPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )

                // Status text
                Text(
                    text = statusMessage,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )

                // ── Setup button (no active wallpaper) ─────────────────
                if (!wallpaperActive) {
                    Button(
                        onClick = {
                            startActivity(Intent(context, EffectSelectionActivity::class.java))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary)
                    ) {
                        Text("Setup Wallpaper", color = MaterialTheme.colorScheme.onPrimary)
                    }
                }

                // ── Update controls (active wallpaper) ─────────────────
                if (wallpaperActive) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val i = Intent(context, EffectSelectionActivity::class.java)
                                i.putExtra("UPDATE_EFFECT_ONLY", true)
                                startActivity(i)
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Update Effect") }

                        OutlinedButton(
                            onClick = { showImageSelectionDialog() },
                            modifier = Modifier.weight(1f)
                        ) { Text("Update Image") }
                    }
                }

                // ── Settings section ───────────────────────────────────
                if (wallpaperActive) {

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    // Dimness
                    SettingsCard(title = "Dimness") {
                        Text(
                            text = "Level: ${"%.0f".format(dimnessValue * 100)}%",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                        Slider(
                            value = dimnessValue,
                            onValueChange = {
                                dimnessValue = it
                                updateButtonStateDimness(it)
                            },
                            valueRange = 0f..1f,
                            modifier = Modifier.fillMaxWidth(),
                            colors = SliderDefaults.colors(thumbColor = BrandPrimary, activeTrackColor = BrandPrimary)
                        )
                        Button(
                            onClick = { applyDimnessUpdate() },
                            enabled = dimnessEnabled,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary)
                        ) { Text("Update Dimness", color = MaterialTheme.colorScheme.onPrimary) }
                    }

                    // Blur (FROSTED effects only)
                    if (showBlurCard) {
                        SettingsCard(title = "Blur Strength") {
                            Text(
                                text = "Radius: ${"%.0f".format(blurValue)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp
                            )
                            Slider(
                                value = blurValue,
                                onValueChange = {
                                    blurValue = it
                                    updateBlurButtonState(it)
                                },
                                valueRange = 1f..500f,
                                modifier = Modifier.fillMaxWidth(),
                                colors = SliderDefaults.colors(thumbColor = BrandPrimary, activeTrackColor = BrandPrimary)
                            )
                            Button(
                                onClick = { applyBlurUpdate() },
                                enabled = blurEnabled,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary)
                            ) { Text("Update Blur", color = MaterialTheme.colorScheme.onPrimary) }
                        }
                    }

                    // Colors
                    SettingsCard(title = "System Colors") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Notify system colors",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = notifyColors,
                                onCheckedChange = { isChecked ->
                                    notifyColors = isChecked
                                    getSharedPreferences("app_prefs", MODE_PRIVATE).edit {
                                        putBoolean("notify_system_colors", isChecked)
                                    }
                                    sendConfigUpdate()
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = BrandPrimary)
                            )
                        }
                    }

                    // Advanced settings
                    OutlinedButton(
                        onClick = {
                            val i = Intent(context, AdvancedSettingsActivity::class.java)
                            i.putExtra("ACTIVE_EFFECT_TYPE", getActiveEffectType() ?: "ORIGINAL")
                            i.putExtra("IS_SAMSUNG", isSamsungDevice())
                            i.putExtra("IS_PLAYLIST_MODE", isPlaylistModeActive)
                            startActivity(i)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Advanced Settings") }
                }
            }
        }
    }

    @Composable
    private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                content()
            }
        }
    }

    // ── Business logic (unchanged) ────────────────────────────────────────

    private fun isSamsungDevice() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    private fun initializeSmartDefaults() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        if (!prefs.contains("poll_interval")) {
            val isSamsung = isSamsungDevice()
            prefs.edit {
                putLong("poll_interval", if (isSamsung) 30000L else 50L)
                putLong("lock_delay",    if (isSamsung) 0L     else 800L)
            }
        }
    }

    private fun checkWallpaperStatus() {
        val activeEffect = getActiveEffectType()
        if (activeEffect != null) {
            statusMessage  = "Wallpaper is active! Customize your experience below."
            wallpaperActive = true
            loadCurrentDimness()

            showBlurCard = activeEffect.contains("FROSTED")
            if (showBlurCard) loadCurrentBlur()

            // ── Playlist mode detection ───────────────────────────────
            val playlistDir = File(filesDir, "playlist")
            isPlaylistModeActive = false
            if (playlistDir.exists() && playlistDir.isDirectory) {
                val files = playlistDir.listFiles { _, name -> name.endsWith(".jpg") }
                if (!files.isNullOrEmpty() && files.size > 1) isPlaylistModeActive = true
            }

            // ── Detect mode change & force defaults ───────────────────
            val prefs       = getSharedPreferences("app_prefs", MODE_PRIVATE)
            val lastMode    = prefs.getString("last_known_wallpaper_mode", "UNKNOWN")
            val currentMode = if (isPlaylistModeActive) "PLAYLIST" else "SINGLE"
            if (lastMode != currentMode) {
                prefs.edit {
                    putBoolean("notify_system_colors", !isPlaylistModeActive)
                    putString("last_known_wallpaper_mode", currentMode)
                }
                sendConfigUpdate()
            }

            // ── Sync switch state ─────────────────────────────────────
            notifyColors = prefs.getBoolean("notify_system_colors", !isPlaylistModeActive)

        } else {
            statusMessage   = getString(R.string.status_instruction)
            wallpaperActive = false
        }
    }

    private fun sendConfigUpdate() {
        val i = Intent("com.app.nosatmosphereeffect.UPDATE_CONFIG")
        i.setPackage(packageName)
        sendBroadcast(i)
    }

    private fun updateButtonStateDimness(value: Float) {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val default = if (!getActiveEffectType().isNullOrEmpty() &&
            getActiveEffectType()!!.contains("HALFTONE")) 0.0f else 0.2f
        dimnessEnabled = value != prefs.getFloat("dim_level", default)
    }

    private fun loadCurrentDimness() {
        val prefs   = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val default = if (!getActiveEffectType().isNullOrEmpty() &&
            getActiveEffectType()!!.contains("HALFTONE")) 0.0f else 0.2f
        val current = prefs.getFloat("dim_level", default)
        dimnessValue   = current
        dimnessEnabled = false
    }

    private fun applyDimnessUpdate() {
        val new = dimnessValue
        getSharedPreferences("app_prefs", MODE_PRIVATE).edit { putFloat("dim_level", new) }
        sendConfigUpdate()
        dimnessEnabled = false
        android.widget.Toast.makeText(this, "Wallpaper Updated!", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun loadCurrentBlur() {
        val current = getSharedPreferences("app_prefs", MODE_PRIVATE)
            .getFloat("frosted_blur_radius", 200f)
        blurValue   = current
        blurEnabled = false
    }

    private fun updateBlurButtonState(value: Float) {
        val saved = getSharedPreferences("app_prefs", MODE_PRIVATE)
            .getFloat("frosted_blur_radius", 200f)
        blurEnabled = value != saved
    }

    private fun applyBlurUpdate() {
        getSharedPreferences("app_prefs", MODE_PRIVATE)
            .edit { putFloat("frosted_blur_radius", blurValue) }
        sendConfigUpdate()
        blurEnabled = false
        android.widget.Toast.makeText(this, "Blur Strength Updated!", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun getActiveEffectType(): String? {
        val wm   = WallpaperManager.getInstance(this)
        val info = wm.wallpaperInfo ?: return null
        if (info.packageName == packageName) {
            return when (info.component.className) {
                AtmosphereService::class.java.name       -> "ORIGINAL"
                BlurToSharpService::class.java.name      -> "REVERSE"
                FrostedService::class.java.name          -> "FROSTED"
                FrostedReverseService::class.java.name   -> "FROSTED_REVERSE"
                HalftoneService::class.java.name         -> "HALFTONE"
                HalftoneReverseService::class.java.name  -> "HALFTONE_REVERSE"
                ColorFillService::class.java.name        -> "COLORFILL"
                ColorFillReverseService::class.java.name -> "COLORFILL_REVERSE"
                else -> null
            }
        }
        return null
    }

    private fun showImageSelectionDialog() {
        val options = if (isPlaylistModeActive)
            arrayOf("Single Image", "Create New Playlist", "Edit Existing Playlist")
        else
            arrayOf("Single Image", "Multiple Images (Playlist)")

        MaterialAlertDialogBuilder(this)
            .setTitle("Select Wallpaper Mode")
            .setItems(options) { _, which ->
                if (isPlaylistModeActive) {
                    when (which) {
                        0 -> pickSingleImage.launch("image/*")
                        1 -> pickMultipleImages.launch("image/*")
                        2 -> launchEditExistingPlaylist()
                    }
                } else {
                    when (which) {
                        0 -> pickSingleImage.launch("image/*")
                        1 -> pickMultipleImages.launch("image/*")
                    }
                }
            }
            .show()
    }

    private fun launchEditExistingPlaylist() {
        val playlistDir = File(filesDir, "playlist")
        if (!playlistDir.exists()) return
        val files = playlistDir.listFiles { _, name -> name.endsWith(".jpg") }
        if (files.isNullOrEmpty()) return
        files.sortBy { it.nameWithoutExtension.substringAfter('_').toIntOrNull() ?: 0 }

        val effectId = getActiveEffectType() ?: "ORIGINAL"
        val i = Intent(this, PlaylistEditorActivity::class.java)
        i.putExtra("EDIT_EXISTING", true)
        i.putExtra("EFFECT_ID", effectId)
        startActivity(i)
    }

    private fun launchCropActivity(uri: android.net.Uri) {
        val effectId = getActiveEffectType() ?: "ORIGINAL"
        val i = if (effectId.contains("REVERSE"))
            Intent(this, BlurToSharpCropActivity::class.java)
        else
            Intent(this, CropActivity::class.java)
        i.data = uri
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.putExtra("EFFECT_ID", effectId)
        startActivity(i)
    }

    private fun launchMultiCropActivity(uris: ArrayList<android.net.Uri>) {
        val effectId = getActiveEffectType() ?: "ORIGINAL"
        val i = Intent(this, PlaylistEditorActivity::class.java)
        i.data = uris[0]
        val clipData = android.content.ClipData.newUri(contentResolver, "Images", uris[0])
        for (idx in 1 until uris.size) clipData.addItem(android.content.ClipData.Item(uris[idx]))
        i.clipData = clipData
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.putParcelableArrayListExtra("IMAGE_URIS", uris)
        i.putExtra("EFFECT_ID", effectId)
        startActivity(i)
    }
}
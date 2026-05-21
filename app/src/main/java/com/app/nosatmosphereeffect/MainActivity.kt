package com.app.nosatmosphereeffect

import android.app.WallpaperManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.app.nosatmosphereeffect.activity.AdvancedSettingsActivity
import com.app.nosatmosphereeffect.activity.BlurToSharpCropActivity
import com.app.nosatmosphereeffect.activity.CropActivity
import com.app.nosatmosphereeffect.activity.EffectSelectionActivity
import com.app.nosatmosphereeffect.activity.PlaylistEditorActivity
import com.app.nosatmosphereeffect.service.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

class MainActivity : ComponentActivity() {

    // Compose States
    private var activeEffect by mutableStateOf<String?>(null)
    private var isPlaylistModeActive by mutableStateOf(false)
    private var dimLevel by mutableFloatStateOf(0.2f)
    private var savedDimLevel by mutableFloatStateOf(0.2f)
    private var blurStrength by mutableFloatStateOf(200f)
    private var savedBlurStrength by mutableFloatStateOf(200f)
    private var syncColors by mutableStateOf(false)

    private val pickSingleImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { launchCropActivity(it) }
    }

    private val pickMultipleImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            launchMultiCropActivity(ArrayList(uris))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeSmartDefaults()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    MainScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkWallpaperStatus()
    }

    @Composable
    fun MainScreen() {
        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = "App Logo",
                modifier = Modifier
                    .size(96.dp)
                    .padding(bottom = 32.dp),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
            )

            Text(
                text = getString(R.string.app_name),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Text(
                text = if (activeEffect != null) "Wallpaper is active! Customize your experience below." else getString(R.string.status_instruction),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 48.dp)
            )

            if (activeEffect == null) {
                Button(
                    onClick = { startActivity(Intent(this@MainActivity, EffectSelectionActivity::class.java)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Icon(painterResource(id = R.drawable.ic_wallpaper), contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Select Effect & Wallpaper", fontSize = 16.sp)
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            val intent = Intent(this@MainActivity, EffectSelectionActivity::class.java)
                            intent.putExtra("UPDATE_EFFECT_ONLY", true)
                            startActivity(intent)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(painterResource(id = R.drawable.ic_deblur), contentDescription = null)
                            Text("Change Effect")
                        }
                    }
                    FilledTonalButton(
                        onClick = { showImageSelectionDialog() },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(painterResource(id = R.drawable.ic_wallpaper), contentDescription = null)
                            Text("Change Image")
                        }
                    }
                }

                // Settings Section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.DarkGray.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    // Dimness Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Black)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Dimness Level", color = Color.White, fontWeight = FontWeight.Bold)
                            Slider(
                                value = dimLevel,
                                onValueChange = { dimLevel = it },
                                valueRange = 0f..0.8f,
                                modifier = Modifier.padding(vertical = 16.dp)
                            )
                            Button(
                                onClick = { applyDimnessUpdate() },
                                enabled = dimLevel != savedDimLevel,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Update Wallpaper Dimness")
                            }
                        }
                    }

                    // Blur Card (Only visible if FROSTED)
                    if (activeEffect?.contains("FROSTED") == true) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.Black)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Blur Strength", color = Color.White, fontWeight = FontWeight.Bold)
                                Slider(
                                    value = blurStrength,
                                    onValueChange = { blurStrength = it },
                                    valueRange = 0f..400f,
                                    modifier = Modifier.padding(vertical = 16.dp)
                                )
                                Button(
                                    onClick = { applyBlurUpdate() },
                                    enabled = blurStrength != savedBlurStrength,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Update Blur Strength")
                                }
                            }
                        }
                    }

                    // Sync Colors Switch
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Sync System Colors", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(
                                "Updates Material You colors on wallpaper change. Disable if buggy/laggy.",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = syncColors,
                            onCheckedChange = { isChecked ->
                                syncColors = isChecked
                                getSharedPreferences("app_prefs", MODE_PRIVATE).edit {
                                    putBoolean("notify_system_colors", isChecked)
                                }
                                sendConfigUpdate()
                            }
                        )
                    }

                    // Advanced Settings Button
                    Button(
                        onClick = {
                            val intent = Intent(this@MainActivity, AdvancedSettingsActivity::class.java)
                            intent.putExtra("ACTIVE_EFFECT_TYPE", activeEffect ?: "ORIGINAL")
                            intent.putExtra("IS_SAMSUNG", isSamsungDevice())
                            intent.putExtra("IS_PLAYLIST_MODE", isPlaylistModeActive)
                            startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Fine Tune Settings")
                    }
                }
            }
        }
    }

    private fun isSamsungDevice(): Boolean {
        return Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    }

    private fun initializeSmartDefaults() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        if (!prefs.contains("poll_interval")) {
            val isSamsung = isSamsungDevice()
            val defaultPoll = if (isSamsung) 30000L else 50L
            val defaultDelay = if (isSamsung) 0L else 800L
            prefs.edit {
                putLong("poll_interval", defaultPoll)
                putLong("lock_delay", defaultDelay)
            }
        }
    }

    private fun checkWallpaperStatus() {
        val wm = WallpaperManager.getInstance(this)
        val info = wm.wallpaperInfo

        activeEffect = if (info != null && info.packageName == packageName) {
            when (info.component.className) {
                AtmosphereService::class.java.name -> "ORIGINAL"
                BlurToSharpService::class.java.name -> "REVERSE"
                FrostedService::class.java.name -> "FROSTED"
                FrostedReverseService::class.java.name -> "FROSTED_REVERSE"
                HalftoneService::class.java.name -> "HALFTONE"
                HalftoneReverseService::class.java.name -> "HALFTONE_REVERSE"
                ColorFillService::class.java.name -> "COLORFILL"
                ColorFillReverseService::class.java.name -> "COLORFILL_REVERSE"
                else -> null
            }
        } else null

        if (activeEffect != null) {
            val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)

            // Load Dimness
            val defaultDim = if (activeEffect!!.contains("HALFTONE")) 0.0f else 0.2f
            dimLevel = prefs.getFloat("dim_level", defaultDim)
            savedDimLevel = dimLevel

            // Load Blur
            if (activeEffect!!.contains("FROSTED")) {
                blurStrength = prefs.getFloat("frosted_blur_radius", 200f)
                savedBlurStrength = blurStrength
            }

            // Playlist mode calculation
            val playlistDir = File(filesDir, "playlist")
            isPlaylistModeActive = playlistDir.exists() && playlistDir.isDirectory &&
                    (playlistDir.listFiles { _, name -> name.endsWith(".jpg") }?.size ?: 0) > 1

            // Sync colors logic
            val lastMode = prefs.getString("last_known_wallpaper_mode", "UNKNOWN")
            val currentMode = if (isPlaylistModeActive) "PLAYLIST" else "SINGLE"

            if (lastMode != currentMode) {
                val defaultSync = !isPlaylistModeActive
                prefs.edit {
                    putBoolean("notify_system_colors", defaultSync)
                    putString("last_known_wallpaper_mode", currentMode)
                }
                sendConfigUpdate()
            }
            syncColors = prefs.getBoolean("notify_system_colors", !isPlaylistModeActive)
        }
    }

    private fun applyDimnessUpdate() {
        getSharedPreferences("app_prefs", MODE_PRIVATE).edit { putFloat("dim_level", dimLevel) }
        savedDimLevel = dimLevel
        sendConfigUpdate()
        Toast.makeText(this, "Wallpaper Updated!", Toast.LENGTH_SHORT).show()
    }

    private fun applyBlurUpdate() {
        getSharedPreferences("app_prefs", MODE_PRIVATE).edit { putFloat("frosted_blur_radius", blurStrength) }
        savedBlurStrength = blurStrength
        sendConfigUpdate()
        Toast.makeText(this, "Blur Strength Updated!", Toast.LENGTH_SHORT).show()
    }

    private fun sendConfigUpdate() {
        val intent = Intent("com.app.nosatmosphereeffect.UPDATE_CONFIG")
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun showImageSelectionDialog() {
        val options = if (isPlaylistModeActive) {
            arrayOf("Single Image", "Create New Playlist", "Edit Existing Playlist")
        } else {
            arrayOf("Single Image", "Multiple Images (Playlist)")
        }

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
        val uris = ArrayList(files.map { Uri.parse("file://${it.absolutePath}") })

        val intent = Intent(this, PlaylistEditorActivity::class.java).apply {
            putExtra("EDIT_EXISTING", true)
            putExtra("EFFECT_ID", activeEffect ?: "ORIGINAL")
        }
        startActivity(intent)
    }

    private fun launchCropActivity(uri: Uri) {
        val effectId = activeEffect ?: "ORIGINAL"
        val intentClass = if (effectId.contains("REVERSE")) BlurToSharpCropActivity::class.java else CropActivity::class.java
        val intent = Intent(this, intentClass).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("EFFECT_ID", effectId)
        }
        startActivity(intent)
    }

    private fun launchMultiCropActivity(uris: ArrayList<Uri>) {
        val effectId = activeEffect ?: "ORIGINAL"
        val intent = Intent(this, PlaylistEditorActivity::class.java).apply {
            data = uris[0]
            val clipData = android.content.ClipData.newUri(contentResolver, "Images", uris[0])
            for (i in 1 until uris.size) {
                clipData.addItem(android.content.ClipData.Item(uris[i]))
            }
            this.clipData = clipData
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putParcelableArrayListExtra("IMAGE_URIS", uris)
            putExtra("EFFECT_ID", effectId)
        }
        startActivity(intent)
    }
}
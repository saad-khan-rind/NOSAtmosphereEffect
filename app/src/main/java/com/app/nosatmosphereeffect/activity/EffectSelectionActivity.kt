package com.app.nosatmosphereeffect.activity

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.nosatmosphereeffect.helper.EffectItem
import com.app.nosatmosphereeffect.service.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class EffectSelectionActivity : ComponentActivity() {

    private var selectedEffectId: String = "ORIGINAL"
    private var isUpdateOnly = false

    private val effectsList = listOf(
        EffectItem("ORIGINAL", "Original Atmosphere", "Wake up: Sharp ➔ Blur\nSignature style. Drifting ambient atmospheric clouds."),
        EffectItem("REVERSE", "Reverse Atmosphere", "Wake up: Blur ➔ Sharp\nMysterious reveal. Ambient clouds fade to a clear view."),
        EffectItem("FROSTED", "Simple Frosted", "Wake up: Sharp ➔ Blur\nModern minimalism. A clean, uniform frosted glass layer."),
        EffectItem("FROSTED_REVERSE", "Simple Frosted (Reverse)", "Wake up: Blur ➔ Sharp\nElegant clarity. Heavy frost dissolves to crystal clear."),
        EffectItem("HALFTONE", "Halftone Print", "Wake up: Sharp ➔ Halftone\nRetro aesthetic. Sharp view dissolves into comic-book CMYK dots."),
        EffectItem("HALFTONE_REVERSE", "Halftone Print (Reverse)", "Wake up: Halftone ➔ Sharp\nRetro aesthetic. CMYK dots seamlessly expand into continuous color."),
        EffectItem("COLORFILL", "Color Fill", "Wake up: B&W ➔ Color\nLiquid awakening. Colors flow outward from your fingerprint."),
        EffectItem("COLORFILL_REVERSE", "Color Fill (Reverse)", "Wake up: Color ➔ B&W\nFluid drain. Colors wash away into grayscale.")
    )

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
        isUpdateOnly = intent.getBooleanExtra("UPDATE_EFFECT_ONLY", false)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    EffectSelectionScreen(effectsList) { item ->
                        selectedEffectId = item.id
                        if (isUpdateOnly) {
                            applyEffectDirectly(selectedEffectId)
                        } else {
                            showSelectionDialog()
                        }
                    }
                }
            }
        }
    }

    private fun showSelectionDialog() {
        val options = arrayOf("Single Image", "Multiple Images (Playlist)")
        MaterialAlertDialogBuilder(this)
            .setTitle("Select Wallpaper Mode")
            .setItems(options) { _, which ->
                if (which == 0) pickSingleImage.launch("image/*")
                else pickMultipleImages.launch("image/*")
            }
            .show()
    }

    private fun launchCropActivity(uri: Uri) {
        val intentClass = if (selectedEffectId.contains("REVERSE")) BlurToSharpCropActivity::class.java else CropActivity::class.java
        startActivity(Intent(this, intentClass).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("EFFECT_ID", selectedEffectId)
        })
        finish()
    }

    private fun launchMultiCropActivity(uris: ArrayList<Uri>) {
        startActivity(Intent(this, PlaylistEditorActivity::class.java).apply {
            data = uris[0]
            clipData = ClipData.newUri(contentResolver, "Images", uris[0]).apply {
                for (i in 1 until uris.size) addItem(ClipData.Item(uris[i]))
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putParcelableArrayListExtra("IMAGE_URIS", uris)
            putExtra("EFFECT_ID", selectedEffectId)
        })
        finish()
    }

    private fun applyEffectDirectly(effectId: String) {
        val serviceClass = when(effectId) {
            "ORIGINAL" -> AtmosphereService::class.java
            "REVERSE" -> BlurToSharpService::class.java
            "FROSTED" -> FrostedService::class.java
            "FROSTED_REVERSE" -> FrostedReverseService::class.java
            "HALFTONE" -> HalftoneService::class.java
            "HALFTONE_REVERSE" -> HalftoneReverseService::class.java
            "COLORFILL" -> ColorFillService::class.java
            "COLORFILL_REVERSE" -> ColorFillReverseService::class.java
            else -> AtmosphereService::class.java
        }
        startActivity(Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, android.content.ComponentName(this@EffectSelectionActivity, serviceClass))
        })
        finish()
    }
}

// Extracted
@Composable
fun EffectSelectionScreen(effects: List<EffectItem>, onEffectClick: (EffectItem) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Choose Effect",
            style = MaterialTheme.typography.displaySmall,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(effects) { effect ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEffectClick(effect) },
                    colors = CardDefaults.cardColors(containerColor = Color.DarkGray.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = effect.title,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Text(
                            text = effect.description,
                            fontSize = 14.sp,
                            color = Color.LightGray
                        )
                    }
                }
            }
        }
    }
}
package com.app.nosatmosphereeffect.activity

import android.app.WallpaperManager
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.nosatmosphereeffect.service.*
import com.app.nosatmosphereeffect.ui.theme.AtmoTheme
import com.app.nosatmosphereeffect.ui.theme.BrandPrimary
import com.google.android.material.dialog.MaterialAlertDialogBuilder

// ── Data model (previously in helper/EffectItem.kt) ───────────────────────
data class EffectItem(val id: String, val title: String, val description: String)

class EffectSelectionActivity : AppCompatActivity() {

    private var selectedEffectId = "ORIGINAL"

    private val effectsList = listOf(
        EffectItem("ORIGINAL",        "Original Atmosphere",
            "Wake up: Sharp ➔ Blur\nSignature style. Drifting ambient atmospheric clouds."),
        EffectItem("REVERSE",         "Reverse Atmosphere",
            "Wake up: Blur ➔ Sharp\nMysterious reveal. Ambient clouds fade to a clear view."),
        EffectItem("FROSTED",         "Simple Frosted",
            "Wake up: Sharp ➔ Blur\nModern minimalism. A clean, uniform frosted glass layer."),
        EffectItem("FROSTED_REVERSE", "Simple Frosted (Reverse)",
            "Wake up: Blur ➔ Sharp\nElegant clarity. Heavy frost dissolves to crystal clear."),
        EffectItem("HALFTONE",        "Halftone Print",
            "Wake up: Sharp ➔ Halftone\nRetro aesthetic. Sharp view dissolves into comic-book CMYK dots."),
        EffectItem("HALFTONE_REVERSE","Halftone Print (Reverse)",
            "Wake up: Halftone ➔ Sharp\nRetro aesthetic. CMYK dots seamlessly expand into continuous color."),
        EffectItem("COLORFILL",       "Color Fill",
            "Wake up: B&W ➔ Color\nLiquid awakening. Colors flow outward from your fingerprint."),
        EffectItem("COLORFILL_REVERSE","Color Fill (Reverse)",
            "Wake up: Color ➔ B&W\nFluid drain. Colors wash away into grayscale.")
    )

    // ── Image pickers (unchanged) ─────────────────────────────────────────
    private val pickSingleImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { launchCropActivity(it) } }

    private val pickMultipleImages = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> -> if (uris.isNotEmpty()) launchMultiCropActivity(ArrayList(uris)) }

    // ── Lifecycle ─────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isUpdateOnly = intent.getBooleanExtra("UPDATE_EFFECT_ONLY", false)

        setContent {
            AtmoTheme {
                EffectSelectionScreen(isUpdateOnly)
            }
        }
    }

    // ── Composable UI ─────────────────────────────────────────────────────
    @Composable
    private fun EffectSelectionScreen(isUpdateOnly: Boolean) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // Title bar
                Text(
                    text = "Choose Effect",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = BrandPrimary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
                )

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(effectsList) { item ->
                        EffectCard(
                            item = item,
                            isSelected = selectedEffectId == item.id,
                            onClick = {
                                selectedEffectId = item.id
                                if (isUpdateOnly) {
                                    applyEffectDirectly(selectedEffectId)
                                } else {
                                    showSelectionDialog()
                                }
                            }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }
    }

    @Composable
    private fun EffectCard(item: EffectItem, isSelected: Boolean, onClick: () -> Unit) {
        val borderColor = if (isSelected) BrandPrimary else MaterialTheme.colorScheme.outline
        val bgColor     = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        else
            MaterialTheme.colorScheme.surfaceVariant

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() },
            shape  = RoundedCornerShape(16.dp),
            border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor),
            colors = CardDefaults.cardColors(containerColor = bgColor)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text       = item.title,
                    fontSize   = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text     = item.description,
                    fontSize = 13.sp,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }
        }
    }

    // ── Business logic (unchanged) ────────────────────────────────────────

    private fun showSelectionDialog() {
        val options = arrayOf("Single Image", "Multiple Images (Playlist)")
        MaterialAlertDialogBuilder(this)
            .setTitle("Select Wallpaper Mode")
            .setItems(options) { _, which ->
                if (which == 0) pickSingleImage.launch("image/*")
                else            pickMultipleImages.launch("image/*")
            }
            .show()
    }

    private fun launchCropActivity(uri: Uri) {
        val i = if (selectedEffectId.contains("REVERSE"))
            Intent(this, BlurToSharpCropActivity::class.java)
        else
            Intent(this, CropActivity::class.java)
        i.data = uri
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.putExtra("EFFECT_ID", selectedEffectId)
        startActivity(i)
        finish()
    }

    private fun launchMultiCropActivity(uris: ArrayList<Uri>) {
        val i = Intent(this, PlaylistEditorActivity::class.java)
        i.data = uris[0]
        val clip = ClipData.newUri(contentResolver, "Images", uris[0])
        for (idx in 1 until uris.size) clip.addItem(ClipData.Item(uris[idx]))
        i.clipData = clip
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.putParcelableArrayListExtra("IMAGE_URIS", uris)
        i.putExtra("EFFECT_ID", selectedEffectId)
        startActivity(i)
        finish()
    }

    private fun applyEffectDirectly(effectId: String) {
        val serviceClass = when (effectId) {
            "ORIGINAL"         -> AtmosphereService::class.java
            "REVERSE"          -> BlurToSharpService::class.java
            "FROSTED"          -> FrostedService::class.java
            "FROSTED_REVERSE"  -> FrostedReverseService::class.java
            "HALFTONE"         -> HalftoneService::class.java
            "HALFTONE_REVERSE" -> HalftoneReverseService::class.java
            "COLORFILL"        -> ColorFillService::class.java
            "COLORFILL_REVERSE"-> ColorFillReverseService::class.java
            else               -> AtmosphereService::class.java
        }
        val i = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this, serviceClass))
        startActivity(i)
        finish()
    }
}
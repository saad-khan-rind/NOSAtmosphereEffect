package com.app.nosatmosphereeffect.ui.screens

import android.graphics.Bitmap
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Surface
import com.app.nosatmosphereeffect.helper.TouchImageView
import com.app.nosatmosphereeffect.helper.WallpaperFitHelper
import com.app.nosatmosphereeffect.ui.components.AtmoAnimatedIconButton
import com.app.nosatmosphereeffect.ui.components.AtmoDropdownField
import com.app.nosatmosphereeffect.ui.components.AtmoIconMotion
import com.app.nosatmosphereeffect.ui.components.AtmoPrimaryButton
import com.app.nosatmosphereeffect.ui.components.AtmoReveal
import com.app.nosatmosphereeffect.ui.components.SettingSwitchRow

/**
 * Bridges Compose and the proven [TouchImageView] gesture/matrix engine. The
 * activity holds one of these, hands it to [CropScreen], and calls through to
 * the underlying view once its image has been decoded.
 */
class CropController {
    var view: TouchImageView? = null

    fun setInitialImage(bitmap: Bitmap, savedMatrix: FloatArray? = null) {
        view?.setInitialImage(bitmap, savedMatrix)
    }

    fun setFitMode(fit: String, fill: String) {
        view?.setFitMode(fit, fill)
    }

    fun getCroppedBitmap(): Bitmap? = view?.getCroppedBitmap()

    fun getCurrentMatrixValues(): FloatArray? = view?.getCurrentMatrixValues()
}

private val fitOptions = listOf(
    "Screen Fill (Crop)",
    "Fit Image (Show All)",
    "Stretch",
    "Rotate to Fit (Landscape)"
)
private val fitValues = listOf(
    WallpaperFitHelper.MODE_FILL,
    WallpaperFitHelper.MODE_FIT,
    WallpaperFitHelper.MODE_STRETCH,
    WallpaperFitHelper.MODE_ROTATE_FIT
)
private val fillOptions = listOf("Black Bars", "Repeat Pattern", "Mirror Pattern")
private val fillValues = listOf(
    WallpaperFitHelper.FILL_BLACK,
    WallpaperFitHelper.FILL_REPEAT,
    WallpaperFitHelper.FILL_MIRROR
)

@Composable
fun CropScreen(
    controller: CropController,
    buttonLabel: String,
    initialFit: String,
    initialFill: String,
    showAtmosphereGlassOption: Boolean = false,
    atmosphereGlassEnabled: Boolean = false,
    onAtmosphereGlassEnabledChange: (Boolean) -> Unit = {},
    onViewCreated: (TouchImageView) -> Unit,
    onFitChanged: (fit: String, fill: String) -> Unit,
    onBack: () -> Unit,
    onConfirm: () -> Unit
) {
    // Tapping the image toggles the surrounding chrome so the user can see the
    // full frame while positioning it. TouchImageView already distinguishes a
    // tap from a drag/pinch internally (see performClick() in its touch
    // handler), so we just listen for that click rather than adding a second,
    // competing gesture detector on top of it.
    var controlsVisible by remember { mutableStateOf(true) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TouchImageView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    // Seed the mode on the view up-front so the first decode
                    // frames the image with the correct fit (important when
                    // re-editing a playlist image saved as Fit/Rotate).
                    setFitMode(initialFit, initialFill)
                    setOnClickListener { controlsVisible = !controlsVisible }
                }.also { v ->
                    controller.view = v
                    onViewCreated(v)
                }
            }
        )

        // Guide lines stay visible regardless of the chrome toggle below.
        Box(
            Modifier
                .align(Alignment.Center)
                .width(1.dp)
                .fillMaxHeight()
                .background(Color.White.copy(alpha = 0.22f))
        )
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.22f))
        )

        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)
            ) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AtmoAnimatedIconButton(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        onClick = onBack,
                        motion = AtmoIconMotion.BACK
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Frame wallpaper",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Column(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    AtmoReveal(delayMillis = 50) {
                        FitChooserSection(
                            initialFit = initialFit,
                            initialFill = initialFill,
                            onFitChanged = onFitChanged
                        )
                    }
                    if (showAtmosphereGlassOption) {
                        AtmoReveal(delayMillis = 90) {
                            SettingSwitchRow(
                                title = "Add glass effect",
                                subtitle = "Keeps the Atmosphere animation and ends on ribbed glass.",
                                checked = atmosphereGlassEnabled,
                                onCheckedChange = onAtmosphereGlassEnabledChange
                            )
                        }
                    }
                    AtmoReveal(delayMillis = 140) {
                        AtmoPrimaryButton(
                            text = buttonLabel,
                            onClick = onConfirm,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FitChooserSection(
    initialFit: String,
    initialFill: String,
    onFitChanged: (fit: String, fill: String) -> Unit
) {
    var fitIndex by remember {
        mutableIntStateOf(fitValues.indexOf(initialFit).takeIf { it >= 0 } ?: 0)
    }
    var fillIndex by remember {
        mutableIntStateOf(fillValues.indexOf(initialFill).takeIf { it >= 0 } ?: 0)
    }

    val currentFit = fitValues[fitIndex]
    val letterboxed = currentFit == WallpaperFitHelper.MODE_FIT ||
        currentFit == WallpaperFitHelper.MODE_ROTATE_FIT

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AtmoDropdownField(
            label = "Image Fit",
            options = fitOptions,
            selectedIndex = fitIndex,
            onSelected = { idx ->
                fitIndex = idx
                onFitChanged(fitValues[idx], fillValues[fillIndex])
            }
        )
        AnimatedVisibility(
            visible = letterboxed,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            AtmoDropdownField(
                label = "Empty space fill",
                options = fillOptions,
                selectedIndex = fillIndex,
                onSelected = { idx ->
                    fillIndex = idx
                    onFitChanged(fitValues[fitIndex], fillValues[idx])
                }
            )
        }
    }
}

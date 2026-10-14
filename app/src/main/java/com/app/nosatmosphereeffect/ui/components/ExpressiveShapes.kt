package com.app.nosatmosphereeffect.ui.components

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.FormatColorFill
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.ViewWeek
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.app.nosatmosphereeffect.ui.model.EffectCatalog
import com.app.nosatmosphereeffect.ui.theme.AtmoMotion
import com.app.nosatmosphereeffect.ui.theme.LocalAtmoExpressive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.launch

/**
 * The M3 Expressive "cookie": a circle whose rim waves in [lobes] soft bumps.
 * [depth] 0 is a plain circle, so animating it morphs circle <-> cookie.
 */
class ScallopShape(private val lobes: Int = 9, private val depth: Float = 0.1f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = min(size.width, size.height) / 2f
        val cx = size.width / 2f
        val cy = size.height / 2f
        val steps = lobes * 16
        val path = Path()
        for (i in 0..steps) {
            val t = 2.0 * PI * i / steps
            val r = radius * (1f - depth * (1f - cos(lobes * t).toFloat()) / 2f)
            val x = cx + r * cos(t).toFloat()
            val y = cy + r * sin(t).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }
}

/** Pill at rest that squeezes into a squircle while [pressed], on the theme's spring. */
@Composable
fun atmoPressShape(pressed: Boolean, restingFallback: Dp = 18.dp): Shape {
    if (!LocalAtmoExpressive.current) return RoundedCornerShape(restingFallback)
    val percent by animateFloatAsState(
        targetValue = if (pressed) 24f else 50f,
        animationSpec = AtmoMotion.fastSpatial(),
        label = "atmoPressShape"
    )
    return RoundedCornerShape(CornerSize(percent))
}

/**
 * Icon on a shape that comes alive when [active]: the circle blooms into a
 * cookie and slowly turns, while the icon itself stays upright.
 */
@Composable
fun AtmoShapeBadge(
    icon: ImageVector,
    active: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    val expressive = LocalAtmoExpressive.current
    val bloom by animateFloatAsState(
        targetValue = if (active && expressive) 1f else 0f,
        animationSpec = AtmoMotion.defaultSpatial(),
        label = "badgeBloom"
    )
    // Read only inside graphicsLayer, so the endless turn redraws without recomposing.
    val spin = if (active && expressive) {
        rememberInfiniteTransition(label = "badgeSpin").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(16_000, easing = LinearEasing)),
            label = "badgeSpinAngle"
        )
    } else {
        null
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    rotationZ = spin?.value ?: 0f
                    scaleX = 0.92f + 0.08f * bloom
                    scaleY = 0.92f + 0.08f * bloom
                }
                .background(containerColor, if (bloom > 0f) ScallopShape(depth = 0.14f * bloom) else CircleShape)
        )
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(size * 0.46f))
    }
}

/** A dot with a soft ring breathing out of it: the wallpaper is live. */
@Composable
fun LivePulseDot(color: Color, modifier: Modifier = Modifier, dotSize: Dp = 10.dp) {
    // Read only inside drawBehind, so the endless pulse redraws without recomposing.
    val pulse = if (LocalAtmoExpressive.current) {
        rememberInfiniteTransition(label = "livePulse").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(1_600, easing = FastOutSlowInEasing),
                RepeatMode.Restart
            ),
            label = "livePulseProgress"
        )
    } else {
        null
    }
    Box(
        modifier
            .size(dotSize * 2.2f)
            .drawBehind {
                val core = dotSize.toPx() / 2f
                val ring = pulse?.value ?: 0f
                if (ring > 0f) {
                    drawCircle(color.copy(alpha = 0.45f * (1f - ring)), radius = core * (1f + 1.2f * ring))
                }
                drawCircle(color, radius = core)
            }
    )
}

/** One recognisable glyph per effect family, shared by the picker and the home screen. */
fun effectFamilyIcon(effectId: String): ImageVector = when (EffectCatalog.family(effectId)) {
    "GLASS" -> Icons.Rounded.ViewWeek
    "COLORFILL" -> Icons.Rounded.FormatColorFill
    "CANVAS" -> Icons.Rounded.Draw
    "FROSTED" -> Icons.Rounded.AcUnit
    "HALFTONE" -> Icons.Rounded.Grain
    "VHS" -> Icons.Rounded.Videocam
    else -> Icons.Rounded.Cloud
}

/**
 * Predictive back for an in-app full-screen layer: while the back gesture is
 * held the layer shrinks, shifts toward the swipe and rounds its corners,
 * peeking at what lies behind; releasing commits [onBack], cancelling springs back.
 */
@Composable
fun PredictiveBackPeek(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var fromLeft by remember { mutableStateOf(true) }
    val currentOnBack by rememberUpdatedState(onBack)
    val settle = AtmoMotion.defaultSpatial<Float>()
    PredictiveBackHandler(enabled) { events ->
        try {
            events.collect { event ->
                fromLeft = event.swipeEdge == BackEventCompat.EDGE_LEFT
                progress.snapTo(event.progress)
            }
            currentOnBack()
        } catch (cancelled: CancellationException) {
            scope.launch { progress.animateTo(0f, settle) }
            throw cancelled
        }
    }
    Box(
        modifier.graphicsLayer {
            // M3 predictive back: scale to 90%, inset 8dp toward the gesture, round up to 32dp.
            val p = PredictiveBackEasing.transform(progress.value)
            scaleX = 1f - 0.1f * p
            scaleY = 1f - 0.1f * p
            translationX = (if (fromLeft) 1f else -1f) * 8.dp.toPx() * p
            shape = RoundedCornerShape(32.dp * p)
            clip = p > 0f
        }
    ) {
        content()
    }
}

/** M3's predictive back easing: quick to answer the finger, gentle near the end. */
private val PredictiveBackEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

package com.app.nosatmosphereeffect.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.app.nosatmosphereeffect.R
import com.app.nosatmosphereeffect.effect.ShuffleEffects
import com.app.nosatmosphereeffect.renderer.duo.DuoHomeImage
import com.app.nosatmosphereeffect.ui.components.AtmoSettingsGroup
import com.app.nosatmosphereeffect.ui.components.SettingSwitchRow
import com.app.nosatmosphereeffect.ui.model.EffectCatalog

/**
 * Fine tuning's Shuffle group: the effects Shuffle picks from, a switch for
 * each family (ShuffleEffects). Saved as they are switched; the next lock
 * picks from what is on then.
 */
@Composable
internal fun ShuffleSettingsGroup() {
    val context = LocalContext.current
    var excluded by remember { mutableStateOf(ShuffleEffects.excludedFamilies(context)) }
    val families = remember { EffectCatalog.families().filter { it.key != ShuffleEffects.EFFECT_ID } }
    val duoReady = remember { DuoHomeImage.exists(context) }

    AtmoSettingsGroup(title = stringResource(R.string.shuffle_group)) {
        Text(
            stringResource(R.string.shuffle_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(Modifier.height(8.dp))
        families.forEach { family ->
            SettingSwitchRow(
                title = stringResource(family.forward.name),
                checked = family.key !in excluded,
                onCheckedChange = { included ->
                    ShuffleEffects.setFamilyIncluded(context, family.key, included)
                    excluded = ShuffleEffects.excludedFamilies(context)
                },
                subtitle = if (family.key == "DUO" && !duoReady) {
                    stringResource(R.string.shuffle_duo_needs_photo)
                } else {
                    null
                }
            )
            Spacer(Modifier.height(6.dp))
        }
    }
}

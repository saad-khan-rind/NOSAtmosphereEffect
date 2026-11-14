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
import com.app.nosatmosphereeffect.effect.ShuffleDirection
import com.app.nosatmosphereeffect.effect.ShuffleEffects
import com.app.nosatmosphereeffect.ui.components.AtmoDropdownField
import com.app.nosatmosphereeffect.ui.components.AtmoSettingsGroup
import com.app.nosatmosphereeffect.ui.components.SettingSwitchRow

/**
 * Fine tuning's Shuffle group: which way round its effects run, and the
 * effects it picks from, a switch for each family (ShuffleEffects). Saved as
 * they are changed; the next lock picks from what is set then.
 */
@Composable
internal fun ShuffleSettingsGroup() {
    val context = LocalContext.current
    var direction by remember { mutableStateOf(ShuffleEffects.direction(context)) }
    var excluded by remember { mutableStateOf(ShuffleEffects.excludedFamilies(context)) }
    val families = remember { ShuffleEffects.families() }

    AtmoSettingsGroup(title = stringResource(R.string.shuffle_group)) {
        AtmoDropdownField(
            label = stringResource(R.string.effects_direction),
            // In ShuffleDirection's order.
            options = listOf(
                stringResource(R.string.shuffle_direction_photo_first),
                stringResource(R.string.shuffle_direction_effect_first),
                stringResource(R.string.shuffle_direction_random)
            ),
            selectedIndex = direction.ordinal,
            onSelected = { index ->
                direction = ShuffleDirection.entries[index]
                ShuffleEffects.setDirection(context, direction)
            }
        )
        Spacer(Modifier.height(16.dp))
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
                }
            )
            Spacer(Modifier.height(6.dp))
        }
    }
}

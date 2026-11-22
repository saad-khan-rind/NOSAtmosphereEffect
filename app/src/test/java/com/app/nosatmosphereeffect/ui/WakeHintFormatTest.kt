package com.app.nosatmosphereeffect.ui

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Fine tuning formats only Colour Fill's wake hint (with the effect's name)
 * and shows every other hint as it is. Formatting them all once crashed the
 * screen: a percent sign in a hint, or in Play's translation of one
 * ("100 % de"), reads as a placeholder.
 */
class WakeHintFormatTest {

    private val hints = Regex("""<string name="(wake_\w*hint)"[^>]*>(.*?)</string>""")
        .findAll(File("src/main/res/values/strings.xml").readText())
        .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }

    @Test
    fun colourFillsHintFormatsWithTheEffectsName() {
        val text = String.format(hints.getValue("wake_color_fill_hint").replace(Regex("<[^>]+>"), ""), "Colour Fill")
        assertTrue(text.contains("Colour Fill"))
    }

    @Test
    fun noOtherHintHasAPlaceholder() {
        // Shown unformatted, a placeholder would appear as it is written.
        val placeholder = Regex("""%(\d+\$)?[sd]""")
        for ((name, text) in hints - "wake_color_fill_hint") {
            if (placeholder.containsMatchIn(text)) fail("$name has a placeholder but is shown unformatted")
        }
    }
}

package com.app.nosatmosphereeffect.ui

import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Fine tuning shows each wake style's hint formatted with an argument, so a
 * bare % in one (like "100% starts") throws and closes the screen.
 */
class WakeHintFormatTest {

    @Test
    fun everyWakeHintFormats() {
        val strings = File("src/main/res/values/strings.xml").readText()
        val hints = Regex("""<string name="(wake_\w*hint)"[^>]*>(.*?)</string>""").findAll(strings)
        for (hint in hints) {
            val text = hint.groupValues[2].replace("\\'", "'")
            runCatching { String.format(text, "Colour Fill") }
                .onFailure { fail("${hint.groupValues[1]} doesn't format: $it") }
        }
    }
}

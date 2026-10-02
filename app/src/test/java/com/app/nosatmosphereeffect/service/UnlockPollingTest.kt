package com.app.nosatmosphereeffect.service

import org.junit.Assert.assertEquals
import org.junit.Test

class UnlockPollingTest {

    @Test
    fun `polls at the configured rate while an unlock is likely`() {
        assertEquals(50L, UnlockPolling.nextDelayMs(configuredMs = 50L, elapsedMs = 0L))
        assertEquals(50L, UnlockPolling.nextDelayMs(configuredMs = 50L, elapsedMs = 89_999L))
    }

    @Test
    fun `slows down once the lock screen has been sitting there`() {
        assertEquals(
            UnlockPolling.SLOW_INTERVAL_MS,
            UnlockPolling.nextDelayMs(configuredMs = 50L, elapsedMs = 60 * 60 * 1000L)
        )
    }

    @Test
    fun `a slower configured interval is kept as it is`() {
        // Samsung's default: already slow, never sped up.
        assertEquals(30_000L, UnlockPolling.nextDelayMs(configuredMs = 30_000L, elapsedMs = 0L))
        assertEquals(30_000L, UnlockPolling.nextDelayMs(configuredMs = 30_000L, elapsedMs = 100_000L))
    }

    @Test
    fun `a nonsense interval cannot spin`() {
        assertEquals(1L, UnlockPolling.nextDelayMs(configuredMs = 0L, elapsedMs = 0L))
    }
}

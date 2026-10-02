package com.app.nosatmosphereeffect.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentationCrashGuardTest {

    /** Storage that outlives a "process", as SharedPreferences does. */
    private class MemoryStore : CrashGuardCore.Store {
        override var inFlight = false
        override var streak = 0
        override fun clear() {
            inFlight = false
            streak = 0
        }
    }

    @Test
    fun `replacing the wallpaper segments in every renderer at once`() {
        // The live wallpaper, the in-app preview and the clock screen all
        // reload together. Before the fix only the first was allowed; the
        // others were refused as crashes and got no subject.
        val guard = CrashGuardCore(MemoryStore())
        repeat(3) { assertEquals(CrashGuardCore.Outcome.ALLOWED, guard.begin()) }
    }

    @Test
    fun `the crash flag stays up until the last attempt returns`() {
        val store = MemoryStore()
        val guard = CrashGuardCore(store)
        guard.begin()
        guard.begin()
        guard.end()
        assertTrue(store.inFlight)
        guard.end()
        assertFalse(store.inFlight)
    }

    @Test
    fun `a new image after the first one is still segmented`() {
        val guard = CrashGuardCore(MemoryStore())
        assertEquals(CrashGuardCore.Outcome.ALLOWED, guard.begin())
        guard.end()
        assertEquals(CrashGuardCore.Outcome.ALLOWED, guard.begin())
    }

    @Test
    fun `a process that died mid-attempt is caught once, then detection carries on`() {
        val store = MemoryStore()
        CrashGuardCore(store).begin() // the process dies here
        val restarted = CrashGuardCore(store)
        val first = restarted.begin()
        assertTrue(first is CrashGuardCore.Outcome.CrashDetected)
        assertEquals(CrashGuardCore.Outcome.ALLOWED, restarted.begin())
    }

    @Test
    fun `two crashes in a row switch detection off until reset`() {
        val store = MemoryStore()
        CrashGuardCore(store).begin()
        CrashGuardCore(store).also { it.begin() }.begin() // caught, then crashes again
        val third = CrashGuardCore(store)
        val caught = third.begin() as CrashGuardCore.Outcome.CrashDetected
        assertTrue(caught.nowDisabled)
        assertEquals(CrashGuardCore.Outcome.DISABLED, third.begin())
        third.reset()
        assertEquals(CrashGuardCore.Outcome.ALLOWED, third.begin())
    }

    @Test
    fun `an attempt that returns clears the crash streak`() {
        val store = MemoryStore()
        CrashGuardCore(store).begin()
        val restarted = CrashGuardCore(store)
        restarted.begin() // caught: streak 1
        restarted.begin()
        restarted.end()
        assertEquals(0, store.streak)
    }
}

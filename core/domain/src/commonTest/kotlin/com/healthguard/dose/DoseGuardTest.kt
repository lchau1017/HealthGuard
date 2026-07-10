package com.healthguard.dose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class DoseGuardTest {

    private val now = Instant.parse("2024-07-03T10:00:00Z")

    @Test
    fun `no previous take is never a double dose`() {
        assertNull(minutesSinceLastTake(lastTaken = null, now = now))
    }

    @Test
    fun `a take well inside the window is a double dose with its minutes`() {
        assertEquals(15L, minutesSinceLastTake(lastTaken = now - 15.minutes, now = now))
    }

    @Test
    fun `a take one minute inside the window is a double dose`() {
        assertEquals(29L, minutesSinceLastTake(lastTaken = now - 29.minutes, now = now))
    }

    @Test
    fun `a take exactly at the window edge is not a double dose`() {
        // The guard is strict (< window), so the boundary itself is allowed.
        assertNull(minutesSinceLastTake(lastTaken = now - DOUBLE_DOSE_WINDOW, now = now))
    }

    @Test
    fun `a take past the window is not a double dose`() {
        assertNull(minutesSinceLastTake(lastTaken = now - 45.minutes, now = now))
    }

    @Test
    fun `a take moments ago reports zero minutes, not null`() {
        assertEquals(0L, minutesSinceLastTake(lastTaken = now, now = now))
    }
}

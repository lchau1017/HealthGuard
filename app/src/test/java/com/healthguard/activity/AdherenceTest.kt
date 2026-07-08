package com.healthguard.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdherenceTest {

    @Test
    fun `percent is taken over taken plus missed`() {
        assertEquals(94, adherencePercent(taken = 94, missed = 6))
        assertEquals(50, adherencePercent(taken = 1, missed = 1))
        assertEquals(100, adherencePercent(taken = 12, missed = 0))
        assertEquals(0, adherencePercent(taken = 0, missed = 3))
    }

    @Test
    fun `percent rounds to the nearest whole number`() {
        assertEquals(67, adherencePercent(taken = 2, missed = 1))
        assertEquals(33, adherencePercent(taken = 1, missed = 2))
    }

    @Test
    fun `no doses at all yields null`() {
        assertNull(adherencePercent(taken = 0, missed = 0))
    }
}

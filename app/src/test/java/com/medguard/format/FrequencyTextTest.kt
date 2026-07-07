package com.medguard.format

import com.medguard.shared.extraction.Frequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrequencyTextTest {

    @Test
    fun `parses canonical renderings back to typed frequencies`() {
        assertEquals(Frequency.TimesPerDay(1), parseFrequency("once a day"))
        assertEquals(Frequency.TimesPerDay(2), parseFrequency("2 times a day"))
        assertEquals(Frequency.EveryHours(1), parseFrequency("every hour"))
        assertEquals(Frequency.EveryHours(6), parseFrequency("every 6 hours"))
    }

    @Test
    fun `digit runs too large for Int are unparseable rather than a crash`() {
        assertNull(parseFrequency("9999999999 times a day"))
        assertNull(parseFrequency("every 9999999999 hours"))
    }

    @Test
    fun `times per day outside 1 to 24 is rejected`() {
        assertNull(parseFrequency("25 times a day"))
        assertNull(parseFrequency("0 times a day"))
        assertEquals(Frequency.TimesPerDay(24), parseFrequency("24 times a day"))
    }

    @Test
    fun `every hours outside 1 to 744 is rejected`() {
        assertNull(parseFrequency("every 745 hours"))
        assertNull(parseFrequency("every 0 hours"))
        assertEquals(Frequency.EveryHours(744), parseFrequency("every 744 hours"))
    }

    @Test
    fun `unrecognised text is unparseable`() {
        assertNull(parseFrequency("whenever it hurts"))
        assertNull(parseFrequency(""))
    }
}

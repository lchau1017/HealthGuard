package com.healthguard.activity

import com.healthguard.home.MedicationPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class AdherenceRowsTest {

    private fun row(
        phase: MedicationPhase = MedicationPhase.TAKING,
        asNeeded: Boolean = false,
        percent: Int? = null,
        taken: Int = 0,
        skipped: Int = 0,
        meetsTarget: Boolean? = null,
        stoppedText: String? = null,
    ) = MedicationAdherence(
        name = "Cetirizine",
        phase = phase,
        asNeeded = asNeeded,
        percent = percent,
        taken = taken,
        skipped = skipped,
        meetsTarget = meetsTarget,
        stoppedText = stoppedText,
    )

    // --- adherenceRowFigure ---

    @Test
    fun `scheduled taking rows show the percent with skips noted`() {
        assertEquals("84%", adherenceRowFigure(row(percent = 84, taken = 21, meetsTarget = true)))
        assertEquals(
            "84% · 2 skipped",
            adherenceRowFigure(row(percent = 84, taken = 21, skipped = 2, meetsTarget = true)),
        )
    }

    @Test
    fun `as-needed rows show a count never a percent`() {
        assertEquals("As needed · 34 taken", adherenceRowFigure(row(asNeeded = true, taken = 34)))
        assertEquals("As needed · 1 taken", adherenceRowFigure(row(asNeeded = true, taken = 1)))
    }

    @Test
    fun `a taking row without any schedule shows its take count`() {
        assertEquals("3 taken", adherenceRowFigure(row(taken = 3)))
    }

    @Test
    fun `not started rows say so`() {
        assertEquals(
            "Not started",
            adherenceRowFigure(row(phase = MedicationPhase.NOT_STARTED)),
        )
    }

    @Test
    fun `stopped rows name the stop and the while-taking percent when computable`() {
        assertEquals(
            "Stopped 3 Jul · 74% while taking",
            adherenceRowFigure(
                row(
                    phase = MedicationPhase.STOPPED,
                    percent = 74,
                    taken = 20,
                    meetsTarget = false,
                    stoppedText = "Stopped 3 Jul",
                ),
            ),
        )
        assertEquals(
            "Stopped 3 Jul",
            adherenceRowFigure(row(phase = MedicationPhase.STOPPED, stoppedText = "Stopped 3 Jul")),
        )
    }

    // --- adherenceRowDescription ---

    @Test
    fun `descriptions spell out the schedule-relative semantics`() {
        assertEquals(
            "Cetirizine: 84% of its own scheduled doses taken, meets the 80% target",
            adherenceRowDescription(row(percent = 84, taken = 21, meetsTarget = true)),
        )
        assertEquals(
            "Cetirizine: 74% of its own scheduled doses taken, below the 80% target, " +
                "2 skipped by choice",
            adherenceRowDescription(
                row(percent = 74, taken = 21, skipped = 2, meetsTarget = false),
            ),
        )
    }

    @Test
    fun `descriptions cover the phase rows`() {
        assertEquals(
            "Cetirizine: as needed, 34 taken in this window",
            adherenceRowDescription(row(asNeeded = true, taken = 34)),
        )
        assertEquals(
            "Cetirizine: not started yet",
            adherenceRowDescription(row(phase = MedicationPhase.NOT_STARTED)),
        )
        assertEquals(
            "Cetirizine: stopped 3 Jul, 74% of its scheduled doses taken while active",
            adherenceRowDescription(
                row(
                    phase = MedicationPhase.STOPPED,
                    percent = 74,
                    meetsTarget = false,
                    stoppedText = "Stopped 3 Jul",
                ),
            ),
        )
        assertEquals(
            "Cetirizine: stopped 3 Jul",
            adherenceRowDescription(
                row(phase = MedicationPhase.STOPPED, stoppedText = "Stopped 3 Jul"),
            ),
        )
    }
}

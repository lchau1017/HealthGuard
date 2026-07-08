package com.healthguard.activity

import com.healthguard.home.MedicationPhase

/**
 * The trailing figure of an "Adherence by medicine" row, by row type:
 * "84%" / "84% · 2 skipped" for actively scheduled medicines, "As needed ·
 * 34 taken" for interval ones, and "Stopped 3 Jul · 74% while taking"
 * (percent only when the window could compute one) for discontinued ones.
 * Never-started medicines have no row at all — the list only carries
 * medicines with activity in the window. Pure and tested — the percent is
 * the medicine's own schedule completeness, and the wording must never let
 * it read as a share of all doses.
 */
fun adherenceRowFigure(row: MedicationAdherence): String {
    val takenText = if (row.taken == 1) "1 taken" else "${row.taken} taken"
    return when {
        row.phase == MedicationPhase.STOPPED -> listOfNotNull(
            row.stoppedText ?: "Stopped",
            row.percent?.let { "$it% while taking" },
        ).joinToString(" · ")
        row.asNeeded -> "As needed · $takenText"
        row.percent == null -> takenText
        row.skipped > 0 -> "${row.percent}% · ${row.skipped} skipped"
        else -> "${row.percent}%"
    }
}

/**
 * Accessibility text for a row: states what the numbers actually mean
 * (each medicine against its own schedule) instead of leaving a bare
 * percentage to be misread.
 */
fun adherenceRowDescription(row: MedicationAdherence): String = when {
    row.phase == MedicationPhase.STOPPED -> {
        val stopped = (row.stoppedText ?: "Stopped").replaceFirstChar { it.lowercase() }
        val percentPart = row.percent
            ?.let { ", $it% of its scheduled doses taken while active" }
            .orEmpty()
        "${row.name}: $stopped$percentPart"
    }
    row.asNeeded -> {
        val takenText = if (row.taken == 1) "1 taken" else "${row.taken} taken"
        "${row.name}: as needed, $takenText in this window"
    }
    row.percent == null -> {
        val takenText = if (row.taken == 1) "1 taken" else "${row.taken} taken"
        "${row.name}: $takenText in this window"
    }
    else -> {
        val target = if (row.meetsTarget == true) {
            "meets the 80% target"
        } else {
            "below the 80% target"
        }
        val skippedPart = if (row.skipped > 0) ", ${row.skipped} skipped by choice" else ""
        "${row.name}: ${row.percent}% of its own scheduled doses taken, $target$skippedPart"
    }
}

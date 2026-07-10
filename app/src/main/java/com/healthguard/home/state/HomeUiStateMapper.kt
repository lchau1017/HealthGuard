@file:OptIn(ExperimentalTime::class)

package com.healthguard.home.state

import com.healthguard.common.format.phaseChipText
import com.healthguard.home.domain.HomeContent
import com.healthguard.home.format.doseRowStatus
import com.healthguard.home.format.weekCaption
import com.healthguard.home.phase
import kotlin.time.ExperimentalTime
import kotlinx.datetime.TimeZone

/**
 * Folds the pure [HomeContent] into the [HomeUiState], applying this layer's
 * presentation formatters (row title/status, phase chip, week caption, due
 * banner). Every row becomes render-ready view data here — the screen never
 * sees a domain entity. [current] carries the parts the fold preserves — the
 * pending take confirmation.
 */
internal fun HomeContent.toUiState(current: HomeUiState, zone: TimeZone): HomeUiState {
    val cards = taking.map { dc ->
        val medication = dc.item.medication
        DoseCard(
            medicationId = medication.id,
            scheduleId = dc.item.schedule.id,
            title = titleLine(medication.drugName, medication.dosage),
            drugName = medication.drugName,
            categoryLabel = medication.label,
            formLabel = formLabel(medication.form),
            nextDoseAt = dc.nextDoseAt,
            lastTaken = dc.lastTaken,
            isDue = dc.isDue,
            // Same `now` the content was computed against — no formatting drift.
            status = doseRowStatus(dc.nextDoseAt, dc.lastTaken, now, zone, dc.isDue),
        )
    }
    return current.copy(
        now = now,
        taking = cards,
        cabinet = cabinet.map { row ->
            CabinetRow(
                medicationId = row.medication.id,
                title = titleLine(row.medication.drugName, row.medication.dosage),
                drugName = row.medication.drugName,
                categoryLabel = row.medication.label,
                formLabel = formLabel(row.medication.form),
                phaseChipText = phaseChipText(row.schedule, now, zone),
                phase = row.schedule.phase,
            )
        },
        dueCount = dueCount,
        weekDays = weekDays,
        weekCaption = weekCaption(weekDays, todayPending),
        dueAlert = cards.firstOrNull { it.isDue }?.let { DueAlert(it, dueCount - 1) },
        // takeConfirm is preserved by copy().
    )
}

/** The rows' pre-joined primary line: "Ibuprofen 200 mg". */
private fun titleLine(drugName: String, dosage: String?): String =
    listOfNotNull(drugName, dosage).joinToString(" ")

/** The rows' pre-capitalised form text: "Tablet"; null = none. */
private fun formLabel(form: String?): String? =
    form?.replaceFirstChar { it.uppercase() }

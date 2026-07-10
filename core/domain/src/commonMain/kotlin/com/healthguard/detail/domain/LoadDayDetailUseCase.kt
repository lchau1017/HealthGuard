@file:OptIn(ExperimentalTime::class)

package com.healthguard.detail.domain

import com.healthguard.activity.DayDetail
import com.healthguard.activity.dayDetail
import com.healthguard.detail.SLOT_MATCH_WINDOW
import com.healthguard.domain.repository.DoseLogRepository
import com.healthguard.domain.model.MedicationWithSchedule
import com.healthguard.domain.schedule.expectedDoseTimes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus

/**
 * Loads the tapped heat-map day's detail sheet, scoped to one medication — the
 * per-med grid answers for a single schedule. Ports `DetailViewModel.selectDay`:
 * that day's logs for this medication plus the schedule's expected slots (slots
 * still inside the 90-minute answer window are not "not recorded" yet).
 */
class LoadDayDetailUseCase(
    private val repository: DoseLogRepository,
    private val clock: () -> Instant,
    private val zone: TimeZone,
) {
    suspend operator fun invoke(item: MedicationWithSchedule, date: LocalDate): DayDetail {
        val now = clock()
        val dayStart = date.atStartOfDayIn(zone)
        val dayEnd = date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
        val logs = repository.doseLogsWithMedicationInRange(dayStart, dayEnd)
            .filter { it.medicationId == item.medication.id }
        val expected = expectedDoseTimes(
            item.schedule,
            dayStart,
            minOf(dayEnd, now - SLOT_MATCH_WINDOW),
            zone,
        )
        val expectedByMedication = if (expected.isEmpty()) {
            emptyMap()
        } else {
            mapOf(item.medication.id to expected)
        }
        return dayDetail(date, logs, expectedByMedication, zone)
    }
}

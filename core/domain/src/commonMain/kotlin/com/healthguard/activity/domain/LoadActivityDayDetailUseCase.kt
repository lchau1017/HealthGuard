@file:OptIn(ExperimentalTime::class)

package com.healthguard.activity.domain

import com.healthguard.activity.DayDetail
import com.healthguard.activity.dayDetail
import com.healthguard.detail.SLOT_MATCH_WINDOW
import com.healthguard.domain.repository.DoseLogRepository
import com.healthguard.domain.repository.MedicationRepository
import com.healthguard.domain.schedule.expectedDoseTimes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus

/**
 * Loads the tapped grid day's detail sheet across ALL medications — the
 * dashboard grid is count-based raw activity over every schedule, so its day
 * sheet answers for every medicine at once (unlike Detail's single-medication
 * [com.healthguard.detail.domain.LoadDayDetailUseCase]). Ports
 * `ActivityViewModel.selectDay`: that day's logs grouped per medicine plus what
 * each in-treatment schedule expected of it (slots still inside the 90-minute
 * answer window are not "not recorded" yet).
 */
class LoadActivityDayDetailUseCase(
    private val medicationRepository: MedicationRepository,
    private val doseLogRepository: DoseLogRepository,
    private val clock: () -> Instant,
    private val zone: TimeZone,
) {
    suspend operator fun invoke(date: LocalDate): DayDetail {
        val now = clock()
        val dayStart = date.atStartOfDayIn(zone)
        val dayEnd = date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
        val logs = doseLogRepository.doseLogsWithMedicationInRange(dayStart, dayEnd)
        val expectedByMedication = medicationRepository.medications().first()
            .associate { row ->
                row.medication.id to expectedDoseTimes(
                    row.schedule,
                    dayStart,
                    minOf(dayEnd, now - SLOT_MATCH_WINDOW),
                    zone,
                )
            }
            .filterValues { it.isNotEmpty() }
        return dayDetail(date, logs, expectedByMedication, zone)
    }
}

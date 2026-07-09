@file:OptIn(ExperimentalTime::class)

package com.healthguard.home.domain

import com.healthguard.shared.data.DoseStatus
import com.healthguard.shared.data.MedicationRepository
import com.healthguard.shared.data.StoredDoseLog
import com.healthguard.shared.data.StoredMedication
import com.healthguard.shared.data.StoredSchedule
import com.healthguard.shared.extraction.Frequency
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** Ids of the demo medications the seeder writes (and [RemoveDemoDataUseCase] clears). */
val demoMedicationIds =
    listOf("demo-med-1", "demo-med-2", "demo-med-3", "demo-med-4", "demo-med-5")

private const val HISTORY_DAYS = 70
private const val SKIP_DAY_CHANCE = 0.15
private const val RECENT_MISS_CHANCE = 0.08

/**
 * Debug-only demo content: a few realistic medications plus ~10 weeks of dose
 * history so the activity heat map, stats, and detail history have something
 * to show on a fresh install. Deterministic (fixed [Random] seed) and
 * idempotent (known ids; seeding twice is a no-op). Never wired into release
 * UI.
 *
 * Seeds demo data; returns false (no-op) when it is already present.
 */
class SeedDemoDataUseCase(
    private val repository: MedicationRepository,
    private val clock: () -> Instant,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) {
    suspend operator fun invoke(): Boolean {
        val now = clock()
        if (repository.getMedication(demoMedicationIds.first()) != null) return false

        val random = Random(42)
        val today = now.toLocalDateTime(zone).date
        val historyStart = today.minus(HISTORY_DAYS, DateTimeUnit.DAY)
        val longStart = historyStart.atTime(LocalTime(8, 0)).toInstant(zone)
        val recentStart = today.minus(10, DateTimeUnit.DAY).atTime(LocalTime(8, 0)).toInstant(zone)

        data class Demo(
            val medId: String,
            val schedId: String,
            val name: String,
            val dosage: String,
            val label: String,
            val ingredients: List<String>,
            val frequency: Frequency,
            val slots: List<LocalTime>,
            val startedAt: Instant?,
            val stoppedAt: Instant? = null,
        )

        val demos = listOf(
            Demo(
                "demo-med-1", "demo-sch-1", "Vitamin D3", "1000 IU", "Supplement",
                listOf("colecalciferol"), Frequency.TimesPerDay(1),
                listOf(LocalTime(9, 0)), longStart,
            ),
            Demo(
                "demo-med-2", "demo-sch-2", "Cetirizine", "10 mg", "Allergy",
                listOf("cetirizine hydrochloride"), Frequency.TimesPerDay(2),
                // Matches the 2x/day meal-aligned anchors (09:00, 21:00) so
                // seeded history lines up with computed next-dose slots.
                listOf(LocalTime(9, 0), LocalTime(21, 0)), longStart,
            ),
            Demo(
                "demo-med-3", "demo-sch-3", "Ibuprofen", "200 mg", "Pain relief",
                listOf("ibuprofen"), Frequency.EveryHours(6),
                listOf(LocalTime(8, 0), LocalTime(14, 0), LocalTime(20, 0)), recentStart,
            ),
            Demo(
                "demo-med-4", "demo-sch-4", "Amoxicillin", "500 mg", "Other",
                listOf("amoxicillin trihydrate"), Frequency.TimesPerDay(3),
                emptyList(), null,
            ),
            // Started 8 weeks ago, stopped 2 weeks ago: exercises the
            // "Stopped" phase chip and the out-of-treatment trailing blanks
            // on its heat map.
            Demo(
                "demo-med-5", "demo-sch-5", "Loratadine", "10 mg", "Allergy",
                listOf("loratadine"), Frequency.TimesPerDay(1),
                listOf(LocalTime(9, 0)),
                today.minus(56, DateTimeUnit.DAY).atTime(LocalTime(8, 0)).toInstant(zone),
                today.minus(14, DateTimeUnit.DAY).atTime(LocalTime(12, 0)).toInstant(zone),
            ),
        )

        // One transaction for the whole seed: observers see a single state
        // change, never a moment where medications exist without their
        // history (which used to flash a bogus due alert mid-seed).
        repository.batch {
        demos.forEach { demo ->
            insertMedication(
                StoredMedication(
                    id = demo.medId,
                    drugName = demo.name,
                    label = demo.label,
                    activeIngredients = demo.ingredients,
                    dosage = demo.dosage,
                    form = "tablet",
                    extractionConfidence = 0.95,
                    createdAt = demo.startedAt ?: now,
                ),
                StoredSchedule(
                    id = demo.schedId,
                    medicationId = demo.medId,
                    frequency = demo.frequency,
                    withFood = demo.medId == "demo-med-3",
                    startedAt = null,
                    stoppedAt = null,
                ),
            )
            demo.startedAt?.let { activate(demo.medId, it) }
            demo.stoppedAt?.let { stop(demo.medId, it) }
        }

        var doseCounter = 0
        demos.filter { it.startedAt != null }.forEach { demo ->
            val firstDay = demo.startedAt!!.toLocalDateTime(zone).date
            var day: LocalDate = firstDay
            while (day <= today) {
                val daysFromToday = today.toEpochDays() - day.toEpochDays()
                // A whole deliberately skipped day five days ago (both of
                // Cetirizine's doses logged SKIPPED): the dash "skipped by
                // choice" state is always demonstrable, whatever the RNG does.
                val forcedSkipDay = demo.medId == "demo-med-2" && daysFromToday == 5L
                // Guaranteed streak: the last 4 days are never silent.
                val skipDay = !forcedSkipDay && daysFromToday > 4 &&
                    random.nextDouble() < SKIP_DAY_CHANCE
                if (!skipDay) {
                    demo.slots.forEach { slot ->
                        // One deliberate gap two days ago (Cetirizine's evening
                        // dose is never logged): the week circles show a
                        // non-full day and the detail history shows a
                        // "Not recorded" row, whatever the RNG does.
                        if (demo.medId == "demo-med-2" && daysFromToday == 2L && slot.hour == 21) {
                            return@forEach
                        }
                        val jitter = random.nextInt(0, 20)
                        val planned = day
                            .atTime(LocalTime(slot.hour, slot.minute))
                            .toInstant(zone)
                        val withinTreatment =
                            demo.stoppedAt == null || planned < demo.stoppedAt
                        if (planned <= now && withinTreatment) {
                            val status = if (forcedSkipDay) {
                                DoseStatus.SKIPPED
                            } else {
                                val recentMiss = daysFromToday in 1..14 &&
                                    random.nextDouble() < RECENT_MISS_CHANCE
                                if (recentMiss) {
                                    if (random.nextBoolean()) DoseStatus.MISSED else DoseStatus.SKIPPED
                                } else {
                                    DoseStatus.TAKEN
                                }
                            }
                            logDose(
                                StoredDoseLog(
                                    id = "demo-dose-${doseCounter++}",
                                    scheduleId = demo.schedId,
                                    plannedAt = planned,
                                    takenAt = if (status == DoseStatus.TAKEN) {
                                        planned + jitter.minutes
                                    } else {
                                        null
                                    },
                                    status = status,
                                ),
                            )
                        }
                    }
                }
                day = LocalDate.fromEpochDays(day.toEpochDays() + 1)
            }
        }
        }
        return true
    }
}

/** Removes every demo medication (and its schedule/history) seeded by [SeedDemoDataUseCase]. */
class RemoveDemoDataUseCase(
    private val repository: MedicationRepository,
) {
    suspend operator fun invoke() {
        demoMedicationIds.forEach { repository.delete(it) }
    }
}

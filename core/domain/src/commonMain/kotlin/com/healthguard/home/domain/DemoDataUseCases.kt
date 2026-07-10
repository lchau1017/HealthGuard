package com.healthguard.home.domain

import com.healthguard.domain.model.DoseId
import com.healthguard.domain.model.DoseStatus
import com.healthguard.domain.model.MedicationId
import com.healthguard.domain.model.ScheduleId
import com.healthguard.domain.repository.MedicationRepository
import com.healthguard.domain.model.StoredDoseLog
import com.healthguard.domain.model.StoredMedication
import com.healthguard.domain.model.StoredSchedule
import com.healthguard.domain.extraction.Frequency
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

private const val HISTORY_DAYS = 70
private const val SKIP_DAY_CHANCE = 0.15
private const val RECENT_MISS_CHANCE = 0.08

/** Treatments begin on a morning routine. */
private val DEMO_START_TIME = LocalTime(8, 0)

/** The stopped demo treatment ends mid-day. */
private val DEMO_STOP_TIME = LocalTime(12, 0)

/**
 * One demo medication as the catalog declares it: identity, dosing, and the
 * treatment stretch as day offsets from "today" (resolved against the
 * caller's clock and zone at seed time, so the dataset always sits at the
 * same relative distance from now).
 */
private data class Demo(
    val medId: MedicationId,
    val schedId: ScheduleId,
    val name: String,
    val dosage: String,
    val label: String,
    val ingredients: List<String>,
    val frequency: Frequency,
    val slots: List<LocalTime>,
    /** Days before today the treatment started; null = never started. */
    val startedDaysAgo: Int?,
    /** Days before today the treatment stopped; null = still running. */
    val stoppedDaysAgo: Int? = null,
) {
    fun startedAt(today: LocalDate, zone: TimeZone): Instant? = startedDaysAgo?.let {
        today.minus(it, DateTimeUnit.DAY).atTime(DEMO_START_TIME).toInstant(zone)
    }

    fun stoppedAt(today: LocalDate, zone: TimeZone): Instant? = stoppedDaysAgo?.let {
        today.minus(it, DateTimeUnit.DAY).atTime(DEMO_STOP_TIME).toInstant(zone)
    }
}

/** The one place the demo dataset is declared; ids and history derive from it. */
private val demoCatalog = listOf(
    Demo(
        MedicationId("demo-med-1"), ScheduleId("demo-sch-1"), "Vitamin D3", "1000 IU", "Supplement",
        listOf("colecalciferol"), Frequency.TimesPerDay(1),
        listOf(LocalTime(9, 0)), startedDaysAgo = HISTORY_DAYS,
    ),
    Demo(
        MedicationId("demo-med-2"), ScheduleId("demo-sch-2"), "Cetirizine", "10 mg", "Allergy",
        listOf("cetirizine hydrochloride"), Frequency.TimesPerDay(2),
        // Matches the 2x/day meal-aligned anchors (09:00, 21:00) so
        // seeded history lines up with computed next-dose slots.
        listOf(LocalTime(9, 0), LocalTime(21, 0)), startedDaysAgo = HISTORY_DAYS,
    ),
    Demo(
        MedicationId("demo-med-3"), ScheduleId("demo-sch-3"), "Ibuprofen", "200 mg", "Pain relief",
        listOf("ibuprofen"), Frequency.EveryHours(6),
        listOf(LocalTime(8, 0), LocalTime(14, 0), LocalTime(20, 0)), startedDaysAgo = 10,
    ),
    Demo(
        MedicationId("demo-med-4"), ScheduleId("demo-sch-4"), "Amoxicillin", "500 mg", "Other",
        listOf("amoxicillin trihydrate"), Frequency.TimesPerDay(3),
        emptyList(), startedDaysAgo = null,
    ),
    // Started 8 weeks ago, stopped 2 weeks ago: exercises the "Stopped"
    // phase chip and the out-of-treatment trailing blanks on its heat map.
    Demo(
        MedicationId("demo-med-5"), ScheduleId("demo-sch-5"), "Loratadine", "10 mg", "Allergy",
        listOf("loratadine"), Frequency.TimesPerDay(1),
        listOf(LocalTime(9, 0)), startedDaysAgo = 56, stoppedDaysAgo = 14,
    ),
)

/** Ids of the demo medications the seeder writes (and [RemoveDemoDataUseCase] clears). */
val demoMedicationIds: List<MedicationId> = demoCatalog.map { it.medId }

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
    private val zone: TimeZone,
) {
    suspend operator fun invoke(): Boolean {
        val now = clock()
        if (repository.getMedication(demoMedicationIds.first()) != null) return false

        val today = now.toLocalDateTime(zone).date

        // One transaction for the whole seed: observers see a single state
        // change, never a moment where medications exist without their
        // history (which used to flash a bogus due alert mid-seed).
        repository.batch {
            demoCatalog.forEach { demo ->
                val startedAt = demo.startedAt(today, zone)
                insertMedication(
                    StoredMedication(
                        id = demo.medId,
                        drugName = demo.name,
                        label = demo.label,
                        activeIngredients = demo.ingredients,
                        dosage = demo.dosage,
                        form = "tablet",
                        extractionConfidence = 0.95,
                        createdAt = startedAt ?: now,
                    ),
                    StoredSchedule(
                        id = demo.schedId,
                        medicationId = demo.medId,
                        frequency = demo.frequency,
                        withFood = demo.medId == MedicationId("demo-med-3"),
                        startedAt = null,
                        stoppedAt = null,
                    ),
                )
                startedAt?.let { activate(demo.medId, it) }
                demo.stoppedAt(today, zone)?.let { stop(demo.medId, it) }
            }
            seedHistory(today, now, zone)
        }
        return true
    }
}

/**
 * Writes every started demo's dose history up to [now]: mostly TAKEN with a
 * fixed-seed sprinkle of skipped days and recent misses, plus two forced
 * Cetirizine features (a fully skipped day five days ago, a silent evening
 * slot two days ago) so the dash and "Not recorded" states are always
 * demonstrable, whatever the RNG does.
 */
private fun MedicationRepository.BatchWriter.seedHistory(
    today: LocalDate,
    now: Instant,
    zone: TimeZone,
) {
    val random = Random(42)
    var doseCounter = 0
    demoCatalog.filter { it.startedDaysAgo != null }.forEach { demo ->
        val startedAt = demo.startedAt(today, zone)!!
        val stoppedAt = demo.stoppedAt(today, zone)
        var day: LocalDate = startedAt.toLocalDateTime(zone).date
        while (day <= today) {
            val daysFromToday = today.toEpochDays() - day.toEpochDays()
            // A whole deliberately skipped day five days ago (both of
            // Cetirizine's doses logged SKIPPED): the dash "skipped by
            // choice" state is always demonstrable, whatever the RNG does.
            val forcedSkipDay = demo.medId == MedicationId("demo-med-2") && daysFromToday == 5L
            // Guaranteed streak: the last 4 days are never silent.
            val skipDay = !forcedSkipDay && daysFromToday > 4 &&
                random.nextDouble() < SKIP_DAY_CHANCE
            if (!skipDay) {
                demo.slots.forEach { slot ->
                    // One deliberate gap two days ago (Cetirizine's evening
                    // dose is never logged): the week circles show a
                    // non-full day and the detail history shows a
                    // "Not recorded" row, whatever the RNG does.
                    if (demo.medId == MedicationId("demo-med-2") && daysFromToday == 2L && slot.hour == 21) {
                        return@forEach
                    }
                    val jitter = random.nextInt(0, 20)
                    val planned = day
                        .atTime(LocalTime(slot.hour, slot.minute))
                        .toInstant(zone)
                    val withinTreatment = stoppedAt == null || planned < stoppedAt
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
                                id = DoseId("demo-dose-${doseCounter++}"),
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

/** Removes every demo medication (and its schedule/history) seeded by [SeedDemoDataUseCase]. */
class RemoveDemoDataUseCase(
    private val repository: MedicationRepository,
) {
    suspend operator fun invoke() {
        demoMedicationIds.forEach { repository.delete(it) }
    }
}

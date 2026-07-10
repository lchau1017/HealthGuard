package com.healthguard.domain.model

import kotlin.jvm.JvmInline

/**
 * Typed identity wrappers for the three persisted entities. Each is a zero-cost
 * `value class` over the stored TEXT id, so `activate(medicationId)` can never
 * be handed a schedule id and compile — parameter naming alone used to be the
 * only guard. The raw [value] appears only at true String boundaries: SQL
 * columns, `rememberSaveable` storage, and synthetic view keys.
 */
@JvmInline
value class MedicationId(val value: String)

/** See [MedicationId]. */
@JvmInline
value class ScheduleId(val value: String)

/** See [MedicationId]. */
@JvmInline
value class DoseId(val value: String)

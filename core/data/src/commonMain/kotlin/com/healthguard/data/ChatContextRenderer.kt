package com.healthguard.data

import com.healthguard.chat.ChatContext
import com.healthguard.domain.extraction.Frequency
import com.healthguard.home.MedicationPhase
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Renders a [ChatContext] into the plain-text block the /chat proxy splices
 * into the model prompt. Deterministic — the same context always yields the
 * same text — so the wire format is testable and the numbers the model sees
 * are exactly the domain-computed ones.
 */
class ChatContextRenderer(private val zone: TimeZone) {

    fun render(context: ChatContext): String = buildString {
        append("Snapshot: ${context.generatedAt.render()}")
        append("\n\nMedications:")
        if (context.medications.isEmpty()) {
            append("\nNo medications are tracked yet.")
        } else {
            context.medications.forEach { append("\n${it.line()}") }
        }
        if (context.weeks.isNotEmpty()) {
            append("\n\nWeekly totals (weeks start Monday):")
            context.weeks.forEach { week ->
                append(
                    "\n- ${week.weekStart}: ${week.expected} expected, ${week.taken} taken, " +
                        "${week.missed} missed, ${week.skipped} skipped",
                )
            }
        }
        if (context.events.isNotEmpty()) {
            append("\n\nRecorded doses:")
            context.events
                .groupBy { it.date }
                .forEach { (date, events) ->
                    val takes = events.joinToString { "${it.drugName} ${it.status.name.lowercase()}" }
                    append("\n- ${date.renderWithDay()}: $takes")
                }
        }
    }

    private fun ChatContext.MedicationFacts.line(): String = buildString {
        append("- $name")
        dosage?.let { append(" ($it)") }
        append(" — ${frequency.text()} — ${phase.text()} — ")
        val percent = adherence.percent
        if (percent != null) {
            append(
                "$percent% adherence over last 30 days " +
                    "(${adherence.taken} taken / ${adherence.expected} expected, " +
                    "${adherence.skipped} skipped)",
            )
        } else {
            append("${adherence.taken} taken over last 30 days (no scheduled expectation)")
        }
        nextDoseAt?.let { append(" — next dose ${it.render()}") }
    }

    private fun Frequency?.text(): String = when (this) {
        is Frequency.TimesPerDay -> "${count}x per day"
        is Frequency.EveryHours -> "as needed (max every ${hours}h)"
        null -> "no schedule"
    }

    private fun MedicationPhase.text(): String = when (this) {
        MedicationPhase.TAKING -> "currently taking"
        MedicationPhase.NOT_STARTED -> "not started"
        MedicationPhase.STOPPED -> "stopped"
    }

    /** `2025-06-18 12:00 (Wednesday)` — a weekday the model need not derive itself. */
    private fun Instant.render(): String {
        val dateTime = toLocalDateTime(zone)
        val hour = dateTime.hour.toString().padStart(2, '0')
        val minute = dateTime.minute.toString().padStart(2, '0')
        return "${dateTime.date} $hour:$minute (${dateTime.date.dayOfWeek.label()})"
    }

    private fun LocalDate.renderWithDay(): String = "$this (${dayOfWeek.label()})"

    private fun DayOfWeek.label(): String =
        name.lowercase().replaceFirstChar { it.titlecase() }
}

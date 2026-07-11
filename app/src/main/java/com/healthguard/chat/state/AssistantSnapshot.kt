@file:OptIn(ExperimentalTime::class)

package com.healthguard.chat.state

import com.healthguard.home.domain.HomeContent
import com.healthguard.home.format.DoseRowStatus
import com.healthguard.home.format.doseRowStatus
import com.healthguard.home.format.weekCaption
import kotlin.time.ExperimentalTime
import kotlinx.datetime.TimeZone

/** The assistant landing card: today's status at a glance, tappable through to Home. */
data class AssistantSnapshot(
    /** "2 doses due now" / "Aspirin — next at 9:00 PM" / "All caught up". */
    val headline: String,
    /** The week line under it, same wording as Home's week card. */
    val caption: String?,
)

/**
 * Folds [HomeContent] into the assistant's snapshot card, reusing Home's
 * formatters so the two screens can never disagree. Null when nothing is
 * tracked at all — the scan card carries the empty state instead.
 */
fun HomeContent.toAssistantSnapshot(zone: TimeZone): AssistantSnapshot? {
    if (taking.isEmpty() && cabinet.isEmpty()) return null
    val next = taking.firstOrNull()
    val headline = when {
        dueCount == 1 -> "1 dose due now"
        dueCount > 1 -> "$dueCount doses due now"
        next != null -> {
            val status = doseRowStatus(next.nextDoseAt, next.lastTaken, now, zone, next.isDue)
            when (status) {
                // "Next at 9:00 PM" -> "Aspirin — next at 9:00 PM"
                is DoseRowStatus.Next ->
                    "${next.item.medication.drugName} — ${status.text.replaceFirstChar { it.lowercase() }}"
                else -> "All caught up"
            }
        }
        else -> "All caught up"
    }
    return AssistantSnapshot(
        headline = headline,
        caption = weekCaption(weekDays, todayPending),
    )
}

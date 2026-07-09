@file:OptIn(ExperimentalTime::class)

package com.healthguard.activity

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * How many activity events fell on one local day. Tracker-neutral: the same
 * shape backs medication doses today and any future tracker (runs, meals).
 */
data class DayCount(val date: LocalDate, val count: Int)

/**
 * Buckets event instants into per-day counts using [zone]'s day boundaries,
 * ascending by date. Days without events are absent (not zero entries).
 */
fun dayCounts(events: List<Instant>, zone: TimeZone): List<DayCount> =
    events.groupingBy { it.toLocalDateTime(zone).date }
        .eachCount()
        .map { (date, count) -> DayCount(date, count) }
        .sortedBy { it.date }

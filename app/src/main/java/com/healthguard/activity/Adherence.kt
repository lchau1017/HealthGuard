package com.healthguard.activity

import kotlin.math.roundToInt

/**
 * Adherence as a whole percentage: TAKEN / (TAKEN + MISSED), rounded.
 * Skipped doses are excluded from both sides — a deliberate skip is not a
 * lapse. Null when there is nothing to measure (no takes and no misses),
 * so callers can omit the figure entirely.
 */
fun adherencePercent(taken: Int, missed: Int): Int? {
    val total = taken + missed
    if (total == 0) return null
    return (taken * 100.0 / total).roundToInt()
}

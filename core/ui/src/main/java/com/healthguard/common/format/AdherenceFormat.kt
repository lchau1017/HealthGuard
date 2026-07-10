package com.healthguard.common.format

import com.healthguard.activity.AdherenceResult

/**
 * The quiet adherence-target caption under a percent figure — "Meets 80%
 * target" / "Below 80% target" — shared by the detail history and the
 * Activity breakdown so the wording (and the number itself) always tracks
 * [AdherenceResult.TARGET_PERCENT].
 */
fun targetCaption(meetsTarget: Boolean): String =
    if (meetsTarget) {
        "Meets ${AdherenceResult.TARGET_PERCENT}% target"
    } else {
        "Below ${AdherenceResult.TARGET_PERCENT}% target"
    }

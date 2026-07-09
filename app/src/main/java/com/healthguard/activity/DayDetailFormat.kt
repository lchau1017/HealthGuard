package com.healthguard.activity

import com.healthguard.format.timeLabel

/** "Cetirizine 10 mg — 2 taken (9:04 AM · 9:12 PM)"; no times when none. */
fun dayLineTitle(line: DayMedicineLine): String {
    val count = line.takenTimes.size
    val times = if (count == 0) {
        ""
    } else {
        " (${line.takenTimes.joinToString(" · ") { timeLabel(it) }})"
    }
    return "${line.name} — $count taken$times"
}

/** The muted lines under a title: "1 skipped", "2 missed", "1 not recorded". */
fun dayLineAnnotations(line: DayMedicineLine): List<String> = buildList {
    if (line.skipped > 0) add("${line.skipped} skipped")
    if (line.missed > 0) add("${line.missed} missed")
    if (line.notRecorded > 0) add("${line.notRecorded} not recorded")
}

/** The aggregate line for expected doses of medicines with no record at all. */
fun expectedNotRecordedText(count: Int): String = "$count expected but not recorded"

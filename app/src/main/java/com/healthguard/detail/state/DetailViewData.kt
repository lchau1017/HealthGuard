package com.healthguard.detail.state

/**
 * What a history row is, driving the row's leading glyph and annotation
 * emphasis: a take (filled check), a logged miss (error-tinted annotation),
 * any other logged answer (skipped/pending), or a derived "Not recorded" gap.
 */
enum class HistoryRowKind { TAKEN, MISSED, LOGGED, NOT_RECORDED }

/** One render-ready row of the detail history list — no domain entities. */
data class HistoryRowData(
    /** Log id, or a synthetic slot key for derived "Not recorded" rows. */
    val id: String,
    /** Pre-formatted primary line: "Today, 8:02 AM". */
    val title: String,
    /** Pre-formatted annotation: "Taken · on time" / "Missed" / "Not recorded". */
    val annotation: String,
    val kind: HistoryRowKind,
)

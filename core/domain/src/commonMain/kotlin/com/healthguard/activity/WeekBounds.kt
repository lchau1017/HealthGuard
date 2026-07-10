package com.healthguard.activity

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus

/** Days in a calendar week — the app's week windows and grids all count 7. */
const val DAYS_PER_WEEK = 7

/** The Monday on or before [date]. */
fun mondayOf(date: LocalDate): LocalDate =
    date.minus(date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)

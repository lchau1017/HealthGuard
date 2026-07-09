package com.healthguard.confirm

/** Maps a yes/no review answer back to a typed value; null when unclear. */
fun parseWithFood(text: String): Boolean? = when (text.trim().lowercase()) {
    "yes", "y", "true" -> true
    "no", "n", "false" -> false
    else -> null
}

package com.healthguard.common.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** Content-description shorthand: every actionable element must announce itself. */
fun Modifier.semanticsLabel(label: String): Modifier =
    semantics { contentDescription = label }

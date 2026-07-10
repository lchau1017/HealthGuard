package com.healthguard.confirm.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.healthguard.common.theme.HealthGuardTheme
import com.healthguard.confirm.state.ConfirmUiState
import com.healthguard.confirm.state.ReviewField
import com.healthguard.confirm.state.ReviewFieldKey
import com.healthguard.domain.extraction.Frequency

/*
 * Design-time previews for the import/confirm dialog. Sample data is fully
 * fixed so renders are reproducible. The review state gets a light and a
 * dark variant; the error state documents the retriable failure layout.
 */

private val reviewState = ConfirmUiState.Review(
    fields = listOf(
        ReviewField(
            key = ReviewFieldKey.DRUG_NAME,
            label = "Name",
            value = "Amoxicillin",
            confidence = 0.98,
            needsReview = false,
        ),
        ReviewField(
            key = ReviewFieldKey.DOSAGE,
            label = "Dosage",
            value = "500 mg",
            confidence = 0.95,
            needsReview = false,
        ),
        ReviewField(
            key = ReviewFieldKey.FORM,
            label = "Form",
            value = "Capsule",
            confidence = 0.91,
            needsReview = false,
        ),
        ReviewField(
            key = ReviewFieldKey.FREQUENCY,
            label = "Frequency",
            value = "3 times a day",
            confidence = 0.42,
            needsReview = true,
        ),
        ReviewField(
            key = ReviewFieldKey.INGREDIENTS,
            label = "Active ingredients",
            value = "Amoxicillin trihydrate",
            confidence = 0.88,
            needsReview = false,
        ),
    ),
    frequency = Frequency.TimesPerDay(3),
    withFood = true,
    label = "Antibiotic",
)

private val errorState = ConfirmUiState.Error(
    message = "Couldn't read the label — the photo may be blurry or cropped.",
    retriable = true,
)

@Preview
@Composable
private fun ConfirmReviewPreview() {
    HealthGuardTheme(darkTheme = false) {
        ConfirmDialog(state = reviewState, onIntent = {})
    }
}

@Preview
@Composable
private fun ConfirmReviewPreviewDark() {
    HealthGuardTheme(darkTheme = true) {
        ConfirmDialog(state = reviewState, onIntent = {})
    }
}

@Preview
@Composable
private fun ConfirmErrorPreview() {
    HealthGuardTheme(darkTheme = false) {
        ConfirmDialog(state = errorState, onIntent = {})
    }
}

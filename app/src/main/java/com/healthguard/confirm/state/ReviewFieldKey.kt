package com.healthguard.confirm.state

/**
 * Identity of one review row on the confirmation screen — what a
 * [ReviewField] is about, and the key a [ConfirmIntent.FieldEdited] /
 * [ConfirmIntent.FieldConfirmed] addresses.
 */
enum class ReviewFieldKey {
    DRUG_NAME,
    DOSAGE,
    FORM,
    FREQUENCY,
    WITH_FOOD,
    INGREDIENTS,
}

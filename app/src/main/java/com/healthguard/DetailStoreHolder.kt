package com.healthguard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * Activity-retained registry of per-medication [ViewModelStore]s for the
 * detail destination.
 *
 * The detail view model must be scoped to the detail "destination", not the
 * Activity (resolving it against the Activity's store with a per-id key would
 * retain one DetailViewModel for every medication ever visited until the
 * Activity dies). A composition-`remember`ed child store gets that bounded
 * lifetime right but does not survive activity recreation — rotating while
 * editing the form would clear the store, recreate the view model, and
 * re-seed the fields from the repository, losing typed edits.
 *
 * This holder is itself a ViewModel retained by the Activity, so the child
 * stores live across configuration changes. [clear] must be called only where
 * the detail actually closes (never from a composition `onDispose`, which
 * fires on rotation too); [onCleared] — the Activity finishing for good —
 * releases whatever is left.
 */
class DetailStoreHolder : ViewModel() {

    private class DetailStoreOwner : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    private val owners = mutableMapOf<String, DetailStoreOwner>()

    /**
     * The retained store owner for the detail showing [id]; created on first
     * use and stable across calls, so providing it as a composition local
     * never invalidates readers on recomposition.
     */
    fun ownerFor(id: String): ViewModelStoreOwner = owners.getOrPut(id) { DetailStoreOwner() }

    /** The detail for [id] has closed: clear its store and forget it. */
    fun clear(id: String) {
        owners.remove(id)?.viewModelStore?.clear()
    }

    /** The Activity is finished for good: release every retained store. */
    override fun onCleared() {
        owners.values.forEach { it.viewModelStore.clear() }
        owners.clear()
    }
}

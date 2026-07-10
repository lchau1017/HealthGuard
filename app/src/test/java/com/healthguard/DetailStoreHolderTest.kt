package com.healthguard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.healthguard.domain.model.MedicationId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailStoreHolderTest {

    private class TrackingViewModel : ViewModel() {
        var cleared = false
        override fun onCleared() {
            cleared = true
        }
    }

    /** Puts a tracking view model into [id]'s store, the way a provider would. */
    private fun DetailStoreHolder.putTracked(id: MedicationId): TrackingViewModel {
        val viewModel = TrackingViewModel()
        ownerFor(id).viewModelStore.put("detail", viewModel)
        return viewModel
    }

    @Test
    fun `ownerFor is stable per id and distinct across ids`() {
        val holder = DetailStoreHolder()

        // Same instance every call: providing it as a composition local never
        // sees a new value on recomposition, and — the point of the holder —
        // a recreated composition after rotation resolves the same store.
        assertSame(holder.ownerFor(MedicationId("a")), holder.ownerFor(MedicationId("a")))
        assertSame(holder.ownerFor(MedicationId("a")).viewModelStore, holder.ownerFor(MedicationId("a")).viewModelStore)
        assertNotSame(holder.ownerFor(MedicationId("a")), holder.ownerFor(MedicationId("b")))
        assertNotSame(holder.ownerFor(MedicationId("a")).viewModelStore, holder.ownerFor(MedicationId("b")).viewModelStore)
    }

    @Test
    fun `clear clears the id's store and forgets it`() {
        val holder = DetailStoreHolder()
        val ownerBefore = holder.ownerFor(MedicationId("a"))
        val viewModel = holder.putTracked(MedicationId("a"))

        holder.clear(MedicationId("a"))

        assertTrue(viewModel.cleared)
        // The id is forgotten: reopening the detail starts from a fresh store.
        assertNotSame(ownerBefore, holder.ownerFor(MedicationId("a")))
    }

    @Test
    fun `clearing an unknown id is a no-op`() {
        val holder = DetailStoreHolder()
        val viewModel = holder.putTracked(MedicationId("a"))

        holder.clear(MedicationId("unknown"))

        assertFalse(viewModel.cleared)
    }

    @Test
    fun `clearing one id leaves other stores alone`() {
        val holder = DetailStoreHolder()
        val clearedViewModel = holder.putTracked(MedicationId("a"))
        val retainedViewModel = holder.putTracked(MedicationId("b"))

        holder.clear(MedicationId("a"))

        assertTrue(clearedViewModel.cleared)
        assertFalse(retainedViewModel.cleared)
    }

    @Test
    fun `onCleared releases every retained store`() {
        val activityStore = ViewModelStore()
        val holder = DetailStoreHolder()
        // The holder lives in the Activity's store; clearing that store (the
        // Activity finishing for good) must cascade into the child stores.
        activityStore.put("holder", holder)
        val first = holder.putTracked(MedicationId("a"))
        val second = holder.putTracked(MedicationId("b"))

        activityStore.clear()

        assertTrue(first.cleared)
        assertTrue(second.cleared)
    }
}

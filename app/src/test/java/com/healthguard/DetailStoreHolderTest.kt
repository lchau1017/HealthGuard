package com.healthguard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
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
    private fun DetailStoreHolder.putTracked(id: String): TrackingViewModel {
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
        assertSame(holder.ownerFor("a"), holder.ownerFor("a"))
        assertSame(holder.ownerFor("a").viewModelStore, holder.ownerFor("a").viewModelStore)
        assertNotSame(holder.ownerFor("a"), holder.ownerFor("b"))
        assertNotSame(holder.ownerFor("a").viewModelStore, holder.ownerFor("b").viewModelStore)
    }

    @Test
    fun `clear clears the id's store and forgets it`() {
        val holder = DetailStoreHolder()
        val ownerBefore = holder.ownerFor("a")
        val viewModel = holder.putTracked("a")

        holder.clear("a")

        assertTrue(viewModel.cleared)
        // The id is forgotten: reopening the detail starts from a fresh store.
        assertNotSame(ownerBefore, holder.ownerFor("a"))
    }

    @Test
    fun `clearing an unknown id is a no-op`() {
        val holder = DetailStoreHolder()
        val viewModel = holder.putTracked("a")

        holder.clear("unknown")

        assertFalse(viewModel.cleared)
    }

    @Test
    fun `clearing one id leaves other stores alone`() {
        val holder = DetailStoreHolder()
        val clearedViewModel = holder.putTracked("a")
        val retainedViewModel = holder.putTracked("b")

        holder.clear("a")

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
        val first = holder.putTracked("a")
        val second = holder.putTracked("b")

        activityStore.clear()

        assertTrue(first.cleared)
        assertTrue(second.cleared)
    }
}

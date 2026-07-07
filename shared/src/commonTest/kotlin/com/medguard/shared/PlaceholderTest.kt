package com.medguard.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceholderTest {

    @Test
    fun `hello returns shared`() {
        assertEquals("shared", Placeholder.hello())
    }
}

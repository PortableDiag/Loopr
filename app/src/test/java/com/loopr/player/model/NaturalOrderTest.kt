package com.loopr.player.model

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderTest {
    @Test
    fun `numbers sort by value and case is ignored`() {
        val names = listOf("ep10.vrm", "Ep2.vrm", "ep1.vrm", "b.glb", "A.glb", "ep02b.vrm")
        assertEquals(listOf("A.glb", "b.glb", "ep1.vrm", "Ep2.vrm", "ep02b.vrm", "ep10.vrm"), names.sortedWith(NaturalOrder))
    }
}

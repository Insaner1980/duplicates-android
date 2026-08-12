package com.emma.duplicates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeletionRecoveryGateTest {
    @Test
    fun `live activity result suppresses recovery until its immediate resume has passed`() {
        val gate = DeletionRecoveryGate()

        assertFalse(gate.onAuthorizationResult(deliveredToLiveRequest = true))
        assertFalse(gate.onResume(hasPendingRequest = false))
        assertTrue(gate.onResume(hasPendingRequest = false))
    }

    @Test
    fun `detached result and resume without a live request trigger recovery`() {
        val gate = DeletionRecoveryGate()

        assertTrue(gate.onAuthorizationResult(deliveredToLiveRequest = false))
        assertFalse(gate.onResume(hasPendingRequest = true))
        assertTrue(gate.onResume(hasPendingRequest = false))
    }
}

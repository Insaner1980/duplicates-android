package com.emma.duplicates.app

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class DuplicateScanWorkerTest {
    @Test
    fun `each execution attempt gets a fresh scan session id for the same work request`() =
        runTest {
            val sessionIds = mutableListOf<String>()

            repeat(2) {
                assertEquals(
                    ScanRunOutcome.COMPLETED,
                    runScanAttempt { sessionId ->
                        sessionIds += sessionId
                        ScanRunOutcome.COMPLETED
                    },
                )
            }

            assertNotEquals(sessionIds[0], sessionIds[1])
            sessionIds.forEach { assertEquals(it, UUID.fromString(it).toString()) }
        }

    @Test
    fun `foreground operation maps an ordinary failure without swallowing its cause`() =
        runTest {
            val cause = IllegalStateException("foreground failed")

            try {
                runForegroundOperation { throw cause }
                fail("Expected foreground failure")
            } catch (failure: ForegroundWorkerFailureException) {
                assertSame(cause, failure.cause)
            }
        }

    @Test
    fun `foreground operation preserves WorkManager cancellation`() =
        runTest {
            val cancellation = CancellationException("work canceled")

            try {
                runForegroundOperation { throw cancellation }
                fail("Expected cancellation")
            } catch (caught: CancellationException) {
                assertSame(cancellation, caught)
            }
        }
}

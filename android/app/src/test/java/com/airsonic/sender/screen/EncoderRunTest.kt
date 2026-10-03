package com.airsonic.sender.screen

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class EncoderRunTest {
    @Test fun failureStopsTheRunAndNotifiesOnce() {
        val errors = mutableListOf<Exception>()
        val run = EncoderRun(errors::add)
        val failure = IllegalStateException("codec failed")
        run.fail(failure)
        run.fail(IllegalStateException("second failure"))
        assertFalse(run.running)
        assertEquals(listOf(failure), errors)
    }

    @Test fun stoppingSuppressesFailuresWhileLastSurfaceSwapCanStillDrain() {
        val run = EncoderRun { fail("Intentional stop must not report a codec error") }
        run.beginStop()
        assertTrue(run.running)
        run.fail(IllegalStateException("surface detached"))
        assertTrue(run.running)
        run.stop()
        run.fail(IllegalStateException("codec released"))
        assertFalse(run.running)
    }

    @Test fun stoppedGenerationCannotReportFailureOrStopItsReplacement() {
        val oldRun = EncoderRun { fail("The old encoder must remain silent after rotation") }
        oldRun.beginStop()
        oldRun.stop()
        val errors = mutableListOf<Exception>()
        val replacement = EncoderRun(errors::add)
        oldRun.fail(IllegalStateException("late old codec failure"))
        assertTrue(replacement.running)
        assertTrue(errors.isEmpty())
        val failure = IllegalStateException("new codec failure")
        replacement.fail(failure)
        assertFalse(replacement.running)
        assertEquals(listOf(failure), errors)
    }

    @Test fun concurrentFailuresProduceOneTerminalNotification() {
        val workers = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val notifications = AtomicInteger()
        val run = EncoderRun { notifications.incrementAndGet() }
        try {
            val results = (1..2).map {
                workers.submit {
                    assertTrue(start.await(1, TimeUnit.SECONDS))
                    run.fail(IllegalStateException("codec failed"))
                }
            }
            start.countDown()
            results.forEach { it.get(1, TimeUnit.SECONDS) }
            assertEquals(1, notifications.get())
            assertFalse(run.running)
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    @Test fun callbackExceptionDoesNotLeaveTheRunActive() {
        val callbackFailure = IllegalStateException("callback failed")
        val run = EncoderRun { throw callbackFailure }
        assertSame(callbackFailure, assertThrows(IllegalStateException::class.java) {
            run.fail(IllegalStateException("codec failed"))
        })
        assertFalse(run.running)
        run.fail(IllegalStateException("already failed"))
    }

    @Test fun consumedOutputIsReleasedExactlyOnce() {
        val operations = mutableListOf<String>()
        consumeEncoderOutput(release = { operations.add("release") }) {
            operations.add("consume")
        }
        assertEquals(listOf("consume", "release"), operations)
    }

    @Test fun downstreamFailureReleasesOutputBeforeNotifyingSessionFailure() {
        val operations = mutableListOf<String>()
        val failure = IllegalArgumentException("invalid parameter sets")
        val run = EncoderRun { error ->
            assertSame(failure, error)
            operations.add("notify")
        }
        try {
            consumeEncoderOutput(release = { operations.add("release") }) {
                operations.add("consume")
                throw failure
            }
            fail("Expected frame callback failure")
        } catch (error: Exception) {
            run.fail(error)
        }
        assertFalse(run.running)
        assertEquals(listOf("consume", "release", "notify"), operations)
    }

    @Test fun releaseFailureIsReportedWhenConsumptionSucceeds() {
        val failure = IllegalStateException("release failed")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            consumeEncoderOutput(release = { throw failure }) {}
        })
    }

    @Test fun releaseFailureDoesNotHideOriginalFrameFailure() {
        val failure = IllegalArgumentException("invalid output")
        val releaseFailure = IllegalStateException("release failed")
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) {
            consumeEncoderOutput(release = { throw releaseFailure }) { throw failure }
        })
        assertArrayEquals(arrayOf(releaseFailure), failure.suppressed)
    }
}

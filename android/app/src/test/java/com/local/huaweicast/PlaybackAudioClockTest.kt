package com.local.huaweicast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackAudioClockTest {
    @Test fun hardwareTimestampUsesRecordedSamplePosition() {
        val clock = PlaybackAudioClock(48000)
        assertEquals(1000000L, clock.read(480, 1020000000L, 960, 1030000000L))
        assertEquals(1010000L, clock.read(480, 1030000000L, 1440, 1040000000L))
    }

    @Test fun fallbackAnchorsFirstReadAndDoesNotFollowThreadSchedulingJitter() {
        val clock = PlaybackAudioClock(48000)
        assertEquals(990000L, clock.read(480, null, 0, 1000000000L))
        assertEquals(1000000L, clock.read(480, null, 0, 1040000000L))
    }

    @Test fun nativeOutputSubtractsPrimingAndCountsOnlyOutputPackets() {
        val clock = PlaybackAudioClock(48000)
        clock.read(480, 1020000000L, 960, 1030000000L)
        // The first encode call may produce no access unit.
        clock.read(480, 1030000000L, 1440, 1040000000L)
        assertEquals(995000L, clock.nextPacketTime(480, 240))
        clock.read(480, 1040000000L, 1920, 1050000000L)
        assertEquals(1005000L, clock.nextPacketTime(480, 240))
    }

    @Test fun partialPcmReadsKeepTheSameFullPacketTimestamp() {
        val clock = PlaybackAudioClock(48000)
        assertEquals(1000000L, clock.read(120, 1003000000L, 144, 1100000000L))
        assertEquals(1002500L, clock.read(240, 1008000000L, 384, 1100000000L))
        assertEquals(1007500L, clock.read(120, 1010000000L, 480, 1100000000L))
        assertEquals(995000L, clock.nextPacketTime(480, 240))
    }

    @Test fun sampleCounterDoesNotAccumulateRoundingDriftAt44100Hz() {
        val clock = PlaybackAudioClock(44100)
        for (index in 0L..1000L) {
            clock.read(480, 2000000000L, 0, 2100000000L)
            assertEquals(2000000L + (index * 480L - 240L) * 1000000L / 44100L,
                clock.nextPacketTime(480, 240))
        }
    }

    @Test fun regressingHardwareTimestampCannotCompressAnEncodedPacket() {
        val clock = PlaybackAudioClock(48000)
        assertEquals(1000000L, clock.read(480, 1000000000L, 0, 1100000000L))
        assertEquals(995000L, clock.nextPacketTime(480, 240))
        assertEquals(1000001L, clock.read(480, 900000000L, 0, 1110000000L))
        assertEquals(1005000L, clock.nextPacketTime(480, 240))
    }

    @Test fun jitteryHardwareAnchorsKeepAllPacketIntervalsNearTheRealFrameDuration() {
        val clock = PlaybackAudioClock(48000)
        clock.read(480, 1000000000L, 0, 1100000000L)
        var previous = clock.nextPacketTime(480, 240)
        for (index in 1L..200L) {
            val jitterUs = if (index % 2L == 0L) -3000L else 3000L
            val hardwareUs = 1000000L + index * 10000L + jitterUs
            clock.read(480, hardwareUs * 1000L, index * 480L, hardwareUs * 1000L + 100000000L)
            val packet = clock.nextPacketTime(480, 240)
            assertTrue("Every encoded AU must retain its duration", packet - previous in 9990L..10010L)
            previous = packet
        }
    }

    @Test fun unavailableTimestampDoesNotSwitchBackToAnOldFallbackAnchor() {
        val clock = PlaybackAudioClock(48000)
        clock.read(480, 1000000000L, 0, 1200000000L)
        assertEquals(995000L, clock.nextPacketTime(480, 240))
        clock.read(480, null, 0, 2000000000L)
        assertEquals(1005000L, clock.nextPacketTime(480, 240))
        clock.read(480, 1020000000L, 960, 2100000000L)
        assertEquals(1015000L, clock.nextPacketTime(480, 240))
    }

    @Test fun firstAvailableHardwareTimestampIsAppliedGraduallyAfterPacketsHaveStarted() {
        val clock = PlaybackAudioClock(48000)
        clock.read(480, null, 0, 1010000000L)
        assertEquals(995000L, clock.nextPacketTime(480, 240))
        clock.read(480, 1015000000L, 480, 1020000000L)
        assertEquals(1005010L, clock.nextPacketTime(480, 240))
    }

    @Test fun boundedClockCorrectionConvergesWithoutDeletingOrDuplicatingSamples() {
        val clock = PlaybackAudioClock(48000)
        clock.read(480, null, 0, 1010000000L)
        var previous = clock.nextPacketTime(480, 240)
        for (index in 1L..100L) {
            val hardwareUs = 1001000L + index * 10000L
            clock.read(480, hardwareUs * 1000L, index * 480L, hardwareUs * 1000L)
            val packet = clock.nextPacketTime(480, 240)
            assertEquals(10010L, packet - previous)
            previous = packet
        }
        assertEquals(1996000L, previous)
    }

    @Test fun staleOrRegressingFramePositionsDoNotRetargetThePacketClock() {
        val clock = PlaybackAudioClock(48000)
        clock.read(480, 1010000000L, 480, 1100000000L)
        assertEquals(995000L, clock.nextPacketTime(480, 240))
        clock.read(480, 1050000000L, 480, 1110000000L)
        assertEquals(1005000L, clock.nextPacketTime(480, 240))
        clock.read(480, 1100000000L, 240, 1120000000L)
        assertEquals(1015000L, clock.nextPacketTime(480, 240))
    }

    @Test fun packetRequiresCapturedSamplesAndValidParameters() {
        val clock = PlaybackAudioClock(48000)
        assertThrows(IllegalStateException::class.java) { clock.nextPacketTime(480, 240) }
        assertThrows(IllegalArgumentException::class.java) { clock.read(0, null, 0, 1000000000L) }
        clock.read(480, null, 0, 1000000000L)
        assertThrows(IllegalArgumentException::class.java) { clock.nextPacketTime(0, 240) }
        assertThrows(IllegalArgumentException::class.java) { clock.nextPacketTime(480, -1) }
    }
}

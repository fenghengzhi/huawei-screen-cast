package com.local.huaweicast

import org.junit.Assert.*
import org.junit.Test

class AudioCodecConfigLatchTest {
    @Test fun announcesConfigurationOnce() {
        val latch = AudioCodecConfigLatch()
        val config = byteArrayOf(0xf8.toByte(), 0xe8.toByte(), 0x40, 0)
        assertFalse(latch.hasConfig())
        assertArrayEquals(config, latch.accept(config))
        assertTrue(latch.hasConfig())
        assertNull(latch.accept(config.copyOf()))
    }

    @Test fun protectsStoredConfigurationFromMutations() {
        val latch = AudioCodecConfigLatch()
        val config = byteArrayOf(0xf8.toByte(), 0xe6.toByte(), 0x40, 0)
        val announced = latch.accept(config)!!
        config[0] = 0
        announced[1] = 0
        assertNull(latch.accept(byteArrayOf(0xf8.toByte(), 0xe6.toByte(), 0x40, 0)))
    }

    @Test fun rejectsConfigurationChanges() {
        val latch = AudioCodecConfigLatch()
        latch.accept(byteArrayOf(0xf8.toByte(), 0xe8.toByte(), 0x40, 0))
        assertThrows(IllegalStateException::class.java) {
            latch.accept(byteArrayOf(0xf8.toByte(), 0xe6.toByte(), 0x40, 0))
        }
    }

    @Test fun rejectsEmptyOrUnboundedConfiguration() {
        val latch = AudioCodecConfigLatch()
        assertThrows(IllegalArgumentException::class.java) { latch.accept(byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { latch.accept(ByteArray(65)) }
        assertFalse(latch.hasConfig())
    }
}

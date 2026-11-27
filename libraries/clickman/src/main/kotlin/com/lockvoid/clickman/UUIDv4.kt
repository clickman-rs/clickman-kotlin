package com.lockvoid.clickman

import java.util.UUID
import kotlin.random.Random

/** RFC 9562 version 4: 122 random bits, the version and the RFC 4122 variant. */
internal object UUIDv4 {
    private const val VERSION = 0x4000L
    private const val VERSION_MASK = 0xF000L
    private const val RFC_4122_VARIANT = Long.MIN_VALUE

    fun generate(random: Random): UUID {
        val high = (random.nextLong() and VERSION_MASK.inv()) or VERSION
        val low = (random.nextLong() ushr 2) or RFC_4122_VARIANT
        return UUID(high, low)
    }
}

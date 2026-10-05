package dev.local.ourahealthbridge.analysis

import dev.local.ourahealthbridge.protocol.RawRingEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RingTimeMapperTest {
    @Test
    fun mapsDecisecondsAroundExplicitAnchor() {
        val mapper = RingTimeMapper.fromAnchors(listOf(RingTimeAnchor(10_000u, 1_800_000_000L)))
        assertEquals(1_800_000_500_000L, mapper.unixMillis(15_000u))
        assertEquals(1_799_999_900_000L, mapper.unixMillis(9_000u))
    }

    @Test
    fun usesNearestAnchorAndReportsClockCorrection() {
        val mapper = RingTimeMapper.fromAnchors(
            listOf(
                RingTimeAnchor(10_000u, 1_800_000_000L),
                RingTimeAnchor(20_000u, 1_800_001_002L),
            ),
        )
        assertEquals(2_000L, mapper.maximumAnchorResidualMillis())
        assertEquals(1_800_001_052_000L, mapper.unixMillis(20_500u))
    }

    @Test
    fun refusesMappingWithoutAnchor() {
        assertNull(RingTimeMapper.fromEvents(emptyList()).unixMillis(1u))
    }
}

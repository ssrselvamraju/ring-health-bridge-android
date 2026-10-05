package dev.local.ourahealthbridge.analysis

import dev.local.ourahealthbridge.protocol.DecodedRingEvent
import dev.local.ourahealthbridge.protocol.OuraEventDecoder
import dev.local.ourahealthbridge.protocol.RawRingEvent
import kotlin.math.abs

data class RingTimeAnchor(val ringDeciseconds: UInt, val unixSeconds: Long)

/** Piecewise wall-clock reconstruction using the nearest explicit ring time-sync event. */
class RingTimeMapper private constructor(private val anchors: List<RingTimeAnchor>) {
    val anchorCount: Int get() = anchors.size

    fun unixMillis(ringDeciseconds: UInt): Long? {
        val anchor = anchors.minByOrNull {
            abs(it.ringDeciseconds.toLong() - ringDeciseconds.toLong())
        } ?: return null
        val deltaDeciseconds = ringDeciseconds.toLong() - anchor.ringDeciseconds.toLong()
        return anchor.unixSeconds * 1_000L + deltaDeciseconds * 100L
    }

    /** Largest deviation from a perfect 100 ms/decisecond clock between adjacent anchors. */
    fun maximumAnchorResidualMillis(): Long? = anchors.zipWithNext().maxOfOrNull { (first, second) ->
        val observed = (second.unixSeconds - first.unixSeconds) * 1_000L
        val ringElapsed = (second.ringDeciseconds.toLong() - first.ringDeciseconds.toLong()) * 100L
        abs(observed - ringElapsed)
    }

    companion object {
        fun fromEvents(events: List<RawRingEvent>): RingTimeMapper {
            val anchors = events.mapNotNull { event ->
                (OuraEventDecoder.decode(event) as? DecodedRingEvent.TimeSync)?.let {
                    RingTimeAnchor(event.ringTimestampDeciseconds, it.unixSeconds)
                }
            }.distinct().sortedBy(RingTimeAnchor::ringDeciseconds)
            return RingTimeMapper(anchors)
        }

        fun fromAnchors(anchors: List<RingTimeAnchor>): RingTimeMapper =
            RingTimeMapper(anchors.distinct().sortedBy(RingTimeAnchor::ringDeciseconds))
    }
}

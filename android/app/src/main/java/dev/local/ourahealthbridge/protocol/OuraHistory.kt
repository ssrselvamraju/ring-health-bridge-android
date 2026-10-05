package dev.local.ourahealthbridge.protocol

data class RawRingEvent(
    val tag: Int,
    val ringTimestampDeciseconds: UInt,
    val body: ByteArray,
) {
    init {
        require(tag >= HISTORY_EVENT_PREFIX) { "Not a history-event tag" }
    }

    override fun equals(other: Any?): Boolean =
        other is RawRingEvent &&
            tag == other.tag &&
            ringTimestampDeciseconds == other.ringTimestampDeciseconds &&
            body.contentEquals(other.body)

    override fun hashCode(): Int =
        31 * (31 * tag + ringTimestampDeciseconds.hashCode()) + body.contentHashCode()
}

data class EventBatchSummary(
    val eventsReceived: Int,
    val sleepAnalysisProgress: Int,
    val bytesLeft: UInt,
)

data class CompletedEventBatch(
    val events: List<RawRingEvent>,
    val nextCursor: UInt,
    val bytesLeft: UInt,
    val progressed: Boolean,
)

class EventBatchAccumulator(private val startCursor: UInt) {
    private val events = mutableListOf<RawRingEvent>()

    fun accept(packet: OuraPacket): EventBatchSummary? {
        if (packet.tag >= HISTORY_EVENT_PREFIX) {
            parseEvent(packet)?.let(events::add)
            return null
        }
        return parseSummary(packet)
    }

    fun complete(summary: EventBatchSummary): CompletedEventBatch {
        val maxTimestamp = events.maxOfOrNull(RawRingEvent::ringTimestampDeciseconds) ?: startCursor
        val next = if (maxTimestamp == UInt.MAX_VALUE) UInt.MAX_VALUE else maxTimestamp + 1u
        val progressed = events.isNotEmpty() && next > startCursor
        return CompletedEventBatch(
            events = events.toList(),
            nextCursor = if (progressed) next else startCursor,
            bytesLeft = summary.bytesLeft,
            progressed = progressed,
        )
    }

    companion object {
        fun parseEvent(packet: OuraPacket): RawRingEvent? {
            if (packet.tag < HISTORY_EVENT_PREFIX) return null
            val timestamp = if (packet.payload.size >= 4) packet.payload.readUIntLittleEndian(0) else 0u
            return RawRingEvent(
                tag = packet.tag,
                ringTimestampDeciseconds = timestamp,
                body = if (packet.payload.size > 4) {
                    packet.payload.copyOfRange(4, packet.payload.size)
                } else {
                    byteArrayOf()
                },
            )
        }

        fun parseSummary(packet: OuraPacket): EventBatchSummary? {
            if (packet.tag != EVENT_BATCH_SUMMARY_TAG || packet.payload.size < 6) return null
            return EventBatchSummary(
                eventsReceived = packet.payload[0].toUByte().toInt(),
                sleepAnalysisProgress = packet.payload[1].toUByte().toInt(),
                bytesLeft = packet.payload.readUIntLittleEndian(2),
            )
        }
    }
}

const val HISTORY_EVENT_PREFIX = 0x41
private const val EVENT_BATCH_SUMMARY_TAG = 0x11

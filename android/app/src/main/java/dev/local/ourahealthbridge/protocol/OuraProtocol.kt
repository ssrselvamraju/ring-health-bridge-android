package dev.local.ourahealthbridge.protocol

import java.util.UUID

object OuraGatt {
    val service: UUID = UUID.fromString("98ed0001-a541-11e4-b6a0-0002a5d5c51b")
    val write: UUID = UUID.fromString("98ed0002-a541-11e4-b6a0-0002a5d5c51b")
    val notify: UUID = UUID.fromString("98ed0003-a541-11e4-b6a0-0002a5d5c51b")
}

data class OuraPacket(val tag: Int, val payload: ByteArray) {
    init {
        require(tag in 0..255) { "Tag must fit one byte" }
        require(payload.size <= UByte.MAX_VALUE.toInt()) { "Payload exceeds one Oura frame" }
    }

    fun encode(): ByteArray = byteArrayOf(tag.toByte(), payload.size.toByte()) + payload

    fun extendedTag(): Int? = if (tag == 0x2f) payload.firstOrNull()?.toUByte()?.toInt() else null

    override fun equals(other: Any?): Boolean =
        other is OuraPacket && tag == other.tag && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * tag.hashCode() + payload.contentHashCode()

    companion object {
        fun parse(frame: ByteArray): OuraPacket? {
            if (frame.size < 2) return null
            val declaredLength = frame[1].toUByte().toInt()
            val availableLength = frame.size - 2
            val payloadLength = minOf(declaredLength, availableLength)
            return OuraPacket(frame[0].toUByte().toInt(), frame.copyOfRange(2, 2 + payloadLength))
        }
    }
}

object OuraRequests {
    fun battery(): ByteArray = OuraPacket(0x0c, byteArrayOf()).encode()

    fun authNonce(): ByteArray = byteArrayOf(0x2f, 0x01, 0x2b)

    fun featureStatus(featureId: UByte): ByteArray =
        OuraPacket(0x2f, byteArrayOf(0x20, featureId.toByte())).encode()

    fun setFeatureMode(featureId: UByte, mode: UByte): ByteArray =
        OuraPacket(0x2f, byteArrayOf(0x22, featureId.toByte(), mode.toByte())).encode()

    fun setFeatureSubscription(capabilityId: UByte, mode: UByte): ByteArray =
        OuraPacket(0x2f, byteArrayOf(0x26, capabilityId.toByte(), mode.toByte())).encode()

    fun authenticate(encryptedNonce: ByteArray): ByteArray {
        require(encryptedNonce.size == 16) { "Encrypted nonce must be 16 bytes" }
        return OuraPacket(0x2f, byteArrayOf(0x2d) + encryptedNonce).encode()
    }

    fun syncTime(unixSeconds: ULong, timezoneHalfHours: UByte = 0u): ByteArray {
        val payload = ByteArray(9) { index ->
            if (index < 8) ((unixSeconds shr (index * 8)) and 0xffu).toByte() else timezoneHalfHours.toByte()
        }
        return OuraPacket(0x12, payload).encode()
    }

    fun events(startDeciseconds: UInt, maximumEvents: UByte = 255u): ByteArray {
        val start = startDeciseconds.toLong()
        val payload = byteArrayOf(
            (start and 0xff).toByte(),
            ((start shr 8) and 0xff).toByte(),
            ((start shr 16) and 0xff).toByte(),
            ((start shr 24) and 0xff).toByte(),
            maximumEvents.toByte(),
            0xff.toByte(),
            0xff.toByte(),
            0xff.toByte(),
            0xff.toByte(),
        )
        return OuraPacket(0x10, payload).encode()
    }
}

object OuraResponses {
    data class FeatureStatus(
        val featureId: Int,
        val mode: Int,
        val status: Int,
        val state: Int,
        val subscription: Int,
    )

    data class FeatureMutationResult(
        val featureId: Int,
        val result: Int,
    )

    fun authNonce(packet: OuraPacket): ByteArray? =
        packet.payload.takeIf {
            packet.tag == 0x2f && it.size == 16 && it.first().toUByte().toInt() == 0x2c
        }?.copyOfRange(1, 16)

    fun authenticationSucceeded(packet: OuraPacket): Boolean =
        packet.tag == 0x2f &&
            packet.payload.size >= 2 &&
            packet.payload[0].toUByte().toInt() == 0x2e &&
            packet.payload[1] == 0.toByte()

    fun batteryPercent(packet: OuraPacket): Int? =
        packet.payload.firstOrNull()?.toUByte()?.toInt()?.takeIf {
            packet.tag == 0x0d && it in 0..100
        }

    fun featureStatus(packet: OuraPacket): FeatureStatus? {
        if (packet.tag != 0x2f || packet.extendedTag() != 0x21 || packet.payload.size < 6) return null
        return FeatureStatus(
            featureId = packet.payload[1].toUByte().toInt(),
            mode = packet.payload[2].toUByte().toInt(),
            status = packet.payload[3].toUByte().toInt(),
            state = packet.payload[4].toUByte().toInt(),
            subscription = packet.payload[5].toUByte().toInt(),
        )
    }

    fun setFeatureModeResult(packet: OuraPacket): FeatureMutationResult? =
        featureMutationResult(packet, responseTag = 0x23)

    fun setFeatureSubscriptionResult(packet: OuraPacket): FeatureMutationResult? =
        featureMutationResult(packet, responseTag = 0x27)

    private fun featureMutationResult(packet: OuraPacket, responseTag: Int): FeatureMutationResult? {
        if (packet.tag != 0x2f || packet.extendedTag() != responseTag || packet.payload.size < 3) return null
        return FeatureMutationResult(
            featureId = packet.payload[1].toUByte().toInt(),
            result = packet.payload[2].toUByte().toInt(),
        )
    }
}

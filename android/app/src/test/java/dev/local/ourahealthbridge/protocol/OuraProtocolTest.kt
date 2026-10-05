package dev.local.ourahealthbridge.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OuraProtocolTest {
    @Test
    fun packet_roundTrips() {
        val packet = OuraPacket(0x2f, byteArrayOf(0x2b))
        assertEquals(packet, OuraPacket.parse(packet.encode()))
    }

    @Test
    fun packet_rejectsShortFrame() {
        assertNull(OuraPacket.parse(byteArrayOf(0x2f)))
    }

    @Test
    fun knownRequests_matchPinnedOpenOura() {
        assertEquals("0c00", OuraRequests.battery().hex())
        assertEquals("2f012b", OuraRequests.authNonce().hex())
        assertEquals("2f02200b", OuraRequests.featureStatus(0x0bu).hex())
        assertEquals("2f03220b01", OuraRequests.setFeatureMode(0x0bu, 0x01u).hex())
        assertEquals("2f03260b04", OuraRequests.setFeatureSubscription(0x0bu, 0x04u).hex())
        assertEquals("10090000000008ffffffff", OuraRequests.events(0u, 8u).hex())
    }

    @Test
    fun parsesFeatureMutationResults() {
        val mode = OuraResponses.setFeatureModeResult(OuraPacket.parse("2f03230b00".hexBytes())!!)!!
        assertEquals(0x0b, mode.featureId)
        assertEquals(0, mode.result)

        val subscription = OuraResponses.setFeatureSubscriptionResult(
            OuraPacket.parse("2f03270b05".hexBytes())!!,
        )!!
        assertEquals(0x0b, subscription.featureId)
        assertEquals(5, subscription.result)
        assertNull(OuraResponses.setFeatureModeResult(OuraPacket.parse("2f03270b00".hexBytes())!!))
    }

    @Test
    fun parsesFeatureStatusWithoutAcceptingOtherResponses() {
        val response = OuraPacket.parse("2f06210b01020304".hexBytes())!!
        val parsed = OuraResponses.featureStatus(response)!!

        assertEquals(0x0b, parsed.featureId)
        assertEquals(1, parsed.mode)
        assertEquals(2, parsed.status)
        assertEquals(3, parsed.state)
        assertEquals(4, parsed.subscription)
        assertNull(OuraResponses.featureStatus(OuraPacket.parse("2f022e00".hexBytes())!!))
    }

    @Test
    fun parsesDesktopVerifiedResponses() {
        val auth = OuraPacket.parse("2f022e00".hexBytes())!!
        val battery = OuraPacket.parse("0d06646400004710".hexBytes())!!

        assertTrue(OuraResponses.authenticationSucceeded(auth))
        assertEquals(100, OuraResponses.batteryPercent(battery))
        assertFalse(OuraResponses.authenticationSucceeded(battery))
    }

    @Test
    fun authRequest_wrapsEncryptedNonce() {
        val encrypted = ByteArray(16) { it.toByte() }
        assertArrayEquals(
            byteArrayOf(0x2f, 0x11, 0x2d) + encrypted,
            OuraRequests.authenticate(encrypted),
        )
    }
}

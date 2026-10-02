package app.halo.tuya

import org.junit.Test

class ProtocolTest {
    @Test fun json() = ProtocolChecks.json()
    @Test fun encodeMatchesTinytuya() = ProtocolChecks.encodeMatchesTinytuya()
    @Test fun decodeTinytuyaFrames() = ProtocolChecks.decodeTinytuyaFrames()
    @Test fun discoveryPackets() = ProtocolChecks.discoveryPackets()
    @Test fun bulbCodec() = ProtocolChecks.bulbCodec()
    @Test fun cloudSignature() = ProtocolChecks.cloudSignature()
    @Test fun liveFakeBulbs() = ProtocolChecks.liveFakeBulbs()
    @Test fun liveDiscovery() = ProtocolChecks.liveDiscovery()
}

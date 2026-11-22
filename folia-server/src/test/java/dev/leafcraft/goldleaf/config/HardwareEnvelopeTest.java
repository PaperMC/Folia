package dev.leafcraft.goldleaf.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HardwareEnvelopeTest {

    @Test
    void classifiesHardwareTiersAcrossBarbell() {
        assertEquals(HardwareEnvelope.HardwareTier.WORKSTATION, HardwareEnvelope.of(6, 8L << 30).tier());
        assertEquals(HardwareEnvelope.HardwareTier.WORKSTATION, HardwareEnvelope.of(16, 16L << 30).tier());
        assertEquals(HardwareEnvelope.HardwareTier.SERVER, HardwareEnvelope.of(32, 64L << 30).tier());
        assertEquals(HardwareEnvelope.HardwareTier.SERVER, HardwareEnvelope.of(64, 128L << 30).tier());
        assertEquals(HardwareEnvelope.HardwareTier.MEGA_NODE, HardwareEnvelope.of(96, 256L << 30).tier());
    }

    @Test
    void normalizesInvalidInputs() {
        final HardwareEnvelope envelope = HardwareEnvelope.of(0, -1024L);
        assertEquals(1, envelope.logicalCores());
        assertEquals(0L, envelope.maxHeapBytes());
        assertEquals(0L, envelope.maxHeapMiB());
        assertEquals(HardwareEnvelope.HardwareTier.WORKSTATION, envelope.tier());
    }

    @Test
    void describesEnvelopeReadably() {
        final HardwareEnvelope envelope = HardwareEnvelope.of(8, 16L * 1024L * 1024L * 1024L);
        assertEquals(16384L, envelope.maxHeapMiB());
        assertTrue(envelope.describe().contains("WORKSTATION"));
        assertTrue(envelope.describe().contains("logicalCores=8"));
        assertTrue(envelope.describe().contains("maxHeapMiB=16384"));
    }
}

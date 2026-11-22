package dev.leafcraft.goldleaf.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ResourceLeakDetector;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurateException;

class BroadcastPacketEncoderTest {

    private record MockViewer(String id, boolean hasInterceptor, boolean failSharedWrite) {
    }

    @Test
    void highFanoutEncodesOnceIsolatesInterceptedViewersAndLeaksZeroBuffers() {
        final ResourceLeakDetector.Level previousLevel = ResourceLeakDetector.getLevel();
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
        try {
            final List<MockViewer> viewers = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                // Every 10th viewer has a per-player interceptor (e.g. ProtocolLib / PacketEvents)
                viewers.add(new MockViewer("v-" + i, (i % 10) == 0, false));
            }

            final byte[] expectedBytes = "entity-move-payload-42".getBytes(StandardCharsets.UTF_8);
            final AtomicInteger serializeCalls = new AtomicInteger();
            final AtomicReference<ByteBuf> allocatedRef = new AtomicReference<>();
            final List<String> sharedRecipients = new ArrayList<>();
            final List<String> fallbackRecipients = new ArrayList<>();

            final SharedBroadcastPacketEncoder.BroadcastResult result = SharedBroadcastPacketEncoder.broadcastGeneric(
                viewers,
                "MovePacket#42",
                8,
                pkt -> true,
                MockViewer::hasInterceptor,
                pkt -> {
                    serializeCalls.incrementAndGet();
                    final ByteBuf buf = SharedBroadcastPacketEncoder.allocateBuffer(PooledByteBufAllocator.DEFAULT, expectedBytes);
                    allocatedRef.set(buf);
                    return buf;
                },
                (viewer, slice) -> {
                    try {
                        final byte[] actual = new byte[slice.readableBytes()];
                        slice.readBytes(actual);
                        assertArrayEquals(expectedBytes, actual);
                        sharedRecipients.add(viewer.id());
                    } finally {
                        ReferenceCountUtil.safeRelease(slice);
                    }
                },
                (viewer, pkt) -> fallbackRecipients.add(viewer.id())
            );

            assertTrue(result.usedSharedBuffer());
            assertEquals(1, serializeCalls.get(), "High-fanout packet must serialize exactly once");
            assertEquals(180, result.sharedDeliveredCount());
            assertEquals(20, result.fallbackDeliveredCount());
            assertEquals(180, sharedRecipients.size());
            assertEquals(20, fallbackRecipients.size());
            assertEquals(0, allocatedRef.get().refCnt(), "Shared ByteBuf refCnt must be 0 after broadcast (S-002)");
        } finally {
            ResourceLeakDetector.setLevel(previousLevel);
        }
    }

    @Test
    void sliceSinkFailureReleasesSliceFallsBackAndBalancesRefCount() {
        final List<MockViewer> viewers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            viewers.add(new MockViewer("v-" + i, false, i == 3 || i == 7));
        }

        final AtomicReference<ByteBuf> allocatedRef = new AtomicReference<>();
        final AtomicInteger fallbackCalls = new AtomicInteger();

        final SharedBroadcastPacketEncoder.BroadcastResult result = SharedBroadcastPacketEncoder.broadcastGeneric(
            viewers,
            "MetaPacket#9",
            4,
            pkt -> true,
            MockViewer::hasInterceptor,
            pkt -> {
                final ByteBuf buf = SharedBroadcastPacketEncoder.allocateBuffer(
                    PooledByteBufAllocator.DEFAULT,
                    new byte[] {1, 2, 3, 4}
                );
                allocatedRef.set(buf);
                return buf;
            },
            (viewer, slice) -> {
                if (viewer.failSharedWrite()) {
                    throw new IllegalStateException("simulated channel write failure");
                }
                ReferenceCountUtil.safeRelease(slice);
            },
            (viewer, pkt) -> fallbackCalls.incrementAndGet()
        );

        assertTrue(result.usedSharedBuffer());
        assertEquals(8, result.sharedDeliveredCount());
        assertEquals(2, result.fallbackDeliveredCount());
        assertEquals(2, fallbackCalls.get());
        assertEquals(0, allocatedRef.get().refCnt(), "Shared ByteBuf refCnt must be 0 even after sink failures (S-002)");
    }

    @Test
    void belowThresholdOrDisabledDeclinesSharedBroadcast() {
        final List<MockViewer> viewers = List.of(
            new MockViewer("v-0", false, false),
            new MockViewer("v-1", false, false)
        );
        final AtomicInteger serializeCalls = new AtomicInteger();

        final SharedBroadcastPacketEncoder.BroadcastResult belowThreshold = SharedBroadcastPacketEncoder.broadcastGeneric(
            viewers,
            "MovePacket",
            8,
            pkt -> true,
            MockViewer::hasInterceptor,
            pkt -> {
                serializeCalls.incrementAndGet();
                return null;
            },
            (v, s) -> ReferenceCountUtil.safeRelease(s),
            (v, p) -> {
            }
        );

        assertFalse(belowThreshold.usedSharedBuffer());
        assertEquals(0, serializeCalls.get());
    }

    @Test
    void configValidationFailsClosedOnInvalidSharedBroadcastThreshold() throws Exception {
        final GoldLeafConfig validDisabled = GoldLeafConfig.defaults();
        validDisabled.network.entityTrackerSharedBroadcastThreshold = 0;
        validDisabled.validate();

        final GoldLeafConfig validEnabled = GoldLeafConfig.defaults();
        validEnabled.network.entityTrackerSharedBroadcastThreshold = 2;
        validEnabled.validate();

        final GoldLeafConfig invalidOne = GoldLeafConfig.defaults();
        invalidOne.network.entityTrackerSharedBroadcastThreshold = 1;
        assertThrows(ConfigurateException.class, invalidOne::validate);

        final GoldLeafConfig invalidNeg = GoldLeafConfig.defaults();
        invalidNeg.network.entityTrackerSharedBroadcastThreshold = -5;
        assertThrows(ConfigurateException.class, invalidNeg::validate);
    }
}

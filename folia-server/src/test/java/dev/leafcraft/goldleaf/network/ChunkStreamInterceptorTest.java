package dev.leafcraft.goldleaf.network;

import dev.leafcraft.goldleaf.storage.DirectSectionSerializer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkStreamInterceptorTest {

    @org.junit.jupiter.api.BeforeEach
    @AfterEach
    void resetRegistries() {
        ChunkStreamInterceptor.resetDefault();
        DirectSectionSerializer.resetDefault();
    }

    @Test
    void chunkStreamInterceptorDefaultsToNullAndSupportsRegisterReset() {
        assertNull(ChunkStreamInterceptor.get());

        final ChunkStreamInterceptor custom = new ChunkStreamInterceptor() {
            @Override
            public boolean supportsConnection(final ServerGamePacketListenerImpl connection) {
                return connection != null;
            }

            @Override
            public boolean trySendChunk(
                final ServerGamePacketListenerImpl connection,
                final ServerLevel level,
                final LevelChunk chunk,
                final boolean shouldModifyAntiXray
            ) {
                return true;
            }
        };

        ChunkStreamInterceptor.register(custom);
        assertSame(custom, ChunkStreamInterceptor.get());
        assertFalse(custom.supportsConnection(null));

        ChunkStreamInterceptor.resetDefault();
        assertNull(ChunkStreamInterceptor.get());
    }

    @Test
    void directSectionSerializerDefaultsToNullAndSupportsRegisterReset() {
        assertNull(DirectSectionSerializer.get());

        final DirectSectionSerializer custom = new DirectSectionSerializer() {
            @Override
            public boolean supportsDataVersion(final int dataVersion) {
                return dataVersion == 4189;
            }

            @Override
            public @Nullable CompoundTag trySerializeDirect(final SerializableChunkData data) {
                return null;
            }
        };

        DirectSectionSerializer.register(custom);
        assertSame(custom, DirectSectionSerializer.get());
        assertTrue(custom.supportsDataVersion(4189));
        assertFalse(custom.supportsDataVersion(3953));
        assertNull(custom.trySerializeDirect(null));

        DirectSectionSerializer.resetDefault();
        assertNull(DirectSectionSerializer.get());
    }
}

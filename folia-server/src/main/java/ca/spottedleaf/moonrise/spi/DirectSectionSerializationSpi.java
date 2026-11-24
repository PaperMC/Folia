package ca.spottedleaf.moonrise.spi;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.jspecify.annotations.Nullable;

/**
 * Layer 1 upstream-candidate direct-section serialization SPI for {@link SerializableChunkData}.
 *
 * <p>Allows a registered storage engine to bypass boxed {@code CompoundTag}/{@code ListTag}
 * section serialization when the chunk {@code DataVersion} matches the current server version,
 * while preserving Folia {@code redstoneGameTime} ticks, Starlight light-state metadata, and
 * fallback to vanilla NBT for legacy upgrades.</p>
 */
public interface DirectSectionSerializationSpi {

    default boolean supportsDataVersion(final int dataVersion) {
        return dataVersion == SharedConstants.getCurrentVersion().dataVersion().version();
    }

    @Nullable CompoundTag trySerializeDirect(SerializableChunkData data);

    default @Nullable CompoundTag trySerializeDirect(
        final SerializableChunkData data,
        final @Nullable String worldId
    ) {
        return trySerializeDirect(data);
    }

    default @Nullable SerializableChunkData tryDeserializeDirect(
        final net.minecraft.server.level.ServerLevel level,
        final net.minecraft.world.level.chunk.PalettedContainerFactory containerFactory,
        final CompoundTag chunkData
    ) {
        return null;
    }

    default void onChunkSerialized(final SerializableChunkData data, final CompoundTag serializedTag) {
    }

    static @Nullable DirectSectionSerializationSpi get() {
        return Holder.current;
    }

    static void register(final @Nullable DirectSectionSerializationSpi serializer) {
        Holder.current = serializer;
    }

    static void resetDefault() {
        Holder.current = null;
    }

    final class Holder {
        private static volatile @Nullable DirectSectionSerializationSpi current;

        private Holder() {
        }
    }
}

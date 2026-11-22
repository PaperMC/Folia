package dev.leafcraft.goldleaf.storage;

import ca.spottedleaf.moonrise.spi.DirectSectionSerializationSpi;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.jspecify.annotations.Nullable;

/**
 * Layer 2 compatibility facade extending the Layer 1 {@link DirectSectionSerializationSpi}
 * ({@code goldleaf-ADR-005} Stage 2).
 */
public interface DirectSectionSerializer extends DirectSectionSerializationSpi {

    static @Nullable DirectSectionSerializationSpi get() {
        return DirectSectionSerializationSpi.get();
    }

    static void register(final @Nullable DirectSectionSerializationSpi serializer) {
        DirectSectionSerializationSpi.register(serializer);
    }

    static void resetDefault() {
        DirectSectionSerializationSpi.resetDefault();
    }
}

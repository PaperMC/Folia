package ca.spottedleaf.moonrise.spi;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * Layer 1 upstream-candidate SPI for per-section 4-state lifecycle transitions
 * ({@code UNIFORM / COLD_LCA / WARM_SPARSE / HOT_MUTABLE}) and hysteresis compaction
 * ({@code goldleaf-ADR-005} Stage 4, {@code leafanvil-ADR-002}, {@code leafanvil-ADR-006}).
 *
 * <p>Hooks are invoked strictly on the chunk's owning region tick thread ({@code F-001..F-003})
 * and must never block or perform heavy encoding synchronously ({@code S-005}).</p>
 */
public interface SectionLifecycleSpi {

    /**
     * Invoked on the owning region tick thread when a chunk downgrades out of
     * {@code ENTITY_TICKING} (or {@code BLOCK_TICKING}) into a colder full status.
     */
    void onChunkDowngraded(ServerLevel world, LevelChunk chunk);

    /**
     * Invoked on the owning region tick thread when a freshly generated {@code ProtoChunk}
     * completes conversion to a live {@link LevelChunk} in {@code ChunkFullTask}.
     */
    void onGeneratedChunkFull(ServerLevel world, LevelChunk chunk);

    static void notifyChunkDowngraded(final ServerLevel world, final LevelChunk chunk) {
        final SectionLifecycleSpi spi = Holder.current;
        if (spi != null && world != null && chunk != null) {
            spi.onChunkDowngraded(world, chunk);
        }
    }

    static void notifyGeneratedChunkFull(final ServerLevel world, final LevelChunk chunk) {
        final SectionLifecycleSpi spi = Holder.current;
        if (spi != null && world != null && chunk != null) {
            spi.onGeneratedChunkFull(world, chunk);
        }
    }

    static @Nullable SectionLifecycleSpi get() {
        return Holder.current;
    }

    static void register(final @Nullable SectionLifecycleSpi spi) {
        Holder.current = spi;
    }

    static void resetDefault() {
        Holder.current = null;
    }

    final class Holder {
        private static volatile @Nullable SectionLifecycleSpi current;

        private Holder() {
        }
    }
}

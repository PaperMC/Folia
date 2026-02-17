package net.azisaba.aetheria.world;

import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@NullMarked
class AetheriaRandomStateSource {
    private final Map<Long, Map<AetheriaLayer.Type, RandomState>> cacheMap = new ConcurrentHashMap<>();

    public @Nullable RandomState getOrCreate(final long seed, final AetheriaLayer.Type layerType, final HolderGetter<NormalNoise.NoiseParameters> noiseParametersGetter) {
        if (!(layerType.generator() instanceof NoiseBasedChunkGenerator noiseBasedGenerator)) {
            return null;
        }
        final Map<AetheriaLayer.Type, RandomState> cache = this.cacheMap.computeIfAbsent(seed, s -> new ConcurrentHashMap<>());
        return cache.computeIfAbsent(
                layerType,
                t -> RandomState.create(noiseBasedGenerator.settings.value(), noiseParametersGetter, seed)
        );
    }
}

package net.azisaba.aetheria.world.height;

import net.azisaba.aetheria.world.AetheriaLayer;
import net.azisaba.aetheria.world.AetheriaLayout;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.jspecify.annotations.NullMarked;

@NullMarked
public abstract class HeightContext implements HeightmapContext {
    public static HeightContext vanilla(final WorldGenLevel level) {
        return new WorldGenLevelBased(level);
    }

    public static HeightContext vanilla(final StructureManager structureManager) {
        return new WorldGenLevelBased(structureManager.level.getMinecraftWorld());
    }

    public static HeightContext vanilla(final ChunkGenerator chunkGenerator) {
        return new ChunkGeneratorBased(chunkGenerator);
    }

    public static HeightContext vanilla(final Structure.GenerationContext generationContext) {
        return new ChunkGeneratorBased(generationContext.chunkGenerator());
    }

    public static HeightContext aetheria(final AetheriaLayout layout, final AetheriaLayer.Type layerType) {
        return new LayerBased(layout, layerType);
    }

    private final HeightmapSet heightmap;

    public HeightContext(HeightmapSet heightmapSet) {
        this.heightmap = heightmapSet;
    }

    public abstract int seaLevel();

    public abstract int y(final int y1);

    @Override
    public Heightmap.Types worldSurfaceWg() {
        return this.heightmap.worldSurfaceWg();
    }

    @Override
    public Heightmap.Types worldSurface() {
        return this.heightmap.worldSurface();
    }

    @Override
    public Heightmap.Types oceanFloorWg() {
        return this.heightmap.oceanFloorWg();
    }

    @Override
    public Heightmap.Types oceanFloor() {
        return this.heightmap.oceanFloor();
    }

    @Override
    public Heightmap.Types motionBlocking() {
        return this.heightmap.motionBlocking();
    }

    @Override
    public Heightmap.Types motionBlockingNoLeaves() {
        return this.heightmap.motionBlockingNoLeaves();
    }

    @NullMarked
    static class WorldGenLevelBased extends HeightContext {
        private final WorldGenLevel worldGenRegion;

        public WorldGenLevelBased(final WorldGenLevel level) {
            super(HeightmapSet.VANILLA);
            this.worldGenRegion = level;
        }

        @Override
        public int seaLevel() {
            return this.worldGenRegion.getSeaLevel();
        }

        @Override
        public int y(final int y1) {
            return y1;
        }
    }

    @NullMarked
    static class ChunkGeneratorBased extends HeightContext {
        private final ChunkGenerator chunkGenerator;

        public ChunkGeneratorBased(final ChunkGenerator chunkGenerator) {
            super(HeightmapSet.VANILLA);
            this.chunkGenerator = chunkGenerator;
        }

        @Override
        public int seaLevel() {
            return this.chunkGenerator.getSeaLevel();
        }

        @Override
        public int y(final int y1) {
            return y1;
        }
    }

    @NullMarked
    static class LayerBased extends HeightContext {
        private final AetheriaLayout layout;
        private final AetheriaLayer.Type layerType;

        public LayerBased(final AetheriaLayout layout, final AetheriaLayer.Type layerType) {
            super(layerType.heightmapSet());
            this.layout = layout;
            this.layerType = layerType;
        }

        @Override
        public int seaLevel() {
            return this.layerType.generator().getSeaLevel();
        }

        @Override
        public int y(int y1) {
            return this.layout.toBlockY(this.layerType, y1);
        }
    }
}

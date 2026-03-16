package net.azisaba.vanilife.server.world.height;

import net.azisaba.vanilife.server.world.resource.ResourceLayer;
import net.azisaba.vanilife.server.world.resource.ResourceLayout;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.NullMarked;

@NullMarked
public abstract class HeightContext implements HeightmapContext {
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

    public static class Vanilla extends HeightContext {
        private final WorldGenLevel worldGenRegion;

        public Vanilla(final WorldGenLevel level) {
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

    public static class Layered extends HeightContext {
        private final ResourceLayout layout;
        private final ResourceLayer.Type layerType;

        public Layered(final ResourceLayout layout, final ResourceLayer.Type layerType) {
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

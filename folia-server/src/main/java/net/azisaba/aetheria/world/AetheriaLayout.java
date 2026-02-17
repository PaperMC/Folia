package net.azisaba.aetheria.world;

import net.minecraft.world.level.LevelHeightAccessor;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@NullMarked
public record AetheriaLayout(
        int minY,
        List<AetheriaLayer.Type> layersTypes
) implements Iterable<AetheriaLayer.Type> {
    public int height() {
        return this.layersTypes.stream()
                .mapToInt(AetheriaLayer.Type::height)
                .sum();
    }

    public int getMinYOf(final AetheriaLayer.Type type) {
        final int index = this.layersTypes.indexOf(type);
        if (index == -1) {
            throw new IllegalStateException("Layer type not found: " + type);
        }
        return this.minY + this.layersTypes.subList(0, index)
                .stream()
                .mapToInt(AetheriaLayer.Type::height)
                .sum();
    }

    public int getMaxYOf(final AetheriaLayer.Type type) {
        return this.getMinYOf(type) + type.height() - 1;
    }

    public AetheriaLayer.@Nullable Type getLayerTypeAt(final int y) {
        return this.layersTypes.stream()
                .filter(t -> {
                    final int minY = this.getMinYOf(t);
                    final int maxY = this.getMaxYOf(t);
                    return y >= minY && y <= maxY;
                })
                .findFirst()
                .orElse(null);
    }

    public int toBlockY(final AetheriaLayer.Type type, final int layerY) {
        return this.getMinYOf(type) + layerY - type.minY();
    }

    public int toLayerY(final AetheriaLayer.Type type, final int blockY) {
        return blockY - this.getMinYOf(type) + type.minY();
    }

    public LevelHeightAccessor createHeightAccessor(final AetheriaLayer.Type type) {
        final int minY = this.getMinYOf(type);
        return LevelHeightAccessor.create(minY, type.height());
    }

    public Stream<AetheriaLayer.Type> stream() {
        return StreamSupport.stream(this.spliterator(), false);
    }

    @Override
    public Iterator<AetheriaLayer.Type> iterator() {
        return this.layersTypes.iterator();
    }
}

package net.azisaba.vanilife.server.world;

import net.azisaba.vanilife.world.IslandsWorld;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class IslandsWorldImpl extends CraftWorld implements IslandsWorld {
    public IslandsWorldImpl(
        final ServerLevel world,
        final @Nullable ChunkGenerator generator,
        final @Nullable BiomeProvider biomeProvider,
        final Environment environment
    ) {
        super(world, generator, biomeProvider, environment);
    }
}

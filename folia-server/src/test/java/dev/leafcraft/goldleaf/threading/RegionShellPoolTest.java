package dev.leafcraft.goldleaf.threading;

import io.papermc.paper.configuration.WorldConfiguration;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import io.papermc.paper.threadedregions.TickRegions;
import java.lang.reflect.Field;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionShellPoolTest {

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static ServerLevel mockLevel() {
        final ServerLevel level = Mockito.mock(ServerLevel.class);
        final WorldConfiguration worldConfig = Mockito.mock(WorldConfiguration.class);
        worldConfig.hopper = Mockito.mock(WorldConfiguration.Hopper.class);
        Mockito.when(level.paperConfig()).thenReturn(worldConfig);
        return level;
    }

    @Test
    void heavySubsystemsAreLazilyAllocatedAndResetClearsAllLazyFields() throws Exception {
        final ServerLevel level = mockLevel();
        final TickRegions.TickRegionData regionData = Mockito.mock(TickRegions.TickRegionData.class);
        final RegionizedWorldData data = new RegionizedWorldData(level, regionData);

        final Field wireHandlerField = RegionizedWorldData.class.getDeclaredField("wireHandler");
        final Field turboField = RegionizedWorldData.class.getDeclaredField("turbo");
        final Field explosionCacheField = RegionizedWorldData.class.getDeclaredField("explosionDensityCache");
        final Field pathCacheField = RegionizedWorldData.class.getDeclaredField("pathTypesByPosCache");
        wireHandlerField.setAccessible(true);
        turboField.setAccessible(true);
        explosionCacheField.setAccessible(true);
        pathCacheField.setAccessible(true);

        // Fresh shell must not eagerly allocate any of the four heavy subsystems
        assertNull(wireHandlerField.get(data));
        assertNull(turboField.get(data));
        assertNull(explosionCacheField.get(data));
        assertNull(pathCacheField.get(data));

        // Null-safe invalidation/clear must not force allocation
        data.clearExplosionDensityCache();
        data.invalidatePathTypeCache(BlockPos.ZERO);
        assertNull(explosionCacheField.get(data));
        assertNull(pathCacheField.get(data));

        // First access allocates on demand
        assertNotNull(data.getExplosionDensityCache());
        assertNotNull(data.getPathTypesByPosCache());
        assertNotNull(data.getWireHandler());
        assertNotNull(explosionCacheField.get(data));
        assertNotNull(pathCacheField.get(data));
        assertNotNull(wireHandlerField.get(data));

        // Mutate transient state before reset
        data.setRedstoneGameTime(999L);
        data.setHandlingTick(true);
        data.populating = true;
        data.captureBlockStates = true;
        data.captureTreeGeneration = true;
        data.currentPrimedTnt = 7;
        data.shouldSignal = false;
        data.catSpawnerNextTick = 42;
        data.patrolSpawnerNextTick = 43;
        data.phantomSpawnerNextTick = 44;

        assertTrue(data.canPool());
        data.resetForPool();

        // DSN-2 audit: all lazy references and mutable state must be reset to clean defaults
        assertNull(wireHandlerField.get(data));
        assertNull(turboField.get(data));
        assertNull(explosionCacheField.get(data));
        assertNull(pathCacheField.get(data));
        assertNull(data.regionData);
        assertNull(data.getTickData());
        assertEquals(1L, data.getRedstoneGameTime());
        assertFalse(data.isHandlingTick());
        assertFalse(data.populating);
        assertFalse(data.captureBlockStates);
        assertFalse(data.captureTreeGeneration);
        assertEquals(0, data.currentPrimedTnt);
        assertTrue(data.shouldSignal);
        assertEquals(0, data.catSpawnerNextTick);
        assertEquals(0, data.patrolSpawnerNextTick);
        assertEquals(0, data.phantomSpawnerNextTick);
    }

    @Test
    void poolReusesEmptyShellsRespectsCapAndRejectsNonEmptyShells() {
        final ServerLevel level = mockLevel();
        final TickRegions.TickRegionData region1 = Mockito.mock(TickRegions.TickRegionData.class);
        final TickRegions.TickRegionData region2 = Mockito.mock(TickRegions.TickRegionData.class);

        final RegionShellPool pool = new RegionShellPool(2);
        final RegionizedWorldData first = pool.acquireShell(level, region1);
        assertEquals(1L, pool.getPoolMisses());
        assertEquals(0L, pool.getPoolHits());

        assertTrue(pool.releaseShell(first));
        assertEquals(1, pool.getPooledCount());

        final RegionizedWorldData reused = pool.acquireShell(level, region2);
        assertSame(first, reused);
        assertSame(region2, reused.regionData);
        assertEquals(1L, pool.getPoolHits());
        assertEquals(0, pool.getPooledCount());

        // Cap enforcement: maxSize == 2
        final RegionizedWorldData s1 = new RegionizedWorldData(level, region1);
        final RegionizedWorldData s2 = new RegionizedWorldData(level, region1);
        final RegionizedWorldData s3 = new RegionizedWorldData(level, region1);
        assertTrue(pool.releaseShell(s1));
        assertTrue(pool.releaseShell(s2));
        assertFalse(pool.releaseShell(s3));
        assertEquals(2, pool.getPooledCount());

        // Non-empty shell must be rejected to prevent cross-region entity leakage
        final RegionizedWorldData nonEmpty = new RegionizedWorldData(level, region1);
        final Entity mockEntity = Mockito.mock(Entity.class);
        nonEmpty.addLoadedEntity(mockEntity);
        assertFalse(nonEmpty.canPool());
        assertFalse(pool.releaseShell(nonEmpty));
        assertEquals(1L, pool.getRejectedNonEmpty());

        final RegionizedWorldData popped1 = pool.acquireShell(level, region2);
        final RegionizedWorldData popped2 = pool.acquireShell(level, region2);
        assertNotSame(popped1, popped2);
        assertEquals(0, pool.getPooledCount());
    }
}

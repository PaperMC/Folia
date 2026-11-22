package dev.leafcraft.goldleaf.bot;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurateException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit, quota, and zero-leak lifecycle tests for {@link SyntheticPlayerService}
 * ({@code goldleaf-ADR-015}).
 */
class SyntheticPlayerServiceTest {

    @AfterEach
    void restoreConfig() {
        final GoldLeafConfig cfg = GoldLeafConfig.get();
        cfg.bots.enabled = false;
        cfg.bots.maxTotal = 16;
        cfg.bots.maxPerPlayer = 2;
        cfg.bots.allowChunkLoading = false;
        SyntheticPlayerService.global().shutdownAll();
    }

    @Test
    void disabledByDefaultRejectsSpawnFailClosed() {
        final GoldLeafConfig cfg = GoldLeafConfig.get();
        assertFalse(cfg.bots.enabled);
        final SyntheticPlayerService service = new SyntheticPlayerService();
        final SyntheticPlayerHandle.PositionSnapshot pos =
            new SyntheticPlayerHandle.PositionSnapshot("world", 0.5, 64.0, 0.5, 0.0f, 0.0f);

        assertThrows(
            IllegalStateException.class,
            () -> service.spawn("farm_bot", UUID.randomUUID(), pos, false)
        );
    }

    @Test
    void deterministicUuidAndQuotaEnforcementAndRegionDispatch() throws Exception {
        final GoldLeafConfig cfg = GoldLeafConfig.get();
        cfg.bots.enabled = true;
        cfg.bots.maxTotal = 3;
        cfg.bots.maxPerPlayer = 2;
        cfg.bots.allowChunkLoading = false;

        final SyntheticPlayerService service = new SyntheticPlayerService();
        final List<String> dispatchedRegions = new ArrayList<>();
        service.setRegionDispatcher((world, cx, cz, task) -> {
            dispatchedRegions.add(world + ":" + cx + "," + cz);
            task.run();
        });

        final UUID ownerA = UUID.randomUUID();
        final UUID ownerB = UUID.randomUUID();
        final SyntheticPlayerHandle.PositionSnapshot pos1 =
            new SyntheticPlayerHandle.PositionSnapshot("world", 16.5, 64.0, 32.5, 0.0f, 0.0f);

        assertEquals(
            SyntheticPlayerService.deterministicUuid("IronFarmBot"),
            SyntheticPlayerService.deterministicUuid("ironfarmbot")
        );
        assertNotEquals(
            SyntheticPlayerService.deterministicUuid("IronFarmBot"),
            SyntheticPlayerService.deterministicUuid("RaidFarmBot")
        );

        final SyntheticPlayerHandle bot1 = service.spawn("IronFarmBot", ownerA, pos1, true).get();
        assertTrue(bot1.isSyntheticPlayer());
        assertFalse(bot1.isChunkLoadingAllowed(), "Config allowChunkLoading=false must override request");
        assertTrue(dispatchedRegions.contains("world:1,2"));

        service.spawn("RaidFarmBot", ownerA, pos1, false).get();
        // Per-owner cap reached (2)
        assertThrows(IllegalStateException.class, () -> service.spawn("ThirdBotA", ownerA, pos1, false));

        // Second owner can spawn up to global cap (3)
        service.spawn("WitchBot", ownerB, pos1, false).get();
        assertThrows(IllegalStateException.class, () -> service.spawn("FourthGlobal", ownerB, pos1, false));

        // Action dispatch & cross-region teleport
        assertEquals(1, service.dispatchAction(bot1.uuid(), SyntheticPlayerAction.move(4.0, 0.0, -2.0)).get());
        assertEquals(20.5, bot1.positionSnapshot().x(), 0.001);

        final SyntheticPlayerHandle.PositionSnapshot netherPos =
            new SyntheticPlayerHandle.PositionSnapshot("world_nether", 64.0, 128.0, 64.0, 90.0f, 0.0f);
        service.teleport(bot1.uuid(), netherPos).get();
        assertEquals("world_nether", bot1.positionSnapshot().worldName());
        assertTrue(dispatchedRegions.contains("world_nether:4,4"));

        // Owner quit cleans up only ownerA's bots
        assertEquals(2, service.onOwnerQuit(ownerA));
        assertEquals(1, service.activeCount());
        assertEquals(1, service.shutdownAll());
        assertEquals(0, service.activeCount());
    }

    @Test
    void repeatedSpawnDespawnZeroLeakGate() throws Exception {
        final GoldLeafConfig cfg = GoldLeafConfig.get();
        cfg.bots.enabled = true;
        cfg.bots.maxTotal = 16;
        cfg.bots.maxPerPlayer = 8;

        final SyntheticPlayerService service = new SyntheticPlayerService();
        final AtomicInteger attached = new AtomicInteger();
        final AtomicInteger detached = new AtomicInteger();
        service.setLifecycleBridge(new SyntheticPlayerService.LifecycleBridge() {
            @Override
            public void onAttach(final SyntheticPlayerHandle handle) {
                attached.incrementAndGet();
            }

            @Override
            public void onTeleport(
                final SyntheticPlayerHandle handle,
                final SyntheticPlayerHandle.PositionSnapshot target
            ) {
            }

            @Override
            public void onDetach(final SyntheticPlayerHandle handle) {
                detached.incrementAndGet();
            }
        });

        final UUID owner = UUID.randomUUID();
        final SyntheticPlayerHandle.PositionSnapshot pos =
            new SyntheticPlayerHandle.PositionSnapshot("world", 0.0, 64.0, 0.0, 0.0f, 0.0f);

        for (int i = 0; i < 150; i++) {
            final SyntheticPlayerHandle h = service.spawn("bot_" + i, owner, pos, false).get();
            assertTrue(service.despawn(h.uuid()));
            assertFalse(service.despawn(h.uuid()), "Second despawn must be idempotent");
        }

        assertEquals(150, attached.get());
        assertEquals(150, detached.get());
        assertEquals(0, service.activeCount());
    }

    @Test
    void invalidBotConfigBoundsFailClosed() {
        final GoldLeafConfig cfg = new GoldLeafConfig();
        cfg.bots.maxTotal = 2;
        cfg.bots.maxPerPlayer = 4;
        assertThrows(ConfigurateException.class, cfg::validate);
    }
}

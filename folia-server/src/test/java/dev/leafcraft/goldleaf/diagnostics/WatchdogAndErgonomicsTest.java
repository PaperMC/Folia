package dev.leafcraft.goldleaf.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.leafcraft.goldleaf.config.HardwareEnvelope;
import dev.leafcraft.goldleaf.telemetry.ChunkActivityCounters;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Verification tests for {@code goldleaf-ADR-011}: synthetic region-stall watchdog context,
 * throwing-contributor fault isolation ({@code S-004}), zero-overhead idle region HUD, and
 * honest startup summary formatting.
 */
final class WatchdogAndErgonomicsTest {

    @AfterEach
    void cleanup() {
        WatchdogDiagnosticsRegistry.global().clear();
        RegionHealthSnapshot.global().clearSubscribers();
    }

    @Test
    void syntheticRegionStallEmitsIdentityPlayersHotChunksAndPluginAttribution() {
        final WatchdogDiagnosticsRegistry registry = new WatchdogDiagnosticsRegistry();
        registry.registerPluginHazard(new WatchdogDiagnosticsRegistry.PluginHazardAttribution(
            "LagPlugin", "com.example.lagplugin.", "UNSAFE_SYNC_IO"
        ));

        final StackTraceElement[] stack = new StackTraceElement[]{
            new StackTraceElement("com.example.lagplugin.SyncChunkLoader", "loadNow", "SyncChunkLoader.java", 42),
            new StackTraceElement("io.papermc.paper.threadedregions.TickRegionScheduler", "runTick", "TickRegionScheduler.java", 100)
        };
        final var ctx = new WatchdogContextContributor.StalledTickContext(
            "world", 10, -20, 6.25, "Region Scheduler Thread #1", false, stack,
            6, 14, -24, -16, 48
        );

        final ChunkActivityCounters hot = new ChunkActivityCounters();
        for (int i = 0; i < 50; i++) {
            hot.recordEntityTick();
            hot.recordRedstoneUpdate();
        }
        final ChunkActivityCounters cold = new ChunkActivityCounters();
        cold.recordEntityTick();

        final List<String> lines = registry.collectDiagnosticLines(
            ctx,
            List.of(
                new WatchdogDiagnosticsRegistry.PlayerLocationSnapshot("Alice", 160, 64, -320),
                new WatchdogDiagnosticsRegistry.PlayerLocationSnapshot("Bob", 170, 65, -310)
            ),
            List.of(
                WatchdogDiagnosticsRegistry.HotChunkSnapshot.fromCounters(11, -20, cold),
                WatchdogDiagnosticsRegistry.HotChunkSnapshot.fromCounters(10, -20, hot)
            )
        );

        final String joined = String.join("\n", lines);
        assertTrue(joined.contains("world='world'"), "Must emit world name");
        assertTrue(joined.contains("center=(10,-20)"), "Must emit center chunk");
        assertTrue(joined.contains("bounds=[(6,-24)..(14,-16)] loadedChunks=48"), "Must emit region bounds and loaded chunk count");
        assertTrue(joined.contains("Alice@(160,64,-320)"), "Must emit player snapshot");
        assertTrue(joined.contains("(10,-20)[ent=50"), "Must rank hottest ADR-006 chunk first");
        assertTrue(joined.contains("LagPlugin[UNSAFE_SYNC_IO]"), "Must attribute ADR-007 preflight plugin on stack");
    }

    @Test
    void throwingWatchdogContributorIsIsolatedWithoutBreakingOtherContributors() {
        final WatchdogDiagnosticsRegistry registry = new WatchdogDiagnosticsRegistry();
        registry.registerContributor(ctx -> {
            throw new RuntimeException("simulated contributor failure");
        });
        registry.registerContributor(ctx -> List.of("[Custom] survivor-line"));

        final var ctx = new WatchdogContextContributor.StalledTickContext(
            "world_nether", 0, 0, 5.5, "Region Scheduler Thread #0", false, null
        );
        final List<String> lines = registry.collectDiagnosticLines(ctx, List.of(), List.of());
        assertTrue(lines.stream().anyMatch(l -> l.contains("world='world_nether'")));
        assertTrue(lines.stream().anyMatch(l -> l.equals("[Custom] survivor-line")));
    }

    @Test
    void idleRegionHudHasZeroOverheadAndHonorsToggleIntervalAndQuitCleanup() {
        final RegionHealthSnapshot hud = new RegionHealthSnapshot();
        final UUID admin = UUID.randomUUID();
        final var metrics = new RegionHealthSnapshot.RegionMetrics(
            "world", 4, -8, 20.0, 12.5, 18.0, 22.0, 0.25, 64, 120, 3
        );

        assertFalse(hud.hasActiveHudSubscribers());
        assertNull(hud.maybeFormatHudForTick(admin, 20L, metrics));

        assertTrue(hud.toggleHud(admin));
        assertTrue(hud.hasActiveHudSubscribers());
        assertNull(hud.maybeFormatHudForTick(admin, 21L, metrics), "Must respect interval ticks");
        final String formatted = hud.maybeFormatHudForTick(admin, 40L, metrics);
        assertNotNull(formatted);
        assertTrue(formatted.contains("20.00 TPS"));
        assertTrue(formatted.contains("12.50ms"));

        hud.onPlayerQuit(admin);
        assertFalse(hud.hasActiveHudSubscribers());
        assertNull(hud.maybeFormatHudForTick(admin, 60L, metrics));
    }

    @Test
    void startupSummaryReportsThreadsOvercommitWarningStorageAndPreflight() {
        final HardwareEnvelope hw = HardwareEnvelope.of(6, 8L * 1024L * 1024L * 1024L);
        final String summary = GoldLeafStartupSummary.format(new GoldLeafStartupSummary.SummaryInput(
            hw, 12, 12, "lca", true, 14, 2, 1
        ));
        assertTrue(summary.contains("logicalCores=6"));
        assertTrue(summary.contains("WARN: configured threads exceed host core sweet spot"));
        assertTrue(summary.contains("format=lca [v3-Columnar Mode B LCD2]"));
        assertTrue(summary.contains("reversible MIT .lca <-> .mca CLI converter"));
        assertTrue(summary.contains("SAFE=14 WARN=2 HAZARD=1"));
        assertEquals(4, summary.split("\n").length);
    }

    @Test
    void goldLeafAdminCommandSupportsStatusHudAndRegionSafeAsyncTeleport() {
        final org.bukkit.command.Command cmd = GoldLeafCommands.createGoldLeafCommand();
        final org.bukkit.entity.Player player = org.mockito.Mockito.mock(org.bukkit.entity.Player.class);
        final org.bukkit.World world = org.mockito.Mockito.mock(org.bukkit.World.class);
        final UUID playerId = UUID.randomUUID();
        org.mockito.Mockito.when(player.hasPermission(GoldLeafCommands.PERM_HUD)).thenReturn(true);
        org.mockito.Mockito.when(player.getUniqueId()).thenReturn(playerId);
        org.mockito.Mockito.when(player.getWorld()).thenReturn(world);
        org.mockito.Mockito.when(player.teleportAsync(org.mockito.ArgumentMatchers.any(org.bukkit.Location.class)))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(Boolean.TRUE));

        final java.util.List<String> messages = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            messages.add(inv.getArgument(0, String.class));
            return null;
        }).when(player).sendMessage(org.mockito.ArgumentMatchers.anyString());

        // /goldleaf status (lock-free summary)
        assertTrue(cmd.execute(player, "goldleaf", new String[]{"status"}));
        assertTrue(messages.stream().anyMatch(m -> m.contains("GoldLeaf Status | cores=")));

        // /goldleaf tp valid coords uses teleportAsync (F-001..F-003, S-005)
        messages.clear();
        assertTrue(cmd.execute(player, "goldleaf", new String[]{"tp", "128.5", "70", "-64.25"}));
        org.mockito.Mockito.verify(player).teleportAsync(org.mockito.ArgumentMatchers.argThat(loc ->
            loc.getWorld() == world && loc.getX() == 128.5 && loc.getY() == 70.0 && loc.getZ() == -64.25
        ));
        assertTrue(messages.stream().anyMatch(m -> m.contains("Teleported asynchronously")));

        // /goldleaf tp rejects non-numeric and non-finite coordinates fail-closed (S-004)
        messages.clear();
        assertTrue(cmd.execute(player, "goldleaf", new String[]{"tp", "NaN", "64", "0"}));
        assertTrue(messages.stream().anyMatch(m -> m.contains("non-finite value rejected")));

        messages.clear();
        assertTrue(cmd.execute(player, "goldleaf", new String[]{"tp", "not-a-number", "64", "0"}));
        assertTrue(messages.stream().anyMatch(m -> m.contains("expected finite numbers")));

        // /goldleaf tp rejects unknown world fail-closed
        messages.clear();
        assertTrue(cmd.execute(player, "goldleaf", new String[]{"tp", "0", "64", "0", "nonexistent_world"}));
        assertTrue(messages.stream().anyMatch(m -> m.contains("Unknown world 'nonexistent_world'")));
    }
}

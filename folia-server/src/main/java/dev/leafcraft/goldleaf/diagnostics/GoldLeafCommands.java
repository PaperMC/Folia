package dev.leafcraft.goldleaf.diagnostics;

import dev.leafcraft.goldleaf.bot.SyntheticPlayerHandle;
import dev.leafcraft.goldleaf.bot.SyntheticPlayerService;
import dev.leafcraft.goldleaf.security.DecodeTimePacketGuard;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Built-in GoldLeaf administrative commands:
 * <ul>
 *   <li>{@code /goldleaf hud} — toggles the zero-idle-overhead live region health HUD
 *       ({@code goldleaf-ADR-011} §1.2).</li>
 *   <li>{@code /goldleaf status} — prints a lock-free operational summary ({@code S-005}).</li>
 *   <li>{@code /goldleaf tp <x> <y> <z> [world]} — region-safe async cross-region teleport
 *       via {@link Player#teleportAsync(Location)} ({@code F-001..F-003}, {@code goldleaf-ADR-011}).</li>
 *   <li>{@code /bot <spawn|despawn|list>} — manages opt-in region-safe synthetic players
 *       ({@code goldleaf-ADR-015}).</li>
 * </ul>
 */
public final class GoldLeafCommands {

    public static final String PERM_HUD = "goldleaf.hud";
    public static final String PERM_BOT = "goldleaf.bot";

    private GoldLeafCommands() {
    }

    public static Command createGoldLeafCommand() {
        return new GoldLeafAdminCommand();
    }

    public static Command createBotCommand() {
        return new BotAdminCommand();
    }

    private static final class GoldLeafAdminCommand extends Command {

        GoldLeafAdminCommand() {
            super(
                "goldleaf",
                "GoldLeaf diagnostics, live region HUD, and region-safe async teleport",
                "/goldleaf <hud|status|tp <x> <y> <z> [world]>",
                List.of()
            );
            setPermission(PERM_HUD);
        }

        @Override
        public boolean execute(final CommandSender sender, final String commandLabel, final String[] args) {
            if (!testPermission(sender)) {
                return true;
            }
            if (args.length == 0) {
                sender.sendMessage("Usage: /goldleaf <hud|status|tp <x> <y> <z> [world]>");
                return true;
            }
            final String sub = args[0].toLowerCase(Locale.ROOT);
            return switch (sub) {
                case "hud" -> handleHud(sender);
                case "status" -> handleStatus(sender);
                case "tp" -> handleAsyncTeleport(sender, args);
                default -> {
                    sender.sendMessage("Usage: /goldleaf <hud|status|tp <x> <y> <z> [world]>");
                    yield true;
                }
            };
        }

        private static boolean handleHud(final CommandSender sender) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can toggle the live region HUD.");
                return true;
            }
            final boolean enabled = RegionHealthSnapshot.global().toggleHud(player.getUniqueId());
            sender.sendMessage("GoldLeaf live region HUD " + (enabled ? "enabled." : "disabled."));
            return true;
        }

        private static boolean handleStatus(final CommandSender sender) {
            final int hudSubscribers = RegionHealthSnapshot.global().hasActiveHudSubscribers() ? 1 : 0;
            final int activeBots = SyntheticPlayerService.global().activeCount();
            final long packetRejects = DecodeTimePacketGuard.global().totalRejected();
            sender.sendMessage(
                "GoldLeaf Status | cores=" + Runtime.getRuntime().availableProcessors()
                    + " | hudActive=" + (hudSubscribers > 0)
                    + " | activeBots=" + activeBots
                    + " | packetGuardRejects=" + packetRejects
            );
            return true;
        }

        private static boolean handleAsyncTeleport(final CommandSender sender, final String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can run /goldleaf tp.");
                return true;
            }
            if (args.length < 4) {
                sender.sendMessage("Usage: /goldleaf tp <x> <y> <z> [world]");
                return true;
            }
            final double x;
            final double y;
            final double z;
            try {
                x = Double.parseDouble(args[1]);
                y = Double.parseDouble(args[2]);
                z = Double.parseDouble(args[3]);
            } catch (final NumberFormatException ex) {
                sender.sendMessage("Invalid coordinates for /goldleaf tp: expected finite numbers.");
                return true;
            }
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                sender.sendMessage("Invalid coordinates for /goldleaf tp: non-finite value rejected.");
                return true;
            }
            final World targetWorld;
            if (args.length >= 5) {
                targetWorld = Bukkit.getServer() != null ? Bukkit.getWorld(args[4]) : null;
                if (targetWorld == null) {
                    sender.sendMessage("Unknown world '" + args[4] + "' for /goldleaf tp.");
                    return true;
                }
            } else {
                targetWorld = player.getWorld();
            }
            final Location target = new Location(targetWorld, x, y, z);
            player.teleportAsync(target).whenComplete((ok, err) -> {
                if (err != null || !Boolean.TRUE.equals(ok)) {
                    sender.sendMessage("Async teleport failed for target (" + x + ", " + y + ", " + z + ").");
                } else {
                    sender.sendMessage("Teleported asynchronously to (" + x + ", " + y + ", " + z + ").");
                }
            });
            return true;
        }
    }

    private static final class BotAdminCommand extends Command {

        BotAdminCommand() {
            super("bot", "Manage GoldLeaf region-safe synthetic players (ADR-015)", "/bot <spawn|despawn|list> [name]", List.of());
            setPermission(PERM_BOT);
        }

        @Override
        public boolean execute(final CommandSender sender, final String commandLabel, final String[] args) {
            if (!testPermission(sender)) {
                return true;
            }
            if (args.length == 0) {
                sender.sendMessage("Usage: /bot <spawn <name>|despawn <name>|list>");
                return true;
            }
            final String sub = args[0].toLowerCase(Locale.ROOT);
            final SyntheticPlayerService service = SyntheticPlayerService.global();
            switch (sub) {
                case "list" -> {
                    final Collection<SyntheticPlayerHandle> handles = service.activeHandles();
                    if (handles.isEmpty()) {
                        sender.sendMessage("No active synthetic players.");
                        return true;
                    }
                    final StringBuilder sb = new StringBuilder("Active bots (").append(handles.size()).append("): ");
                    int i = 0;
                    for (final SyntheticPlayerHandle h : handles) {
                        if (i++ > 0) {
                            sb.append(", ");
                        }
                        sb.append(h.name()).append('@').append(h.positionSnapshot().worldName());
                    }
                    sender.sendMessage(sb.toString());
                    return true;
                }
                case "spawn" -> {
                    if (args.length < 2) {
                        sender.sendMessage("Usage: /bot spawn <name>");
                        return true;
                    }
                    final String name = args[1];
                    final UUID ownerUuid = sender instanceof Player p ? p.getUniqueId() : new UUID(0L, 0L);
                    final SyntheticPlayerHandle.PositionSnapshot pos;
                    if (sender instanceof Player p) {
                        final var loc = p.getLocation();
                        pos = new SyntheticPlayerHandle.PositionSnapshot(
                            loc.getWorld() != null ? loc.getWorld().getName() : "world",
                            loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch()
                        );
                    } else {
                        pos = new SyntheticPlayerHandle.PositionSnapshot("world", 0.0, 64.0, 0.0, 0.0f, 0.0f);
                    }
                    try {
                        service.spawn(name, ownerUuid, pos, false).whenComplete((handle, err) -> {
                            if (err != null) {
                                sender.sendMessage("Failed to spawn bot '" + name + "': " + err.getMessage());
                            } else {
                                sender.sendMessage("Spawned synthetic player '" + handle.name() + "'.");
                            }
                        });
                    } catch (final RuntimeException ex) {
                        sender.sendMessage("Cannot spawn bot '" + name + "': " + ex.getMessage());
                    }
                    return true;
                }
                case "despawn" -> {
                    if (args.length < 2) {
                        sender.sendMessage("Usage: /bot despawn <name>");
                        return true;
                    }
                    final String name = args[1];
                    final UUID uuid = SyntheticPlayerService.deterministicUuid(name);
                    if (service.despawn(uuid)) {
                        sender.sendMessage("Despawned synthetic player '" + name + "'.");
                    } else {
                        sender.sendMessage("No active synthetic player named '" + name + "'.");
                    }
                    return true;
                }
                default -> {
                    sender.sendMessage("Usage: /bot <spawn <name>|despawn <name>|list>");
                    return true;
                }
            }
        }
    }
}

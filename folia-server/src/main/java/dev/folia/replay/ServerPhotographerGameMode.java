package dev.folia.replay;

import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.GameType;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ServerPhotographerGameMode extends ServerPlayerGameMode {

    public ServerPhotographerGameMode(ServerPhotographer photographer) {
        super(photographer);
        super.setGameModeForPlayer(GameType.SPECTATOR, null);
    }

    @Override
    public boolean changeGameModeForPlayer(@NotNull GameType gameMode) {
        return false;
    }

    @Nullable
    @Override
    public PlayerGameModeChangeEvent changeGameModeForPlayer(@NotNull GameType gameMode, PlayerGameModeChangeEvent.@NotNull Cause cause, @Nullable Component cancelMessage) {
        return null;
    }

    @Override
    protected void setGameModeForPlayer(@NotNull GameType gameMode, @Nullable GameType previousGameMode) {
    }

    @Override
    public void tick() {
    }
}

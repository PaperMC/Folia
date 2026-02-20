package dev.folia.entity;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import dev.folia.replay.BukkitRecorderOption;

import java.util.Collection;
import java.util.UUID;

public interface PhotographerManager {
    @Nullable
    Photographer getPhotographer(@NotNull UUID uuid);

    @Nullable
    Photographer getPhotographer(@NotNull String id);

    @Nullable
    Photographer createPhotographer(@NotNull String id, @NotNull Location location);

    @Nullable
    Photographer createPhotographer(@NotNull String id, @NotNull Location location, @NotNull BukkitRecorderOption recorderOption);

    void removePhotographer(@NotNull String id);

    void removePhotographer(@NotNull UUID uuid);

    void removeAllPhotographers();

    /**
     * Returns all photographers currently on the server.
     *
     * @return unmodifiable collection of photographers, never null
     */
    @NotNull
    Collection<Photographer> getPhotographers();
}

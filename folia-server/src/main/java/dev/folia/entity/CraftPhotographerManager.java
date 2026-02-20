package dev.folia.entity;

import com.google.common.collect.Lists;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import dev.folia.replay.BukkitRecorderOption;
import dev.folia.replay.RecorderOption;
import dev.folia.replay.ServerPhotographer;

import java.util.Collection;
import java.util.Collections;
import java.util.UUID;

public class CraftPhotographerManager implements PhotographerManager {

    private final Collection<Photographer> photographerViews = Collections.unmodifiableList(Lists.transform(ServerPhotographer.getPhotographers(), ServerPhotographer::getBukkitPlayer));

    @Override
    public @Nullable Photographer getPhotographer(@NotNull UUID uuid) {
        ServerPhotographer photographer = ServerPhotographer.getPhotographer(uuid);
        if (photographer != null) {
            return photographer.getBukkitPlayer();
        }
        return null;
    }

    @Override
    public @Nullable Photographer getPhotographer(@NotNull String id) {
        ServerPhotographer photographer = ServerPhotographer.getPhotographer(id);
        if (photographer != null) {
            return photographer.getBukkitPlayer();
        }
        return null;
    }

    @Override
    public @Nullable Photographer createPhotographer(@NotNull String id, @NotNull Location location) {
        ServerPhotographer photographer = new ServerPhotographer.PhotographerCreateState(location, id, RecorderOption.createDefaultOption()).createSync();
        if (photographer != null) {
            return photographer.getBukkitPlayer();
        }
        return null;
    }

    @Override
    public @Nullable Photographer createPhotographer(@NotNull String id, @NotNull Location location, @NotNull BukkitRecorderOption recorderOption) {
        ServerPhotographer photographer = new ServerPhotographer.PhotographerCreateState(location, id, RecorderOption.createFromBukkit(recorderOption)).createSync();
        if (photographer != null) {
            return photographer.getBukkitPlayer();
        }
        return null;
    }

    @Override
    public void removePhotographer(@NotNull String id) {
        ServerPhotographer photographer = ServerPhotographer.getPhotographer(id);
        if (photographer != null) {
            photographer.remove(true);
        }
    }

    @Override
    public void removePhotographer(@NotNull UUID uuid) {
        ServerPhotographer photographer = ServerPhotographer.getPhotographer(uuid);
        if (photographer != null) {
            photographer.remove(true);
        }
    }

    @Override
    public void removeAllPhotographers() {
        for (ServerPhotographer photographer : ServerPhotographer.getPhotographers()) {
            photographer.remove(true);
        }
    }

    @Override
    public Collection<Photographer> getPhotographers() {
        return photographerViews;
    }
}

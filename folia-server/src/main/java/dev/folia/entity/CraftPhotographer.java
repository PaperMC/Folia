package dev.folia.entity;

import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import dev.folia.replay.ServerPhotographer;

import java.io.File;

public class CraftPhotographer extends CraftPlayer implements Photographer {

    public CraftPhotographer(CraftServer server, ServerPhotographer entity) {
        super(server, entity);
    }

    @Override
    public void stopRecording() {
        this.stopRecording(true);
    }

    @Override
    public void stopRecording(boolean async) {
        this.stopRecording(async, true);
    }

    @Override
    public void stopRecording(boolean async, boolean save) {
        this.getHandle().remove(async, save);
    }

    @Override
    public void pauseRecording() {
        this.getHandle().pauseRecording();
    }

    @Override
    public void resumeRecording() {
        this.getHandle().resumeRecording();
    }

    @Override
    public void setRecordFile(@NotNull File file) {
        this.getHandle().setSaveFile(file);
    }

    @Override
    public void setFollowPlayer(@Nullable Player player) {
        ServerPlayer serverPlayer = player != null ? ((CraftPlayer) player).getHandle() : null;
        this.getHandle().setFollowPlayer(serverPlayer);
    }

    @Override
    public @NotNull String getId() {
        return this.getHandle().createState.id;
    }

    @Override
    public ServerPhotographer getHandle() {
        return (ServerPhotographer) entity;
    }

    public void setHandle(final ServerPhotographer entity) {
        super.setHandle(entity);
    }

    @Override
    public String toString() {
        return "CraftPhotographer{" + "name=" + getName() + '}';
    }
}

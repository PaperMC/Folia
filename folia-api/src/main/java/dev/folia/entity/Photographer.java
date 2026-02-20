package dev.folia.entity;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;

public interface Photographer extends Player {

    @NotNull
    String getId();

    void setRecordFile(@NotNull File file);

    void stopRecording();

    void stopRecording(boolean async);

    void stopRecording(boolean async, boolean save);

    void pauseRecording();

    void resumeRecording();

    void setFollowPlayer(@Nullable Player player);
}

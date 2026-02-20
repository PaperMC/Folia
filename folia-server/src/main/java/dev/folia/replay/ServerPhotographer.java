package dev.folia.replay;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftWorld;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.folia.entity.CraftPhotographer;
import dev.folia.entity.Photographer;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

public class ServerPhotographer extends ServerPlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Folia-Photographer");
    private static final List<ServerPhotographer> photographers = new CopyOnWriteArrayList<>();

    public PhotographerCreateState createState;
    private ServerPlayer followPlayer;
    private Recorder recorder;
    private File saveFile;
    private Vec3 lastPos;
    /** 本地 tick 计数，用于节流 resetPosition/move（Folia 无全局 getTickCount） */
    private int tickCounter;

    private final ServerStatsCounter stats;

    private ServerPhotographer(MinecraftServer server, ServerLevel world, GameProfile profile) {
        super(server, world, profile, ClientInformation.createDefault());
        this.followPlayer = null;
        this.stats = new PhotographerStatsCounter(server);
        this.lastPos = this.position();
        try {
            Field gameModeField = ServerPlayer.class.getDeclaredField("gameMode");
            gameModeField.setAccessible(true);
            gameModeField.set(this, new ServerPhotographerGameMode(this));
        } catch (Exception e) {
            throw new RuntimeException("Failed to set photographer game mode", e);
        }
    }

    public static ServerPhotographer createPhotographer(@NotNull PhotographerCreateState state) throws IOException {
        if (!isCreateLegal(state.id)) {
            throw new IllegalArgumentException(state.id + " is a invalid photographer id");
        }

        MinecraftServer server = MinecraftServer.getServer();

        ServerLevel world = ((CraftWorld) state.loc.getWorld()).getHandle();
        GameProfile profile = new GameProfile(UUID.randomUUID(), state.id);

        ServerPhotographer photographer = new ServerPhotographer(server, world, profile);
        photographer.recorder = new Recorder(photographer, state.option, new File("replay", state.id));
        photographer.saveFile = new File("replay", state.id + ".mcpr");
        photographer.createState = state;

        photographer.recorder.start();
        MinecraftServer.getServer().getPlayerList().placeNewPhotographer(photographer.recorder, photographer, world);
        photographer.serverLevel().chunkSource.move(photographer);
        photographer.setInvisible(true);
        photographers.add(photographer);

        LOGGER.info("Photographer " + state.id + " created");

        return photographer;
    }

    @Override
    public void tick() {
        super.tick();
        super.doTick();

        tickCounter++;
        if (tickCounter % 10 == 0) {
            connection.resetPosition();
            this.serverLevel().chunkSource.move(this);
        }

        if (this.followPlayer != null) {
            if (this.getCamera() == this || this.getCamera().level() != this.level()) {
                this.getBukkitPlayer().teleportAsync(this.getCamera().getBukkitEntity().getLocation());
                this.setCamera(followPlayer);
            } else if (lastPos.distanceToSqr(this.position()) > 1024D) {
                this.getBukkitPlayer().teleportAsync(this.getCamera().getBukkitEntity().getLocation());
            }
        }

        lastPos = this.position();
    }

    @Override
    public void die(@NotNull DamageSource damageSource) {
        super.die(damageSource);
        remove(true);
    }

    @Override
    public boolean isInvulnerableTo(@NotNull ServerLevel world, @NotNull DamageSource damageSource) {
        return true;
    }

    @Override
    public boolean hurtServer(@NotNull ServerLevel world, @NotNull DamageSource source, float amount) {
        return false;
    }

    @Override
    public void setHealth(float health) {
    }

    @NotNull
    @Override
    public ServerStatsCounter getStats() {
        return stats;
    }

    public void remove(boolean async) {
        this.remove(async, true);
    }

    public void remove(boolean async, boolean save) {
        super.remove(RemovalReason.KILLED);
        photographers.remove(this);
        this.recorder.stop();
        this.server.getPlayerList().removePhotographer(this);

        LOGGER.info("Photographer " + createState.id + " removed");

        if (!recorder.isSaved()) {
            CompletableFuture<Void> future = recorder.saveRecording(saveFile, save);
            if (!async) {
                future.join();
            }
        }
    }

    public void setFollowPlayer(ServerPlayer followPlayer) {
        this.setCamera(followPlayer);
        this.followPlayer = followPlayer;
    }

    public void setSaveFile(File saveFile) {
        this.saveFile = saveFile;
    }

    public void pauseRecording() {
        this.recorder.pauseRecording();
    }

    public void resumeRecording() {
        this.recorder.resumeRecording();
    }

    public static ServerPhotographer getPhotographer(String id) {
        for (ServerPhotographer photographer : photographers) {
            if (photographer.createState.id.equals(id)) {
                return photographer;
            }
        }
        return null;
    }

    public static ServerPhotographer getPhotographer(UUID uuid) {
        for (ServerPhotographer photographer : photographers) {
            if (photographer.getUUID().equals(uuid)) {
                return photographer;
            }
        }
        return null;
    }

    public static List<ServerPhotographer> getPhotographers() {
        return photographers;
    }

    public Photographer getBukkitPlayer() {
        return getBukkitEntity();
    }

    @Override
    @NotNull
    public CraftPhotographer getBukkitEntity() {
        return (CraftPhotographer) super.getBukkitEntity();
    }

    public static boolean isCreateLegal(@NotNull String name) {
        if (!name.matches("^[a-zA-Z0-9_]{4,16}$")) {
            return false;
        }

        return Bukkit.getPlayerExact(name) == null && ServerPhotographer.getPhotographer(name) == null;
    }

    public static class PhotographerCreateState {

        public RecorderOption option;
        public Location loc;
        public final String id;

        public PhotographerCreateState(Location loc, String id, RecorderOption option) {
            this.loc = loc;
            this.id = id;
            this.option = option;
        }

        public ServerPhotographer createSync() {
            try {
                return createPhotographer(this);
            } catch (IOException e) {
                e.printStackTrace();
            }
            return null;
        }
    }
}

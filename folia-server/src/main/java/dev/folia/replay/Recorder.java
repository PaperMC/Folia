package dev.folia.replay;

import com.mojang.serialization.DynamicOps;
import io.netty.channel.local.LocalChannel;
import net.minecraft.SharedConstants;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ClientboundServerLinksPacket;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.network.protocol.configuration.ClientboundSelectKnownPacks;
import net.minecraft.network.protocol.configuration.ClientboundUpdateEnabledFeaturesPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.flag.FeatureFlags;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Recorder extends Connection {

    private static final Logger LOGGER = LoggerFactory.getLogger("Folia-Photographer");

    private final ReplayFile replayFile;
    private final ServerPhotographer photographer;
    private final RecorderOption recorderOption;
    private final RecordMetaData metaData;

    private final ExecutorService saveService = Executors.newSingleThreadExecutor();

    private boolean stopped = false;
    private boolean paused = false;
    private boolean resumeOnNextPacket = true;

    private long startTime;
    private long lastPacket;
    private long timeShift = 0;

    private boolean isSaved;
    private boolean isSaving;
    private ConnectionProtocol state = ConnectionProtocol.LOGIN;

    public Recorder(ServerPhotographer photographer, RecorderOption recorderOption, File replayFile) throws IOException {
        super(PacketFlow.CLIENTBOUND);

        this.photographer = photographer;
        this.recorderOption = recorderOption;
        this.metaData = new RecordMetaData();
        this.replayFile = new ReplayFile(replayFile);
        this.channel = new LocalChannel();
    }

    public void start() {
        startTime = System.currentTimeMillis();

        metaData.singleplayer = false;
        metaData.serverName = recorderOption.serverName;
        metaData.date = startTime;
        metaData.mcversion = SharedConstants.getCurrentVersion().getName();

        this.savePacket(new ClientboundLoginFinishedPacket(photographer.getGameProfile()), ConnectionProtocol.LOGIN);
        this.startConfiguration();

        if (recorderOption.forceWeather != null) {
            setWeather(recorderOption.forceWeather);
        }
    }

    public void startConfiguration() {
        this.state = ConnectionProtocol.CONFIGURATION;
        MinecraftServer server = MinecraftServer.getServer();

        this.savePacket(new ClientboundCustomPayloadPacket(new BrandPayload(server.getServerModName())), ConnectionProtocol.CONFIGURATION);
        this.savePacket(new ClientboundServerLinksPacket(server.serverLinks().untrust()), ConnectionProtocol.CONFIGURATION);
        this.savePacket(new ClientboundUpdateEnabledFeaturesPacket(FeatureFlags.REGISTRY.toNames(server.getWorldData().enabledFeatures())), ConnectionProtocol.CONFIGURATION);

        List<KnownPack> knownPackslist = server.getResourceManager().listPacks().flatMap((iresourcepack) -> iresourcepack.location().knownPackInfo().stream()).toList();
        this.savePacket(new ClientboundSelectKnownPacks(knownPackslist), ConnectionProtocol.CONFIGURATION);

        server.getServerResourcePack().ifPresent((info) -> this.savePacket(new ClientboundResourcePackPushPacket(
            info.id(), info.url(), info.hash(), info.isRequired(), Optional.ofNullable(info.prompt())
        )));

        LayeredRegistryAccess<RegistryLayer> layeredregistryaccess = server.registries();
        DynamicOps<Tag> dynamicOps = layeredregistryaccess.compositeAccess().createSerializationContext(NbtOps.INSTANCE);
        RegistrySynchronization.packRegistries(dynamicOps, layeredregistryaccess.getAccessFrom(RegistryLayer.WORLDGEN), Set.copyOf(knownPackslist),
            (key, entries) ->
                this.savePacket(new ClientboundRegistryDataPacket(key, entries), ConnectionProtocol.CONFIGURATION)
        );
        this.savePacket(new ClientboundUpdateTagsPacket(TagNetworkSerialization.serializeTagsToNetwork(layeredregistryaccess)), ConnectionProtocol.CONFIGURATION);

        this.savePacket(ClientboundFinishConfigurationPacket.INSTANCE, ConnectionProtocol.CONFIGURATION);
        state = ConnectionProtocol.PLAY;
    }

    @Override
    public void flushChannel() {
    }

    public void stop() {
        stopped = true;
    }

    public void pauseRecording() {
        resumeOnNextPacket = false;
        paused = true;
    }

    public void resumeRecording() {
        resumeOnNextPacket = true;
    }

    public void setWeather(RecorderOption.RecordWeather weather) {
        weather.getPackets().forEach(this::savePacket);
    }

    public long getRecordedTime() {
        final long base = System.currentTimeMillis() - startTime;
        return base - timeShift;
    }

    private synchronized long getCurrentTimeAndUpdate() {
        long now = getRecordedTime();
        if (paused) {
            if (resumeOnNextPacket) {
                paused = false;
            }
            timeShift += now - lastPacket;
            return lastPacket;
        }
        return lastPacket = now;
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public void send(@NotNull Packet<?> packet, @Nullable PacketSendListener callbacks, boolean flush) {
        if (!stopped) {
            if (packet instanceof BundlePacket<?> packet1) {
                packet1.subPackets().forEach(subPacket -> send(subPacket, null));
                return;
            }

            if (packet instanceof ClientboundAddEntityPacket packet1) {
                if (packet1.getType() == EntityType.PLAYER) {
                    metaData.players.add(packet1.getUUID());
                    saveMetadata();
                }
            }

            if (packet instanceof ClientboundDisconnectPacket) {
                return;
            }

            if (recorderOption.forceDayTime != -1 && packet instanceof ClientboundSetTimePacket packet1) {
                packet = new ClientboundSetTimePacket(packet1.dayTime(), recorderOption.forceDayTime, false);
            }

            if (recorderOption.forceWeather != null && packet instanceof ClientboundGameEventPacket packet1) {
                ClientboundGameEventPacket.Type type = packet1.getEvent();
                if (type == ClientboundGameEventPacket.START_RAINING || type == ClientboundGameEventPacket.STOP_RAINING || type == ClientboundGameEventPacket.RAIN_LEVEL_CHANGE || type == ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE) {
                    return;
                }
            }

            if (recorderOption.ignoreChat && (packet instanceof ClientboundSystemChatPacket || packet instanceof ClientboundPlayerChatPacket)) {
                return;
            }

            savePacket(packet);
        }
    }

    private void saveMetadata() {
        saveService.submit(() -> {
            try {
                replayFile.saveMetaData(metaData);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    private void savePacket(Packet<?> packet) {
        this.savePacket(packet, state);
    }

    private void savePacket(Packet<?> packet, final ConnectionProtocol protocol) {
        try {
            final long timestamp = getCurrentTimeAndUpdate();
            saveService.submit(() -> {
                try {
                    replayFile.savePacket(timestamp, packet, protocol);
                } catch (Exception e) {
                    LOGGER.error("Error saving packet", e);
                }
            });
        } catch (Exception e) {
            LOGGER.error("Error saving packet", e);
        }
    }

    public boolean isSaved() {
        return isSaved;
    }

    public CompletableFuture<Void> saveRecording(File dest, boolean save) {
        isSaved = true;
        if (!isSaving) {
            isSaving = true;
            metaData.duration = (int) lastPacket;
            return CompletableFuture.runAsync(() -> {
                saveMetadata();
                saveService.shutdown();
                boolean interrupted = false;
                try {
                    saveService.awaitTermination(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
                try {
                    if (save) {
                        replayFile.closeAndSave(dest);
                    } else {
                        replayFile.closeNotSave();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    throw new CompletionException(e);
                } finally {
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, runnable -> {
                final Thread thread = new Thread(runnable, "Recording file save thread");
                thread.start();
            });
        } else {
            LOGGER.warn("saveRecording() called twice");
            return CompletableFuture.supplyAsync(() -> {
                throw new IllegalStateException("saveRecording() called twice");
            });
        }
    }
}

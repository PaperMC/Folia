package dev.folia.replay;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class RecorderOption {

    public int recordDistance = -1;
    public String serverName = "Folia";
    public RecordWeather forceWeather = null;
    public int forceDayTime = -1;
    public boolean ignoreChat = false;

    @NotNull
    @Contract(" -> new")
    public static RecorderOption createDefaultOption() {
        return new RecorderOption();
    }

    @NotNull
    public static RecorderOption createFromBukkit(@NotNull BukkitRecorderOption bukkitRecorderOption) {
        RecorderOption recorderOption = new RecorderOption();
        recorderOption.serverName = bukkitRecorderOption.serverName;
        recorderOption.ignoreChat = bukkitRecorderOption.ignoreChat;
        recorderOption.forceDayTime = bukkitRecorderOption.forceDayTime;
        recorderOption.forceWeather = switch (bukkitRecorderOption.forceWeather) {
            case RAIN -> RecordWeather.RAIN;
            case CLEAR -> RecordWeather.CLEAR;
            case THUNDER -> RecordWeather.THUNDER;
            case NULL -> null;
        };
        return recorderOption;
    }

    public enum RecordWeather {
        CLEAR(new ClientboundGameEventPacket(ClientboundGameEventPacket.STOP_RAINING, 0), new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, 0), new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, 0)),
        RAIN(new ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 0), new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, 1), new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, 0)),
        THUNDER(new ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 0), new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, 1), new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, 1));

        private final List<Packet<?>> packets;

        RecordWeather(Packet<?>... packets) {
            this.packets = List.of(packets);
        }

        public List<Packet<?>> getPackets() {
            return packets;
        }
    }
}

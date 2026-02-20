package dev.folia.replay;

public class BukkitRecorderOption {

    public String serverName = "Folia";
    public BukkitRecordWeather forceWeather = BukkitRecordWeather.NULL;
    public int forceDayTime = -1;
    public boolean ignoreChat = false;

    public enum BukkitRecordWeather {
        CLEAR,
        RAIN,
        THUNDER,
        NULL
    }
}

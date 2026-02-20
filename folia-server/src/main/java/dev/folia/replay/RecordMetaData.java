package dev.folia.replay;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class RecordMetaData {

    public static final int CURRENT_FILE_FORMAT_VERSION = 14;

    public boolean singleplayer = false;
    public String serverName = "Folia";
    public int duration = 0;
    public long date;
    public String mcversion;
    public String fileFormat = "MCPR";
    public int fileFormatVersion;
    public int protocol;
    public String generator;
    public int selfId = -1;

    public Set<UUID> players = new HashSet<>();
}

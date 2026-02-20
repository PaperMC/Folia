package dev.folia.replay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.status.StatusProtocols;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.NotNull;
import dev.folia.util.UUIDSerializer;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ReplayFile {

    private static final String RECORDING_FILE = "recording.tmcpr";
    private static final String RECORDING_FILE_CRC32 = "recording.tmcpr.crc32";
    private static final String MARKER_FILE = "markers.json";
    private static final String META_FILE = "metaData.json";

    private static final Gson MARKER_GSON = new GsonBuilder().registerTypeAdapter(ReplayMarker.class, new ReplayMarker.Serializer()).create();
    private static final Gson META_GSON = new GsonBuilder().registerTypeAdapter(UUID.class, new UUIDSerializer()).create();

    private final File tmpDir;
    private final DataOutputStream packetStream;
    private final CRC32 crc32 = new CRC32();

    private final File markerFile;
    private final File metaFile;

    private final Map<ConnectionProtocol, ProtocolInfo<?>> protocols;

    public ReplayFile(@NotNull File name) throws IOException {
        this.tmpDir = new File(name.getParentFile(), name.getName() + ".tmp");
        if (tmpDir.exists()) {
            if (!ReplayFile.deleteDir(tmpDir)) {
                throw new IOException("Recording file " + name + " already exists!");
            }
        }

        if (!tmpDir.mkdirs()) {
            throw new IOException("Failed to create temp directory for recording " + tmpDir);
        }

        File packetFile = new File(tmpDir, RECORDING_FILE);
        this.metaFile = new File(tmpDir, META_FILE);
        this.markerFile = new File(tmpDir, MARKER_FILE);

        this.packetStream = new DataOutputStream(new DigestOutputStream(new BufferedOutputStream(new FileOutputStream(packetFile)), crc32));

        this.protocols = Map.of(
            ConnectionProtocol.STATUS, StatusProtocols.CLIENTBOUND,
            ConnectionProtocol.LOGIN, LoginProtocols.CLIENTBOUND,
            ConnectionProtocol.CONFIGURATION, ConfigurationProtocols.CLIENTBOUND,
            ConnectionProtocol.PLAY, GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(MinecraftServer.getServer().registryAccess()))
        );
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private byte @NotNull [] getPacketBytes(Packet packet, ConnectionProtocol state) {
        ProtocolInfo<?> protocol = this.protocols.get(state);
        if (protocol == null) {
            throw new IllegalArgumentException("Unknown protocol state " + state);
        }

        ByteBuf buf = Unpooled.buffer();
        protocol.codec().encode(buf, packet);

        buf.readerIndex(0);
        byte[] ret = new byte[buf.readableBytes()];
        buf.readBytes(ret);
        buf.release();
        return ret;
    }

    public void saveMarkers(List<ReplayMarker> markers) throws IOException {
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(markerFile), StandardCharsets.UTF_8)) {
            writer.write(MARKER_GSON.toJson(markers));
        }
    }

    public void saveMetaData(@NotNull RecordMetaData data) throws IOException {
        data.fileFormat = "MCPR";
        data.fileFormatVersion = RecordMetaData.CURRENT_FILE_FORMAT_VERSION;
        data.protocol = SharedConstants.getCurrentVersion().getProtocolVersion();
        data.generator = "replay-folia-" + SharedConstants.getCurrentVersion().getName();

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(metaFile), StandardCharsets.UTF_8)) {
            writer.write(META_GSON.toJson(data));
        }
    }

    public void savePacket(long timestamp, Packet<?> packet, ConnectionProtocol protocol) throws Exception {
        byte[] data = getPacketBytes(packet, protocol);
        packetStream.writeInt((int) timestamp);
        packetStream.writeInt(data.length);
        packetStream.write(data);
    }

    public synchronized void closeAndSave(File file) throws IOException {
        packetStream.close();

        String[] files = tmpDir.list();
        if (files == null) {
            return;
        }

        try (ZipOutputStream os = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            for (String fileName : files) {
                os.putNextEntry(new ZipEntry(fileName));
                File f = new File(tmpDir, fileName);
                copy(new FileInputStream(f), os);
            }

            os.putNextEntry(new ZipEntry(RECORDING_FILE_CRC32));
            Writer writer = new OutputStreamWriter(os);
            writer.write(Long.toString(crc32.getValue()));
            writer.flush();
        }

        for (String fileName : files) {
            File f = new File(tmpDir, fileName);
            Files.delete(f.toPath());
        }
        Files.delete(tmpDir.toPath());
    }

    public synchronized void closeNotSave() throws IOException {
        packetStream.close();

        String[] files = tmpDir.list();
        if (files == null) {
            return;
        }

        for (String fileName : files) {
            File f = new File(tmpDir, fileName);
            Files.delete(f.toPath());
        }
        Files.delete(tmpDir.toPath());
    }

    private void copy(@NotNull InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int len;
        while ((len = in.read(buffer)) > -1) {
            out.write(buffer, 0, len);
        }
        in.close();
    }

    private static boolean deleteDir(File dir) {
        if (dir == null || !dir.exists()) {
            return false;
        }

        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDir(file);
                } else {
                    if (!file.delete()) {
                        return false;
                    }
                }
            }
        }

        return dir.delete();
    }
}

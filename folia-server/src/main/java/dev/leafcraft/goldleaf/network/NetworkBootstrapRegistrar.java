package dev.leafcraft.goldleaf.network;

import ca.spottedleaf.moonrise.spi.ChunkLoadBackpressureSpi;
import dev.leafcraft.goldleaf.bot.SyntheticPlayerService;
import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import dev.leafcraft.goldleaf.diagnostics.RegionHealthSnapshot;
import dev.leafcraft.goldleaf.protocol.BuiltInTechnicalProtocolHandlers;
import dev.leafcraft.goldleaf.protocol.TechnicalProtocolCodecs;
import dev.leafcraft.goldleaf.protocol.TechnicalProtocolRegistry;
import dev.leafcraft.goldleaf.security.ChunkLoadBackpressurePolicy;
import dev.leafcraft.goldleaf.security.DecodeTimePacketGuard;
import dev.leafcraft.goldleaf.storage.LcaResidencyManager;
import io.papermc.paper.spi.PaperNetworkSpi;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Layer 2 bootstrap registrar for networking, {@code LCA_NET_V1} chunk wire streaming,
 * technical client protocols, chunk-load backpressure, and decode-time packet guards.
 */
public final class NetworkBootstrapRegistrar {

    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkBootstrapRegistrar.class);

    private NetworkBootstrapRegistrar() {
    }

    public static void register(final @Nullable GoldLeafConfig config) {
        if (BroadcastPacketEncoder.get() == null) {
            BroadcastPacketEncoder.register(SharedBroadcastPacketEncoder.createDefault());
        }
        if (OffTickChunkPacketController.get() == null) {
            OffTickChunkPacketController.register(new DefaultOffTickChunkPacketController());
        }
        if (config != null && config.network != null && config.network.lcaNetV1Enabled) {
            if (ChunkStreamInterceptor.get() == null) {
                ChunkStreamInterceptor.register(LcaNetWireEngine.fromConfig(
                    LcaResidencyManager.global().cacheForWorld(LcaResidencyManager.DEFAULT_WORLD_ID),
                    LcaResidencyManager.global(),
                    config.network,
                    0L,
                    null,
                    (connection, channel, wirePayload) -> {
                        connection.send(new ClientboundCustomPayloadPacket(
                            new DiscardedPayload(
                                Identifier.parse(channel),
                                wirePayload
                            )
                        ));
                        return true;
                    }
                ));
            }
        }
        BuiltInTechnicalProtocolHandlers.setPayloadSender((playerId, channelId, wirePayload) -> {
            final MinecraftServer server = MinecraftServer.getServer();
            if (server == null || server.getPlayerList() == null) {
                return false;
            }
            final ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null || player.connection == null) {
                return false;
            }
            player.connection.send(new ClientboundCustomPayloadPacket(
                new DiscardedPayload(
                    Identifier.parse(channelId),
                    wirePayload
                )
            ));
            return true;
        });
        BuiltInTechnicalProtocolHandlers.setPlayerStateProvider(playerId -> {
            final MinecraftServer server = MinecraftServer.getServer();
            if (server == null || server.getPlayerList() == null) {
                return null;
            }
            final ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                return null;
            }
            final ServerLevel level = player.level();
            final UUID worldUuid = (level != null && level.getWorld() != null) ? level.getWorld().getUID() : null;
            return new BuiltInTechnicalProtocolHandlers.PlayerProtocolState(
                worldUuid,
                player.getFoodData().getSaturationLevel(),
                player.getFoodData().exhaustionLevel
            );
        });
        BuiltInTechnicalProtocolHandlers.setServuxResponder((playerId, query) ->
            TechnicalProtocolCodecs.encodeServuxStructureQuery(query)
        );
        BuiltInTechnicalProtocolHandlers.setSyncmaticaResponder((playerId, header) ->
            TechnicalProtocolCodecs.encodeSyncmaticaHeader(header)
        );
        BuiltInTechnicalProtocolHandlers.setJadeResponder((playerId, req) ->
            TechnicalProtocolCodecs.encodeJadeInspectRequest(req)
        );
        BuiltInTechnicalProtocolHandlers.syncFromConfig(
            TechnicalProtocolRegistry.global(),
            config != null ? config.protocols : null
        );
        ChunkLoadBackpressureSpi.register(new ChunkLoadBackpressureSpi() {
            @Override
            public long evaluate(
                final UUID playerId,
                final int pending,
                final long baseMaxGens,
                final int sendDistance,
                final long nowNanos
            ) {
                return ChunkLoadBackpressurePolicy.get()
                    .evaluate(playerId, pending, baseMaxGens, sendDistance, nowNanos)
                    .maxGenerationsThisTick();
            }

            @Override
            public int evaluateSendDistance(
                final UUID playerId,
                final int pending,
                final long baseMaxGens,
                final int sendDistance,
                final long nowNanos
            ) {
                return ChunkLoadBackpressurePolicy.get()
                    .evaluate(playerId, pending, baseMaxGens, sendDistance, nowNanos)
                    .effectiveSendDistance();
            }

            @Override
            public void recordNewChunkGenerations(final UUID playerId, final int generatedCount, final long nowNanos) {
                ChunkLoadBackpressurePolicy.get().recordNewChunkGenerations(playerId, generatedCount, nowNanos);
            }
        });
        PaperNetworkSpi.register(new PaperNetworkSpi.Provider() {
            @Override
            public NbtAccounter createServerboundNbtAccounter() {
                return DecodeTimePacketGuard.createServerboundNbtAccounter();
            }

            @Override
            public boolean isEntityTrackerAllocationFreePurgeEnabled() {
                return GoldLeafConfig.get().network.entityTrackerAllocationFreePurge;
            }

            @Override
            public boolean tryBroadcast(
                final Entity entity,
                final Set<ServerPlayerConnection> seenBy,
                final Packet<? super ClientGamePacketListener> packet
            ) {
                final BroadcastPacketEncoder encoder = BroadcastPacketEncoder.get();
                return encoder != null && encoder.tryBroadcast(entity, seenBy, packet);
            }

            @Override
            public boolean trySendChunk(
                final ServerGamePacketListenerImpl connection,
                final ServerLevel level,
                final LevelChunk chunk,
                final boolean shouldModify
            ) {
                final ChunkStreamInterceptor streamInterceptor = ChunkStreamInterceptor.get();
                if (streamInterceptor != null
                    && streamInterceptor.supportsConnection(connection)
                    && streamInterceptor.trySendChunk(connection, level, chunk, shouldModify)) {
                    return true;
                }
                final OffTickChunkPacketController offTickController = OffTickChunkPacketController.get();
                return offTickController != null
                    && GoldLeafConfig.get().network.antiXrayOffTickEncoding
                    && offTickController.trySendOffTick(connection, level, chunk, shouldModify);
            }

            @Override
            public void dispatchTechnicalProtocol(
                final UUID playerId,
                final String channel,
                final byte[] data,
                final BiPredicate<UUID, String> permissionCheck
            ) {
                final TechnicalProtocolRegistry registry = TechnicalProtocolRegistry.global();
                if (!registry.isEmpty()) {
                    registry.dispatchIncoming(playerId, channel, data, permissionCheck);
                }
            }

            @Override
            public boolean tryHandleCustomPayload(
                final ServerGamePacketListenerImpl connection,
                final String channel,
                final byte[] data
            ) {
                if (connection == null || connection.player == null || channel == null || data == null) {
                    return false;
                }
                final ChunkStreamInterceptor interceptor = ChunkStreamInterceptor.get();
                if (!(interceptor instanceof LcaNetWireEngine engine)) {
                    return false;
                }
                if (LcaNetWireEngine.CHANNEL_HANDSHAKE.equals(channel)) {
                    final UUID playerId = connection.player.getUUID();
                    final LcaNetWireEngine.NegotiatedSession session = engine.handleClientHandshake(playerId, data);
                    final byte[] ackBytes = LcaNetWireEngine.buildHandshakeAckBytes(session);
                    connection.send(new ClientboundCustomPayloadPacket(
                        new DiscardedPayload(
                            Identifier.parse(LcaNetWireEngine.CHANNEL_HANDSHAKE),
                            ackBytes
                        )
                    ));
                    LOGGER.info(
                        "[GoldLeaf-LcaNet] Native LCA_NET_V1 handshake from {}: active={}, caps=0x{}, cacheKiB={}, parityHash=0x{}",
                        connection.player.getScoreboardName(),
                        session.lcaStreamActive(),
                        Integer.toHexString(session.activeCapabilities()),
                        session.negotiatedCacheBudgetKiB(),
                        Long.toHexString(session.registryParityHash64())
                    );
                    return true;
                }
                if (LcaNetWireEngine.CHANNEL_CHUNK_DIGEST.equals(channel)) {
                    return true;
                }
                return false;
            }

            @Override
            public void onPlayerJoin(final UUID playerId) {
                final TechnicalProtocolRegistry registry = TechnicalProtocolRegistry.global();
                if (!registry.isEmpty()) {
                    registry.onPlayerJoin(playerId);
                }
            }

            @Override
            public void onChannelRegister(final UUID playerId, final Set<String> channels) {
                final TechnicalProtocolRegistry registry = TechnicalProtocolRegistry.global();
                if (!registry.isEmpty() && channels != null) {
                    for (final String channel : channels) {
                        registry.onChannelRegister(playerId, channel);
                    }
                }
            }

            @Override
            public void onPlayerTick(final UUID playerId, final long tickCount) {
                final TechnicalProtocolRegistry registry = TechnicalProtocolRegistry.global();
                if (!registry.isEmpty()) {
                    registry.onPlayerTick(playerId);
                }
            }

            @Override
            public void onPlayerQuit(final UUID playerId) {
                final ChunkStreamInterceptor interceptor = ChunkStreamInterceptor.get();
                if (interceptor instanceof LcaNetWireEngine engine) {
                    engine.unregisterConnection(playerId);
                }
                TechnicalProtocolRegistry.global().onPlayerQuit(playerId);
                ChunkLoadBackpressurePolicy.get().onPlayerQuit(playerId);
                DecodeTimePacketGuard.global().onPlayerQuit(playerId);
                RegionHealthSnapshot.global().onPlayerQuit(playerId);
                SyntheticPlayerService.global().onOwnerQuit(playerId);
            }

            @Override
            public int validateBookEdit(final UUID playerId, final List<String> pages) {
                final DecodeTimePacketGuard.ValidationDecision decision = DecodeTimePacketGuard.global().validateBookPayload(
                    playerId,
                    pages,
                    System.currentTimeMillis() / 1000L
                );
                if (decision.accepted()) {
                    return 0;
                }
                return decision.shouldDisconnect() ? 2 : 1;
            }

            @Override
            public int validatePacketBurst(final UUID playerId, final String packetType) {
                final DecodeTimePacketGuard.ValidationDecision decision = DecodeTimePacketGuard.global().validatePacketBurst(
                    playerId,
                    packetType,
                    System.currentTimeMillis()
                );
                if (decision.accepted()) {
                    return 0;
                }
                return decision.shouldDisconnect() ? 2 : 1;
            }

            @Override
            public boolean isChatSigningDisabled() {
                final GoldLeafConfig cfg = GoldLeafConfig.get();
                return cfg != null && cfg.security != null && cfg.security.disableChatSigning;
            }
        });
    }
}

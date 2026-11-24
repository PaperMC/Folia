package dev.leafcraft.goldleaf;

import ca.spottedleaf.moonrise.spi.ChunkLoadBackpressureSpi;
import ca.spottedleaf.moonrise.spi.ChunkResidencySpi;
import ca.spottedleaf.moonrise.spi.DirectSectionSerializationSpi;
import ca.spottedleaf.moonrise.spi.RegionStorageSpi;
import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import dev.leafcraft.goldleaf.network.ChunkStreamInterceptor;
import dev.leafcraft.goldleaf.network.LcaNetWireEngine;
import io.papermc.paper.spi.PaperDiagnosticsSpi;
import io.papermc.paper.spi.PaperNetworkSpi;
import io.papermc.paper.spi.PaperTelemetrySpi;
import io.papermc.paper.spi.PaperThreadingSpi;
import io.papermc.paper.spi.PaperWorldgenSpi;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldLeafBootstrapTest {

    @AfterEach
    void resetAll() {
        PaperThreadingSpi.resetDefault();
        PaperDiagnosticsSpi.resetDefault();
        PaperNetworkSpi.resetDefault();
        PaperTelemetrySpi.resetDefault();
        PaperWorldgenSpi.resetDefault();
        RegionStorageSpi.resetDefault();
        DirectSectionSerializationSpi.resetDefault();
        ChunkResidencySpi.resetDefault();
        ChunkLoadBackpressureSpi.resetDefault();
        ChunkStreamInterceptor.resetDefault();
    }

    @Test
    void defaultBootstrapWiresLayer2ProvidersWithLcaDefault(@TempDir final Path configDir) throws Exception {
        final GoldLeafConfig cfg = GoldLeafBootstrap.init(configDir);
        assertTrue(GoldLeafBootstrap.isRegistered());
        assertEqualsFormat("lca", cfg.storage.format);

        assertNotNull(PaperThreadingSpi.get());
        assertNotNull(PaperDiagnosticsSpi.get());
        assertNotNull(PaperNetworkSpi.get());
        assertNotNull(PaperTelemetrySpi.get());
        assertNotNull(PaperWorldgenSpi.get());
        assertNotNull(RegionStorageSpi.getFactory());
        assertNotNull(ChunkLoadBackpressureSpi.get());

        // Default format=lca registers Tier 2 direct-section/residency and native LCA_NET_V1 wire engine
        assertNotNull(DirectSectionSerializationSpi.get());
        assertNotNull(ChunkResidencySpi.get());
        assertInstanceOf(LcaNetWireEngine.class, ChunkStreamInterceptor.get());
    }

    @Test
    void mcaFormatDisablesTier2DirectSerializerAndResidency() {
        final GoldLeafConfig cfg = GoldLeafConfig.defaults();
        cfg.storage.format = "mca";
        cfg.network.lcaNetV1Enabled = false;

        GoldLeafBootstrap.registerAllLayer2Providers(cfg);

        assertNull(DirectSectionSerializationSpi.get());
        assertNull(ChunkResidencySpi.get());
        assertNull(ChunkStreamInterceptor.get());
    }

    @Test
    void lcaAndWireConfigRegistersTier2DirectSerializerResidencyAndWireEngine() {
        final GoldLeafConfig cfg = GoldLeafConfig.defaults();
        cfg.storage.format = "lca";
        cfg.network.lcaNetV1Enabled = true;

        GoldLeafBootstrap.registerAllLayer2Providers(cfg);

        assertNotNull(DirectSectionSerializationSpi.get());
        assertNotNull(ChunkResidencySpi.get());
        assertInstanceOf(LcaNetWireEngine.class, ChunkStreamInterceptor.get());
    }

    private static void assertEqualsFormat(final String expected, final String actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}

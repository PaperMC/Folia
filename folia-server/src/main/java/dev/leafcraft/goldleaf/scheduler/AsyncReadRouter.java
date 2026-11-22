package dev.leafcraft.goldleaf.scheduler;

import java.util.Objects;

/**
 * Compatibility bridge to the Layer 1 {@code paper-api}
 * {@link io.papermc.paper.threadedregions.scheduler.AsyncReadRouter} SPI
 * ({@code goldleaf-ADR-002}).
 */
public interface AsyncReadRouter extends io.papermc.paper.threadedregions.scheduler.AsyncReadRouter {

    static io.papermc.paper.threadedregions.scheduler.AsyncReadRouter get() {
        return io.papermc.paper.threadedregions.scheduler.AsyncReadRouter.get();
    }

    static void register(final io.papermc.paper.threadedregions.scheduler.AsyncReadRouter router) {
        io.papermc.paper.threadedregions.scheduler.AsyncReadRouter.register(
            Objects.requireNonNull(router, "router")
        );
    }

    static void resetDefault() {
        io.papermc.paper.threadedregions.scheduler.AsyncReadRouter.resetDefault();
    }
}

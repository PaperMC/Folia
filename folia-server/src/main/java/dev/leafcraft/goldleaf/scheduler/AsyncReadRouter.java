package dev.leafcraft.goldleaf.scheduler;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Layer 1 generic async-first read/dispatch router SPI ({@code goldleaf-ADR-002}).
 *
 * <p>Decouples read-heavy subsystems and plugins from Folia's 20 TPS tick cadence by executing
 * read workloads off-tick and dispatching state-mutating continuations onto the target region or
 * global immediate executor. Ships with a stock Folia fallback ({@link DefaultAsyncReadRouter})
 * and allows Layer 2 extensions to register specialized cross-node routers.</p>
 */
public interface AsyncReadRouter {

    /**
     * Submits a read-only supplier to run asynchronously on {@code asyncExecutor}, failing closed
     * by propagating any exception onto the returned future.
     */
    <T> CompletableFuture<T> submitAsyncRead(Supplier<T> asyncReader, Executor asyncExecutor);

    /**
     * Executes {@code asyncReader} on {@code asyncExecutor}, then dispatches {@code regionConsumer}
     * onto {@code targetRegionExecutor} and completes the returned future with the read value once
     * the region continuation finishes.
     */
    <T> CompletableFuture<T> readAndDispatch(
        Supplier<T> asyncReader,
        Executor asyncExecutor,
        Consumer<T> regionConsumer,
        Executor targetRegionExecutor
    );

    static AsyncReadRouter get() {
        return Holder.current;
    }

    static void register(final AsyncReadRouter router) {
        Holder.current = Objects.requireNonNull(router, "router");
    }

    static void resetDefault() {
        Holder.current = DefaultAsyncReadRouter.INSTANCE;
    }

    final class Holder {
        private static volatile AsyncReadRouter current = DefaultAsyncReadRouter.INSTANCE;

        private Holder() {
        }
    }

    /**
     * Stock Folia fallback implementation using standard {@link CompletableFuture} stages.
     */
    final class DefaultAsyncReadRouter implements AsyncReadRouter {

        public static final DefaultAsyncReadRouter INSTANCE = new DefaultAsyncReadRouter();

        private DefaultAsyncReadRouter() {
        }

        @Override
        public <T> CompletableFuture<T> submitAsyncRead(
            final Supplier<T> asyncReader,
            final Executor asyncExecutor
        ) {
            Objects.requireNonNull(asyncReader, "asyncReader");
            Objects.requireNonNull(asyncExecutor, "asyncExecutor");
            return CompletableFuture.supplyAsync(asyncReader, asyncExecutor);
        }

        @Override
        public <T> CompletableFuture<T> readAndDispatch(
            final Supplier<T> asyncReader,
            final Executor asyncExecutor,
            final Consumer<T> regionConsumer,
            final Executor targetRegionExecutor
        ) {
            Objects.requireNonNull(regionConsumer, "regionConsumer");
            Objects.requireNonNull(targetRegionExecutor, "targetRegionExecutor");
            return this.submitAsyncRead(asyncReader, asyncExecutor).thenApplyAsync(value -> {
                regionConsumer.accept(value);
                return value;
            }, targetRegionExecutor);
        }
    }
}

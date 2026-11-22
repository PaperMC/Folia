package dev.leafcraft.goldleaf.scheduler;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncReadRouterTest {

    @AfterEach
    void restoreDefaultRouter() {
        AsyncReadRouter.resetDefault();
    }

    @Test
    void readAndDispatchRunsOffTickThenDispatchesToRegionExecutor() throws Exception {
        final ExecutorService asyncPool = Executors.newSingleThreadExecutor(r -> new Thread(r, "goldleaf-async-read"));
        final ExecutorService regionPool = Executors.newSingleThreadExecutor(r -> new Thread(r, "goldleaf-region-exec"));
        try {
            final AtomicReference<String> readThread = new AtomicReference<>();
            final AtomicReference<String> dispatchThread = new AtomicReference<>();

            final CompletableFuture<String> future = AsyncReadRouter.get().readAndDispatch(
                () -> {
                    readThread.set(Thread.currentThread().getName());
                    return "chunk-snapshot";
                },
                asyncPool,
                value -> dispatchThread.set(Thread.currentThread().getName() + ":" + value),
                regionPool
            );

            assertEquals("chunk-snapshot", future.get(5, TimeUnit.SECONDS));
            assertEquals("goldleaf-async-read", readThread.get());
            assertEquals("goldleaf-region-exec:chunk-snapshot", dispatchThread.get());
            assertNotEquals(readThread.get(), dispatchThread.get());
        } finally {
            asyncPool.shutdownNow();
            regionPool.shutdownNow();
        }
    }

    @Test
    void submitAsyncReadFailsClosedOnException() {
        final CompletableFuture<String> failed = AsyncReadRouter.get().submitAsyncRead(
            () -> {
                throw new IllegalStateException("io-fault");
            },
            Runnable::run
        );
        final CompletionException thrown = assertThrows(CompletionException.class, failed::join);
        assertTrue(thrown.getCause() instanceof IllegalStateException);
        assertEquals("io-fault", thrown.getCause().getMessage());
    }

    @Test
    void customRouterCanBeRegisteredAndReset() {
        final AsyncReadRouter custom = new AsyncReadRouter() {
            @Override
            public <T> CompletableFuture<T> submitAsyncRead(
                final java.util.function.Supplier<T> asyncReader,
                final java.util.concurrent.Executor asyncExecutor
            ) {
                return CompletableFuture.completedFuture(asyncReader.get());
            }

            @Override
            public <T> CompletableFuture<T> readAndDispatch(
                final java.util.function.Supplier<T> asyncReader,
                final java.util.concurrent.Executor asyncExecutor,
                final java.util.function.Consumer<T> regionConsumer,
                final java.util.concurrent.Executor targetRegionExecutor
            ) {
                final T val = asyncReader.get();
                regionConsumer.accept(val);
                return CompletableFuture.completedFuture(val);
            }
        };

        AsyncReadRouter.register(custom);
        assertSame(custom, AsyncReadRouter.get());

        AsyncReadRouter.resetDefault();
        assertSame(AsyncReadRouter.DefaultAsyncReadRouter.INSTANCE, AsyncReadRouter.get());
    }
}

package com.local.huaweicast;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Runs cleanup once on its owning thread, sharing completion across concurrent callers. */
final class ThreadBoundCleanup {
    private final BooleanSupplier isOwnerThread;
    private final Consumer<Runnable> dispatch;
    private final Runnable cleanup;
    private final AtomicBoolean requested = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final CountDownLatch finished = new CountDownLatch(1);

    ThreadBoundCleanup(BooleanSupplier isOwnerThread, Consumer<Runnable> dispatch, Runnable cleanup) {
        this.isOwnerThread = Objects.requireNonNull(isOwnerThread);
        this.dispatch = Objects.requireNonNull(dispatch);
        this.cleanup = Objects.requireNonNull(cleanup);
    }

    void release(long timeout, TimeUnit unit) {
        if (isOwnerThread.getAsBoolean()) {
            requested.set(true);
            runCleanup();
            return;
        }
        if (requested.compareAndSet(false, true)) {
            try {
                dispatch.accept(this::runCleanup);
            } catch (RuntimeException error) {
                // The owner may have already cleaned up and quit while dispatch was in flight.
                if (!started.get()) {
                    requested.set(false);
                    throw error;
                }
            }
        }
        try {
            finished.await(timeout, unit);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private void runCleanup() {
        if (!started.compareAndSet(false, true)) return;
        try {
            cleanup.run();
        } finally {
            finished.countDown();
        }
    }
}

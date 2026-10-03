package com.local.huaweicast;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class ThreadBoundCleanupTest {
    @Test public void repeatedReleaseSchedulesAndRunsCleanupOnce() {
        List<Runnable> queue = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ThreadBoundCleanup cleanup = new ThreadBoundCleanup(() -> false, queue::add, calls::incrementAndGet);

        cleanup.release(0, TimeUnit.MILLISECONDS);
        cleanup.release(0, TimeUnit.MILLISECONDS);
        assertEquals(1, queue.size());
        queue.get(0).run();
        cleanup.release(0, TimeUnit.MILLISECONDS);
        assertEquals(1, calls.get());
        assertEquals(1, queue.size());
    }

    @Test public void concurrentCallersWaitForTheSameCleanup() throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(2);
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicInteger dispatched = new AtomicInteger();
        CountDownLatch scheduled = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        ThreadBoundCleanup cleanup = new ThreadBoundCleanup(() -> false, action -> {
            dispatched.incrementAndGet();
            queued.set(action);
            scheduled.countDown();
        }, () -> {});
        try {
            Future<?> first = callers.submit(() -> cleanup.release(5, TimeUnit.SECONDS));
            assertTrue(scheduled.await(1, TimeUnit.SECONDS));
            Future<?> second = callers.submit(() -> {
                secondStarted.countDown();
                cleanup.release(5, TimeUnit.SECONDS);
            });
            assertTrue(secondStarted.await(1, TimeUnit.SECONDS));
            queued.get().run();
            first.get(1, TimeUnit.SECONDS);
            second.get(1, TimeUnit.SECONDS);
            assertEquals(1, dispatched.get());
        } finally {
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test public void ownerThreadCleansUpDirectlyWithoutWaitingForItself() {
        AtomicInteger calls = new AtomicInteger();
        ThreadBoundCleanup cleanup = new ThreadBoundCleanup(() -> true,
            action -> fail("Owner must not enqueue its own cleanup"), calls::incrementAndGet);
        cleanup.release(5, TimeUnit.SECONDS);
        cleanup.release(5, TimeUnit.SECONDS);
        assertEquals(1, calls.get());
    }

    @Test public void initializationFailureCanCleanUpBeforeQueuedStop() {
        AtomicBoolean owner = new AtomicBoolean();
        List<Runnable> queue = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ThreadBoundCleanup cleanup = new ThreadBoundCleanup(owner::get, queue::add, calls::incrementAndGet);
        cleanup.release(0, TimeUnit.MILLISECONDS);
        owner.set(true);
        cleanup.release(5, TimeUnit.SECONDS);
        queue.get(0).run();
        assertEquals(1, calls.get());
    }

    @Test public void interruptedCallerStillSchedulesCleanupAndPreservesInterrupt() {
        List<Runnable> queue = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ThreadBoundCleanup cleanup = new ThreadBoundCleanup(() -> false, queue::add, calls::incrementAndGet);
        try {
            Thread.currentThread().interrupt();
            cleanup.release(5, TimeUnit.SECONDS);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, queue.size());
        } finally {
            Thread.interrupted();
        }
        queue.get(0).run();
        cleanup.release(0, TimeUnit.MILLISECONDS);
        assertEquals(1, calls.get());
    }

    @Test public void cleanupFailureStillSignalsCompletionAndIsNotRetried() {
        List<Runnable> queue = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("release failed");
        ThreadBoundCleanup cleanup = new ThreadBoundCleanup(() -> false, queue::add, () -> {
            calls.incrementAndGet();
            throw failure;
        });
        cleanup.release(0, TimeUnit.MILLISECONDS);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> queue.get(0).run()));
        cleanup.release(0, TimeUnit.MILLISECONDS);
        assertEquals(1, calls.get());
        assertEquals(1, queue.size());
    }

    @Test public void rejectedDispatchDoesNotPreventRetry() {
        AtomicBoolean reject = new AtomicBoolean(true);
        AtomicInteger calls = new AtomicInteger();
        ThreadBoundCleanup cleanup = new ThreadBoundCleanup(() -> false, action -> {
            if (reject.get()) throw new IllegalStateException("Unavailable executor");
            action.run();
        }, calls::incrementAndGet);
        assertThrows(IllegalStateException.class, () -> cleanup.release(0, TimeUnit.MILLISECONDS));
        reject.set(false);
        cleanup.release(0, TimeUnit.MILLISECONDS);
        assertEquals(1, calls.get());
    }

    @Test public void ownerCleanupWinningDispatchRaceDoesNotFailRelease() {
        AtomicBoolean owner = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<ThreadBoundCleanup> reference = new AtomicReference<>();
        ThreadBoundCleanup cleanup = new ThreadBoundCleanup(owner::get, action -> {
            owner.set(true);
            reference.get().release(0, TimeUnit.MILLISECONDS);
            owner.set(false);
            throw new IllegalStateException("Owner has already quit");
        }, calls::incrementAndGet);
        reference.set(cleanup);
        cleanup.release(0, TimeUnit.MILLISECONDS);
        cleanup.release(0, TimeUnit.MILLISECONDS);
        assertEquals(1, calls.get());
    }
}

package com.local.huaweicast;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Orders this process's DLNA screen-mirroring commands, including final receiver cleanup. */
final class DlnaPlaybackSession implements AutoCloseable {
    interface Commands {
        void setUri(String uri, String metadata) throws Exception;
        void play() throws Exception;
        void stop() throws Exception;
    }

    static final class ControlQueue {
        final ScheduledThreadPoolExecutor executor;
        // Accessed only on the control thread. MediaService is not part of this ownership map.
        final Map<String, DlnaPlaybackSession> owners = new HashMap<>();

        ControlQueue() {
            executor = new ScheduledThreadPoolExecutor(1, task -> {
                Thread thread = new Thread(task, "dlna-mirror-control");
                thread.setDaemon(true);
                return thread;
            });
            executor.setRemoveOnCancelPolicy(true);
        }
    }

    private static final ControlQueue SHARED_QUEUE = new ControlQueue();
    private final ControlQueue queue;
    private final String endpoint;
    private final Commands commands;
    private final Consumer<Exception> onFailure;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object pollingLock = new Object();
    private ScheduledFuture<?> polling;

    DlnaPlaybackSession(String endpoint, Commands commands, Consumer<Exception> onFailure) {
        this(SHARED_QUEUE, endpoint, commands, onFailure);
    }

    DlnaPlaybackSession(ControlQueue queue, String endpoint, Commands commands,
                        Consumer<Exception> onFailure) {
        this.queue = Objects.requireNonNull(queue);
        this.endpoint = Objects.requireNonNull(endpoint);
        this.commands = Objects.requireNonNull(commands);
        this.onFailure = Objects.requireNonNull(onFailure);
    }

    void start(String uri, String metadata, Runnable onStarted) {
        if (closed.get() || !started.compareAndSet(false, true)) return;
        queue.executor.execute(() -> {
            if (closed.get()) return;
            DlnaPlaybackSession previous = queue.owners.put(endpoint, this);
            if (previous != null) previous.cancelPolling();
            try {
                commands.setUri(uri, metadata);
                if (closed.get()) return;
                commands.play();
                if (!closed.get()) onStarted.run();
            } catch (Exception error) {
                if (!closed.get()) {
                    close();
                    onFailure.accept(error);
                }
            }
        });
    }

    void poll(Runnable action, long initialDelay, long delay, TimeUnit unit) {
        synchronized (pollingLock) {
            if (closed.get() || polling != null) return;
            polling = queue.executor.scheduleWithFixedDelay(() -> {
                if (!closed.get() && queue.owners.get(endpoint) == this) action.run();
            }, initialDelay, delay, unit);
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        cancelPolling();
        // Do not interrupt an in-flight SOAP command: its Stop must follow its completion.
        queue.executor.execute(() -> {
            if (queue.owners.get(endpoint) != this) return;
            queue.owners.remove(endpoint);
            try { commands.stop(); } catch (Exception ignored) { }
        });
    }

    private void cancelPolling() {
        synchronized (pollingLock) {
            if (polling != null) {
                polling.cancel(false);
                polling = null;
            }
        }
    }
}

package com.local.huaweicast;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DlnaPlaybackSessionTest {
    @Test public void stoppingBeforeStartDoesNotControlReceiver() throws Exception {
        try (Fixture f = new Fixture()) {
            Receiver receiver = f.receiver("one");
            DlnaPlaybackSession session = f.session("tv", receiver);
            session.close();
            session.start("uri", "metadata", receiver.started::countDown);
            f.drain();
            assertEquals(List.of(), f.events);
            assertEquals(0, f.ownerCount());
        }
    }

    @Test public void stoppingQueuedStartDoesNotControlReceiver() throws Exception {
        try (Fixture f = new Fixture(); Gate gate = new Gate()) {
            f.queue.executor.execute(gate::block);
            gate.awaitEntered();
            Receiver receiver = f.receiver("one");
            DlnaPlaybackSession session = f.session("tv", receiver);
            session.start("uri", "metadata", receiver.started::countDown);
            session.close();
            gate.close();
            f.drain();
            assertEquals(List.of(), f.events);
            assertEquals(0, f.ownerCount());
        }
    }

    @Test public void stoppingDuringSetUriSkipsPlayAndStopsAfterRequestReturns() throws Exception {
        try (Fixture f = new Fixture(); Gate gate = new Gate()) {
            Receiver receiver = f.receiver("one");
            receiver.onSetUri = gate::block;
            DlnaPlaybackSession session = f.session("tv", receiver);
            session.start("uri", "metadata", receiver.started::countDown);
            gate.awaitEntered();
            session.close();
            assertEquals(List.of("one:uri"), f.events);
            gate.close();
            await(receiver.stopped);
            f.drain();
            assertEquals(List.of("one:uri", "one:stop"), f.events);
            assertEquals(1, receiver.started.getCount());
            assertEquals(0, f.ownerCount());
        }
    }

    @Test public void stoppingDuringPlayFinishesPlayBeforeStopWithoutStartedCallback() throws Exception {
        try (Fixture f = new Fixture(); Gate gate = new Gate()) {
            Receiver receiver = f.receiver("one");
            receiver.onPlay = gate::block;
            DlnaPlaybackSession session = f.session("tv", receiver);
            session.start("uri", "metadata", receiver.started::countDown);
            gate.awaitEntered();
            session.close();
            assertEquals(List.of("one:uri", "one:play"), f.events);
            gate.close();
            await(receiver.stopped);
            assertEquals(List.of("one:uri", "one:play", "one:stop"), f.events);
            assertEquals(1, receiver.started.getCount());
        }
    }

    @Test public void repeatedStartAndStopAreIdempotent() throws Exception {
        try (Fixture f = new Fixture()) {
            Receiver receiver = f.receiver("one");
            DlnaPlaybackSession session = f.session("tv", receiver);
            session.start("uri", "metadata", receiver.started::countDown);
            await(receiver.started);
            session.start("another-uri", "another-metadata", receiver.started::countDown);
            session.close();
            session.close();
            session.close();
            f.drain();
            assertEquals(List.of("one:uri", "one:play", "one:stop"), f.events);
            assertEquals("uri", receiver.uri);
            assertEquals("metadata", receiver.metadata);
            assertEquals(0, f.ownerCount());
        }
    }

    @Test public void oldStopIsOrderedBeforeNewSessionStarts() throws Exception {
        try (Fixture f = new Fixture()) {
            Receiver first = f.receiver("one"), second = f.receiver("two");
            DlnaPlaybackSession old = f.session("tv", first), next = f.session("tv", second);
            old.start("old", "metadata", first.started::countDown);
            await(first.started);
            old.close();
            next.start("new", "metadata", second.started::countDown);
            await(second.started);
            assertEquals(List.of("one:uri", "one:play", "one:stop", "two:uri", "two:play"), f.events);
        }
    }

    @Test public void lateStopFromOldSessionDoesNotStopNewOwner() throws Exception {
        try (Fixture f = new Fixture(); Gate gate = new Gate()) {
            Receiver first = f.receiver("one"), second = f.receiver("two");
            DlnaPlaybackSession old = f.session("tv", first), next = f.session("tv", second);
            old.start("old", "metadata", first.started::countDown);
            await(first.started);
            f.queue.executor.execute(gate::block);
            gate.awaitEntered();
            next.start("new", "metadata", second.started::countDown);
            old.close();
            gate.close();
            await(second.started);
            f.drain();
            assertEquals(List.of("one:uri", "one:play", "two:uri", "two:play"), f.events);
            assertEquals(1, f.ownerCount());
            next.close();
            f.drain();
            assertEquals(0, f.ownerCount());
            assertEquals("two:stop", f.events.get(f.events.size() - 1));
        }
    }

    @Test public void differentReceiversRetainIndependentOwnership() throws Exception {
        try (Fixture f = new Fixture()) {
            Receiver first = f.receiver("one"), second = f.receiver("two");
            DlnaPlaybackSession old = f.session("tv-one", first), next = f.session("tv-two", second);
            old.start("old", "metadata", first.started::countDown);
            next.start("new", "metadata", second.started::countDown);
            await(second.started);
            assertEquals(2, f.ownerCount());
            old.close();
            f.drain();
            assertEquals(1, f.ownerCount());
            assertEquals("one:stop", f.events.get(f.events.size() - 1));
        }
    }

    @Test public void failedSetUriStillCleansUpWithoutPlay() throws Exception {
        try (Fixture f = new Fixture()) {
            Receiver receiver = f.receiver("one");
            receiver.onSetUri = () -> { throw new IOException("set URI failed"); };
            DlnaPlaybackSession session = f.session("tv", receiver);
            session.start("uri", "metadata", receiver.started::countDown);
            await(receiver.stopped);
            f.drain();
            assertEquals(List.of("one:uri", "one:stop"), f.events);
            assertEquals(1, f.failures.get());
            assertEquals(0, f.ownerCount());
        }
    }

    @Test public void failedPlayStillCleansUpWithoutStartedCallback() throws Exception {
        try (Fixture f = new Fixture()) {
            Receiver receiver = f.receiver("one");
            receiver.onPlay = () -> { throw new IOException("play failed"); };
            DlnaPlaybackSession session = f.session("tv", receiver);
            session.start("uri", "metadata", receiver.started::countDown);
            await(receiver.stopped);
            f.drain();
            assertEquals(List.of("one:uri", "one:play", "one:stop"), f.events);
            assertEquals(1, receiver.started.getCount());
            assertEquals(1, f.failures.get());
            assertEquals(0, f.ownerCount());
        }
    }

    @Test public void failedStopStillRemovesOwnership() throws Exception {
        try (Fixture f = new Fixture()) {
            Receiver receiver = f.receiver("one");
            receiver.onStop = () -> { throw new IOException("stop failed"); };
            DlnaPlaybackSession session = f.session("tv", receiver);
            session.start("uri", "metadata", receiver.started::countDown);
            await(receiver.started);
            session.close();
            f.drain();
            assertEquals(0, f.ownerCount());
            assertEquals(0, f.failures.get());
        }
    }

    @Test public void stoppingCancelsPollingWithoutInterruptingInFlightRequest() throws Exception {
        try (Fixture f = new Fixture(); Gate gate = new Gate()) {
            Receiver receiver = f.receiver("one");
            AtomicInteger polls = new AtomicInteger();
            DlnaPlaybackSession session = f.session("tv", receiver);
            session.start("uri", "metadata", () -> session.poll(() -> {
                polls.incrementAndGet();
                gate.block();
            }, 0, 1, TimeUnit.MILLISECONDS));
            gate.awaitEntered();
            session.close();
            assertEquals(0, gate.interruptions.get());
            gate.close();
            await(receiver.stopped);
            f.drain();
            assertEquals(1, polls.get());
            assertEquals(0, gate.interruptions.get());
            assertTrue(f.queue.executor.getQueue().isEmpty());
        }
    }

    @Test public void stoppedSessionCannotScheduleMorePolling() throws Exception {
        try (Fixture f = new Fixture()) {
            Receiver receiver = f.receiver("one");
            AtomicInteger polls = new AtomicInteger();
            DlnaPlaybackSession session = f.session("tv", receiver);
            session.close();
            session.poll(polls::incrementAndGet, 0, 1, TimeUnit.MILLISECONDS);
            f.drain();
            assertEquals(0, polls.get());
            assertTrue(f.queue.executor.getQueue().isEmpty());
        }
    }

    private interface Operation { void run() throws Exception; }

    private static final class Receiver implements DlnaPlaybackSession.Commands {
        final String name;
        final List<String> events;
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch stopped = new CountDownLatch(1);
        Operation onSetUri = () -> { }, onPlay = () -> { }, onStop = () -> { };
        String uri, metadata;

        Receiver(String name, List<String> events) { this.name = name; this.events = events; }

        @Override public void setUri(String uri, String metadata) throws Exception {
            this.uri = uri;
            this.metadata = metadata;
            events.add(name + ":uri");
            onSetUri.run();
        }

        @Override public void play() throws Exception { events.add(name + ":play"); onPlay.run(); }

        @Override public void stop() throws Exception {
            events.add(name + ":stop");
            try { onStop.run(); } finally { stopped.countDown(); }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final DlnaPlaybackSession.ControlQueue queue = new DlnaPlaybackSession.ControlQueue();
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final List<DlnaPlaybackSession> sessions = new ArrayList<>();
        final AtomicInteger failures = new AtomicInteger();

        Receiver receiver(String name) { return new Receiver(name, events); }

        DlnaPlaybackSession session(String endpoint, Receiver receiver) {
            DlnaPlaybackSession session = new DlnaPlaybackSession(queue, endpoint, receiver, error -> failures.incrementAndGet());
            sessions.add(session);
            return session;
        }

        void drain() throws Exception { queue.executor.submit(() -> { }).get(3, TimeUnit.SECONDS); }

        int ownerCount() throws Exception { return queue.executor.submit(queue.owners::size).get(3, TimeUnit.SECONDS); }

        @Override public void close() throws Exception {
            sessions.forEach(DlnaPlaybackSession::close);
            queue.executor.shutdown();
            if (!queue.executor.awaitTermination(3, TimeUnit.SECONDS)) {
                queue.executor.shutdownNow();
                throw new AssertionError("control queue did not stop");
            }
        }
    }

    private static final class Gate implements AutoCloseable {
        final CountDownLatch entered = new CountDownLatch(1), released = new CountDownLatch(1);
        final AtomicInteger interruptions = new AtomicInteger();

        void block() {
            entered.countDown();
            try { await(released); }
            catch (InterruptedException error) { interruptions.incrementAndGet(); Thread.currentThread().interrupt(); }
        }

        void awaitEntered() throws InterruptedException { await(entered); }
        @Override public void close() { released.countDown(); }
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue("timed out waiting for control operation", latch.await(3, TimeUnit.SECONDS));
    }
}

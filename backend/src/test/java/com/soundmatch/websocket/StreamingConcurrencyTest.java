package com.soundmatch.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StreamingConcurrencyTest {
    AudioWindowAssembler.Window window(long sequence) {
        return new AudioWindowAssembler.Window(sequence, (sequence - 1) * 240000, (sequence + 1) * 240000,
                48000, new byte[4], System.nanoTime());
    }
    ThreadPoolExecutor pool() { return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1)); }
    AudioSession session(String id, SimpleMeterRegistry metrics) {
        return new AudioSession(AudioSessionTest.socket(id), new ObjectMapper(), AudioSessionTest.config(Map.of()), metrics);
    }
    @Test void latestPendingOnlyAndStaleSuppression() throws Exception {
        var metrics = new SimpleMeterRegistry();
        var socket = AudioSessionTest.socket("s");
        var session = new AudioSession(socket, new ObjectMapper(), AudioSessionTest.config(Map.of()), metrics);
        var pool = pool();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var done = new CountDownLatch(1);
        var sequences = new CopyOnWriteArrayList<Long>();
        try {
            java.util.function.Consumer<AudioWindowAssembler.Window> processor = w -> {
                sequences.add(w.sequence());
                if (w.sequence() == 1) {
                    entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { throw new RuntimeException(e); }
                    session.sendIfCurrent(1, Map.of("type", "recommendations", "sequence", 1));
                } else {
                    session.sendIfCurrent(w.sequence(), Map.of("type", "recommendations", "sequence", w.sequence()));
                    done.countDown();
                }
            };
            session.offer(window(1), pool, processor);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            session.offer(window(2), pool, processor);
            session.offer(window(3), pool, processor);
            release.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(1L, 3L), sequences);
            verify(socket, times(1)).sendMessage(argThat(m -> m.getPayload().toString().contains("\"sequence\":3")));
            assertEquals(1, metrics.get("soundmatch.streaming.windows.dropped").tag("reason", "superseded").counter().count());
        } finally { release.countDown(); session.close(CloseStatus.NORMAL); pool.shutdownNow(); }
    }
    @Test void boundedGlobalQueueDisconnectCancellationAndIsolation() throws Exception {
        var metrics = new SimpleMeterRegistry();
        var pool = pool();
        var first = session("first", metrics);
        var second = session("second", metrics);
        var third = session("third", metrics);
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        try {
            first.offer(window(1), pool, w -> {
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException e) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            second.offer(window(1), pool, w -> fail("Cancelled queued work must not run"));
            third.offer(window(1), pool, w -> fail("Rejected work must not run"));
            assertEquals(1, pool.getQueue().size());
            assertFalse(third.hasWork());
            assertEquals(1, metrics.get("soundmatch.streaming.errors").tag("code", "OVERLOADED").counter().count());
            second.close(CloseStatus.NORMAL);
            assertEquals(0, pool.getQueue().size());
            assertFalse(first.closed());
            first.close(CloseStatus.NORMAL);
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertFalse(first.hasWork());
            assertFalse(second.hasWork());
        } finally { first.close(CloseStatus.NORMAL); second.close(CloseStatus.NORMAL); third.close(CloseStatus.NORMAL); pool.shutdownNow(); }
    }
    @Test void globallyQueuedWindowIsReplacedBeforeInference() throws Exception {
        var pool = pool();
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var received = new CompletableFuture<Long>();
        var session = session("queued", new SimpleMeterRegistry());
        try {
            pool.execute(() -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            session.offer(window(1), pool, w -> received.complete(w.sequence()));
            session.offer(window(2), pool, w -> received.complete(w.sequence()));
            release.countDown();
            assertEquals(2L, received.get(5, TimeUnit.SECONDS));
        } finally { release.countDown(); session.close(CloseStatus.NORMAL); pool.shutdownNow(); }
    }
}

package com.soundmatch.service;

import com.soundmatch.websocket.AudioSession;
import com.soundmatch.websocket.AudioWindowAssembler.Window;
import com.soundmatch.client.AiEngineClient;
import com.soundmatch.config.StreamingProperties;
import com.soundmatch.dto.StreamingDtos;
import com.soundmatch.exception.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Service
public class AudioStreamingService {
    private static final Logger log = LoggerFactory.getLogger(AudioStreamingService.class);
    private final AiEngineClient client;
    private final StreamingProperties config;
    private final MeterRegistry metrics;
    private final ThreadPoolExecutor executor;

    public AudioStreamingService(AiEngineClient client, StreamingProperties config, MeterRegistry metrics) {
        this.client = client; this.config = config; this.metrics = metrics;
        var ids = new AtomicInteger();
        executor = new ThreadPoolExecutor(config.workers(), config.workers(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.queueCapacity()), task -> {
                    var thread = new Thread(task, "pcm-inference-" + ids.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        metrics.gauge("soundmatch.streaming.jobs.active", executor, ThreadPoolExecutor::getActiveCount);
        metrics.gauge("soundmatch.streaming.queue.size", executor, e -> e.getQueue().size());
    }
    public void submit(AudioSession session, Window window) {
        metrics.counter("soundmatch.streaming.windows.assembled").increment();
        session.offer(window, executor, next -> process(session, next));
    }
    private void process(AudioSession session, Window window) {
        if (!session.current(window.sequence())) return;
        long started = System.nanoTime();
        long queueNanos = started - window.assembledNanos();
        metrics.timer("soundmatch.streaming.queue.wait").record(queueNanos, TimeUnit.NANOSECONDS);
        String requestId = UUID.randomUUID().toString();
        try (var requestContext = MDC.putCloseable("request_id", requestId);
             var sessionContext = MDC.putCloseable("session_id", session.id())) {
            session.sendIfCurrent(window.sequence(), new StreamingDtos.Processing("processing", window.sequence(),
                    window.startSeconds(), window.endSeconds()));
            if (!session.current(window.sequence())) return;
            var result = client.pcm(window.pcm(), window.sampleRate(), window.sequence(), session.id(), requestId,
                    config.recommendationLimit(), config.internalToken());
            session.sendIfCurrent(window.sequence(), new StreamingDtos.Result("recommendations", session.id(),
                    window.sequence(), window.startSeconds(), window.endSeconds(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), result.recommendations()));
        } catch (ApiException exception) {
            synchronized (session) {
                if (session.current(window.sequence())) session.error(exception.code(), exception.getMessage());
            }
        } catch (RuntimeException exception) {
            synchronized (session) {
                if (session.current(window.sequence())) session.error("INFERENCE_ERROR", "Error al procesar la ventana");
            }
        } finally {
            long elapsed = System.nanoTime() - started;
            metrics.timer("soundmatch.streaming.processing").record(elapsed, TimeUnit.NANOSECONDS);
            metrics.timer("soundmatch.streaming.total").record(System.nanoTime() - window.assembledNanos(), TimeUnit.NANOSECONDS);
            log.atInfo().addKeyValue("request_id", requestId).addKeyValue("session_id", session.id())
                    .addKeyValue("sequence", window.sequence()).addKeyValue("sample_rate", window.sampleRate())
                    .addKeyValue("window_start_seconds", window.startSeconds()).addKeyValue("window_end_seconds", window.endSeconds())
                    .addKeyValue("queue_ms", queueNanos / 1_000_000).addKeyValue("duration_ms", elapsed / 1_000_000)
                    .log("stream_inference");
        }
    }
    @PreDestroy public void shutdown() { executor.shutdownNow(); }
}

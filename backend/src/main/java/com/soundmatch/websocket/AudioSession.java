package com.soundmatch.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soundmatch.config.StreamingProperties;
import com.soundmatch.dto.StreamingDtos;
import com.soundmatch.exception.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/** All mutable session state is guarded by this instance. Never hold it during HTTP inference. */
public final class AudioSession {
    private static final Logger log = LoggerFactory.getLogger(AudioSession.class);
    private final String id = UUID.randomUUID().toString();
    private final WebSocketSession socket;
    private final ObjectMapper mapper;
    private final StreamingProperties config;
    private final MeterRegistry metrics;
    private final Instant startedAt = Instant.now();
    private final long startedNanos = System.nanoTime();
    private long lastActivity = startedNanos, tokenUpdated = startedNanos, latestSequence;
    private double tokens;
    private int sampleRate;
    private boolean closed;
    private AudioWindowAssembler assembler;
    private FutureTask<Void> active;
    private AudioWindowAssembler.Window pending;
    private ThreadPoolExecutor executor;

    public AudioSession(WebSocketSession socket, ObjectMapper mapper, StreamingProperties config, MeterRegistry metrics) {
        this.socket = new ConcurrentWebSocketSessionDecorator(socket, config.sendTimeoutMs(), config.sendBufferBytes());
        this.mapper = mapper;
        this.config = config;
        this.metrics = metrics;
    }
    public String id() { return id; }
    public Instant startedAt() { return startedAt; }
    public synchronized boolean closed() { return closed; }
    public synchronized boolean initialized() { return assembler != null; }
    public synchronized boolean hasWork() { return active != null || pending != null; }
    public synchronized long receivedSamples() { return assembler == null ? 0 : assembler.receivedSamples(); }
    public synchronized long latestSequence() { return latestSequence; }

    public synchronized void start(JsonNode message) {
        if (closed || assembler != null) throw invalid("INVALID_STATE", "La sesion ya fue iniciada o cerrada");
        JsonNode rate = message.path("sampleRate");
        if (!rate.isIntegralNumber() || !rate.canConvertToInt() || !config.sampleRates().contains(rate.intValue())
                || !message.path("channels").isIntegralNumber() || !message.path("channels").canConvertToInt()
                || message.path("channels").intValue() != 1
                || !"pcm_f32le".equals(message.path("format").asText())
                || !matches(message, "windowSeconds", config.windowSeconds())
                || !matches(message, "hopSeconds", config.hopSeconds()))
            throw invalid("INVALID_PARAMETERS", "Se requiere PCM float32 mono, frecuencia y ventanas autorizadas");
        sampleRate = rate.intValue();
        assembler = new AudioWindowAssembler(sampleRate, config.windowSeconds(), config.hopSeconds());
        tokens = sampleRate * config.burstSeconds();
        tokenUpdated = lastActivity = System.nanoTime();
        send(new StreamingDtos.Ready("ready", id, sampleRate, config.windowSeconds(), config.hopSeconds()));
    }
    private static boolean matches(JsonNode node, String key, int expected) {
        return !node.has(key) || (node.get(key).isIntegralNumber() && node.get(key).canConvertToInt()
                && node.get(key).intValue() == expected);
    }

    public synchronized void receive(ByteBuffer payload, Consumer<AudioWindowAssembler.Window> output) {
        if (closed || assembler == null) throw invalid("INVALID_STATE", "Enviar start antes del audio");
        int bytes = payload.remaining();
        if (bytes > config.maxBinaryBytes()) throw invalid("MESSAGE_TOO_LARGE", "Bloque PCM demasiado grande");
        if (bytes == 0 || bytes % Float.BYTES != 0) throw invalid("INVALID_AUDIO", "Longitud PCM invalida");
        var input = payload.slice().order(ByteOrder.LITTLE_ENDIAN);
        float[] samples = new float[bytes / Float.BYTES];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = input.getFloat();
            if (!Float.isFinite(samples[i]) || Math.abs(samples[i]) > 1.0f)
                throw invalid("INVALID_AUDIO", "Muestras PCM fuera de rango o no finitas");
        }
        long now = System.nanoTime();
        tokens = Math.min(sampleRate * config.burstSeconds(), tokens +
                (now - tokenUpdated) / 1e9 * sampleRate * config.audioRateLimit());
        tokenUpdated = now;
        if (samples.length > tokens) throw invalid("RATE_LIMIT", "Exceso de audio por segundo");
        tokens -= samples.length;
        lastActivity = now;
        assembler.append(samples, output);
    }

    /** One task and one replaceable pending window per session, including while globally queued. */
    public synchronized void offer(AudioWindowAssembler.Window window, ThreadPoolExecutor pool,
                                   Consumer<AudioWindowAssembler.Window> processor) {
        if (closed) return;
        latestSequence = window.sequence();
        if (pending != null) metrics.counter("soundmatch.streaming.windows.dropped", "reason", "superseded").increment();
        pending = window;
        if (active != null) return;
        executor = pool;
        schedule(pool, processor);
    }

    private void schedule(ThreadPoolExecutor pool, Consumer<AudioWindowAssembler.Window> processor) {
        active = new FutureTask<>(() -> {
            AudioWindowAssembler.Window next;
            synchronized (this) {
                if (closed || pending == null) return null;
                next = pending;
                pending = null;
            }
            try { processor.accept(next); }
            catch (RuntimeException exception) { error("INFERENCE_ERROR", "Error al procesar la ventana"); }
            return (Void) null;
        }) {
            @Override protected void done() {
                synchronized (AudioSession.this) {
                    active = null;
                    // Rejoin the bounded global queue so a slow session cannot monopolize a worker.
                    if (!closed && pending != null) schedule(pool, processor);
                }
            }
        };
        try {
            pool.execute(active);
        } catch (RejectedExecutionException exception) {
            active = null;
            pending = null;
            metrics.counter("soundmatch.streaming.windows.dropped", "reason", "queue_full").increment();
            error("OVERLOADED", "Cola de inferencia llena");
        }
    }

    public synchronized boolean current(long sequence) { return !closed && sequence == latestSequence; }
    public synchronized void sendIfCurrent(long sequence, Object message) {
        if (current(sequence)) send(message);
        else if (!closed) metrics.counter("soundmatch.streaming.windows.dropped", "reason", "stale_result").increment();
    }
    public synchronized void send(Object message) {
        if (closed || !socket.isOpen()) return;
        try { socket.sendMessage(new TextMessage(mapper.writeValueAsString(message))); }
        catch (IOException | RuntimeException exception) { close(CloseStatus.SERVER_ERROR); }
    }
    public synchronized void error(String code, String message) {
        if (closed) return;
        metrics.counter("soundmatch.streaming.errors", "code", code).increment();
        log.atWarn().addKeyValue("session_id", id).addKeyValue("error_type", code).log("stream_error");
        send(new StreamingDtos.Error("error", code, message));
    }
    public synchronized boolean expired(long now) {
        return now - startedNanos >= config.maxDuration().toNanos()
                || now - lastActivity >= config.idleTimeout().toNanos();
    }
    public synchronized void close(CloseStatus status) {
        if (closed) return;
        closed = true;
        pending = null;
        if (active != null) {
            var cancelled = active;
            cancelled.cancel(true);
            if (executor != null) executor.remove(cancelled);
            active = null;
        }
        if (assembler != null) { assembler.close(); assembler = null; }
        try { if (socket.isOpen()) socket.close(status); }
        catch (IOException | RuntimeException ignored) { /* State is already released. */ }
    }
    private static ApiException invalid(String code, String message) { return new ApiException(400, code, message); }
}

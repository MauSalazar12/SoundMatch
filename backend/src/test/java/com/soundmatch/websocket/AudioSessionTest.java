package com.soundmatch.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soundmatch.config.StreamingProperties;
import com.soundmatch.exception.ApiException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.web.socket.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AudioSessionTest {
    static StreamingProperties config(Map<String, Object> overrides) {
        var values = new HashMap<String, Object>();
        values.put("soundmatch.streaming.max-sessions", 32);
        overrides.forEach((key, value) -> values.put("soundmatch.streaming." + key, value));
        return new Binder(new MapConfigurationPropertySource(values)).bind("soundmatch.streaming",
                Bindable.of(StreamingProperties.class)).get();
    }
    static WebSocketSession socket(String id) {
        var socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(id);
        when(socket.isOpen()).thenReturn(true);
        when(socket.getAttributes()).thenReturn(new HashMap<>());
        return socket;
    }
    static final String START = """
        {"type":"start","sampleRate":48000,"channels":1,"format":"pcm_f32le","windowSeconds":10,"hopSeconds":5}
        """;
    final ObjectMapper mapper = new ObjectMapper();
    final WebSocketSession socket = socket("s1");
    final AudioSession session = new AudioSession(socket, mapper, config(Map.of()), new SimpleMeterRegistry());

    @Test void startAndLittleEndianAndRelease() throws Exception {
        session.start(mapper.readTree(START));
        verify(socket).sendMessage(argThat(message -> message.getPayload().toString().contains("\"type\":\"ready\"")));
        // LE .5f; BE interpretation would be outside normal intent but read the deterministic byte count.
        session.receive(ByteBuffer.wrap(new byte[]{0, 0, 0, 63}), w -> fail());
        assertEquals(1, session.receivedSamples());
        assertThrows(ApiException.class, () -> session.start(mapper.readTree(START)));
        session.close(CloseStatus.NORMAL);
        assertTrue(session.closed());
        assertFalse(session.initialized());
        assertEquals(0, session.receivedSamples());
        verify(socket).close(CloseStatus.NORMAL);
    }
    @ParameterizedTest @ValueSource(strings={"NaN", "Infinity", "-Infinity", "1.1"})
    void rejectsNonFiniteAndAmplitude(String value) throws Exception {
        session.start(mapper.readTree(START));
        var pcm = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.parseFloat(value)).flip();
        assertEquals("INVALID_AUDIO", assertThrows(ApiException.class, () -> session.receive(pcm, w -> fail())).code());
        assertEquals(0, session.receivedSamples());
    }
    @Test void stateSizeAlignmentAndRate() throws Exception {
        assertEquals("INVALID_STATE", assertThrows(ApiException.class,
                () -> session.receive(ByteBuffer.allocate(4), w -> fail())).code());
        session.start(mapper.readTree(START));
        for (int bytes : new int[]{0, 3, 65540}) assertThrows(ApiException.class,
                () -> session.receive(ByteBuffer.allocate(bytes), w -> fail()));
        for (int i = 0; i < 5; i++) session.receive(ByteBuffer.allocate(65536), w -> fail());
        assertEquals("RATE_LIMIT", assertThrows(ApiException.class,
                () -> session.receive(ByteBuffer.allocate(65536), w -> fail())).code());
    }
    @ParameterizedTest @ValueSource(strings={"{}", "{\"sampleRate\":8000}",
            "{\"sampleRate\":48000,\"channels\":2,\"format\":\"pcm_f32le\"}",
            "{\"sampleRate\":48000,\"channels\":1,\"format\":\"wav\"}",
            "{\"sampleRate\":48000,\"channels\":1,\"format\":\"pcm_f32le\",\"windowSeconds\":1}"})
    void invalidStart(String json) {
        assertThrows(ApiException.class, () -> session.start(mapper.readTree(json)));
        assertFalse(session.initialized());
    }
    @Test void admissionIsolationAndExpiry() throws Exception {
        var manager = new AudioSessionManager(config(Map.of("max-sessions", 1, "idle-timeout", "1ms")), mapper, new SimpleMeterRegistry());
        var first = manager.open(socket);
        assertNull(manager.open(socket("s2")));
        assertEquals(1, manager.size());
        Thread.sleep(5);
        manager.cleanup();
        assertEquals(0, manager.size());
        assertTrue(first.closed());
    }

    @Test void concurrentAdmissionNeverExceedsCap() throws Exception {
        var manager = new AudioSessionManager(config(Map.of("max-sessions", 2)), mapper, new SimpleMeterRegistry());
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var calls = new java.util.ArrayList<java.util.concurrent.Callable<AudioSession>>();
            for (int i = 0; i < 16; i++) {
                var socket = socket("parallel-" + i);
                calls.add(() -> manager.open(socket));
            }
            var accepted = new java.util.ArrayList<AudioSession>();
            for (var future : pool.invokeAll(calls)) if (future.get() != null) accepted.add(future.get());
            assertEquals(2, accepted.size());
            assertEquals(2, manager.size());
            assertNotEquals(accepted.get(0).id(), accepted.get(1).id());
        } finally { manager.shutdown(); pool.shutdownNow(); }
        assertEquals(0, manager.size());
    }

    @Test void buffersAndClocksAreIsolatedAndDurationExpires() throws Exception {
        var props = config(Map.of("burst-seconds", 10));
        var one = new AudioSession(socket("one"), mapper, props, new SimpleMeterRegistry());
        var two = new AudioSession(socket("two"), mapper, props, new SimpleMeterRegistry());
        one.start(mapper.readTree(START)); two.start(mapper.readTree(START));
        var first = new java.util.ArrayList<AudioWindowAssembler.Window>();
        var second = new java.util.ArrayList<AudioWindowAssembler.Window>();
        for (int block = 0; block < 30; block++) {
            var positive = ByteBuffer.allocate(64000).order(ByteOrder.LITTLE_ENDIAN);
            var negative = ByteBuffer.allocate(64000).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 16000; i++) { positive.putFloat(.25f); negative.putFloat(-.5f); }
            one.receive(positive.flip(), first::add);
            two.receive(negative.flip(), second::add);
        }
        assertEquals(1, first.size()); assertEquals(1, second.size());
        assertEquals(.25f, ByteBuffer.wrap(first.get(0).pcm()).order(ByteOrder.LITTLE_ENDIAN).getFloat());
        assertEquals(-.5f, ByteBuffer.wrap(second.get(0).pcm()).order(ByteOrder.LITTLE_ENDIAN).getFloat());
        one.close(CloseStatus.NORMAL);
        assertEquals(480000, two.receivedSamples());
        two.close(CloseStatus.NORMAL);
        var limited = new AudioSession(socket("short"), mapper, config(Map.of("max-duration", "1ms")), new SimpleMeterRegistry());
        Thread.sleep(5);
        assertTrue(limited.expired(System.nanoTime()));
        limited.close(CloseStatus.NORMAL);
    }
}

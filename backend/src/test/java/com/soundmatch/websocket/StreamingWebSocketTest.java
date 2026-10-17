package com.soundmatch.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StreamingWebSocketTest {
    static final HttpServer engine;
    static final List<byte[]> bodies = new CopyOnWriteArrayList<>();
    static final List<String> sequences = new CopyOnWriteArrayList<>();
    static final List<String> rates = new CopyOnWriteArrayList<>();
    static final List<String> tokens = new CopyOnWriteArrayList<>();
    static {
        try {
            engine = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            engine.createContext("/internal/v1/recommendations/pcm", exchange -> {
                bodies.add(exchange.getRequestBody().readAllBytes());
                sequences.add(exchange.getRequestHeaders().getFirst("X-Sequence"));
                rates.add(exchange.getRequestHeaders().getFirst("X-Sample-Rate"));
                tokens.add(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
                byte[] body = """
                    {"strategy":"mean_query_best_match","recommendations":[{"song_id":123,"title":"Example",
                    "artist":"Artist","similarity":0.89,"segment_index":0,"start_seconds":0,"end_seconds":10}]}
                    """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            engine.start();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("soundmatch.ai-engine.base-url", () -> "http://127.0.0.1:" + engine.getAddress().getPort());
        registry.add("soundmatch.streaming.internal-token", () -> "integration-token");
    }
    @AfterAll static void shutdown() { engine.stop(0); }
    @LocalServerPort int port;
    @Autowired AudioSessionManager sessions;
    final ObjectMapper mapper = new ObjectMapper();

    static class Listener implements WebSocket.Listener {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final StringBuilder text = new StringBuilder();
        @Override public void onOpen(WebSocket ws) { ws.request(1); }
        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            text.append(data);
            if (last) { messages.add(text.toString()); text.setLength(0); }
            ws.request(1);
            return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) { closed.complete(code); return null; }
        @Override public void onError(WebSocket ws, Throwable error) { closed.completeExceptionally(error); }
    }
    WebSocket connect(Listener listener, String origin) throws Exception {
        return HttpClient.newHttpClient().newWebSocketBuilder().header("Origin", origin)
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/listen"), listener).get(5, TimeUnit.SECONDS);
    }
    JsonNode next(Listener listener) throws Exception {
        var text = listener.messages.poll(5, TimeUnit.SECONDS);
        assertNotNull(text, "Expected WebSocket message");
        return mapper.readTree(text);
    }
    void released() throws Exception {
        for (int i = 0; i < 100 && sessions.size() != 0; i++) Thread.sleep(20);
        assertEquals(0, sessions.size());
    }
    @Test void realHandshakePcmHttpThreeWindowsResultsAndStop() throws Exception {
        bodies.clear(); sequences.clear(); rates.clear(); tokens.clear();
        var listener = new Listener();
        var ws = connect(listener, "http://localhost:5173");
        try {
            ws.sendText(AudioSessionTest.START, true).join();
            var ready = next(listener);
            assertEquals("ready", ready.path("type").asText());
            assertDoesNotThrow(() -> java.util.UUID.fromString(ready.path("sessionId").asText()));
            byte[] all = new byte[20 * 48000 * 4];
            var samples = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 20 * 48000; i++) samples.putFloat((float) (.1 * Math.sin(i * .03)));
            for (int block = 0; block < 200; block++) {
                ws.sendBinary(ByteBuffer.wrap(all, block * 19200, 19200), true).join();
                Thread.sleep(100); // Real-time pacing, no microphone and no weakened server limits.
            }
            for (int i = 1; i <= 3; i++) {
                var processing = next(listener);
                assertEquals("processing", processing.path("type").asText());
                assertEquals(i, processing.path("sequence").asInt());
                var result = next(listener);
                assertEquals("recommendations", result.path("type").asText());
                assertEquals(i, result.path("sequence").asInt());
                assertEquals((i - 1) * 5, result.path("windowStartSeconds").asInt());
                assertEquals((i - 1) * 5 + 10, result.path("windowEndSeconds").asInt());
                assertEquals(ready.path("sessionId"), result.path("sessionId"));
                assertEquals(123, result.path("recommendations").get(0).path("song_id").asInt());
                assertArrayEquals(Arrays.copyOfRange(all, (i - 1) * 960000, (i - 1) * 960000 + 1920000), bodies.get(i - 1));
            }
            assertEquals(List.of("1", "2", "3"), sequences);
            assertEquals(List.of("48000", "48000", "48000"), rates);
            assertEquals(List.of("integration-token", "integration-token", "integration-token"), tokens);
            ws.sendText("{\"type\":\"stop\"}", true).join();
            assertEquals(1000, listener.closed.get(5, TimeUnit.SECONDS));
            released();
        } finally { ws.abort(); }
    }
    @Test void forbiddenOriginFailsHandshake() {
        assertThrows(ExecutionException.class, () -> connect(new Listener(), "https://untrusted.example"));
    }
    @Test void invalidControlAndAudioBeforeStartCloseAndRelease() throws Exception {
        for (boolean binary : new boolean[]{false, true}) {
            var listener = new Listener();
            var ws = connect(listener, "http://localhost:5173");
            if (binary) ws.sendBinary(ByteBuffer.allocate(4), true).join();
            else ws.sendText("{invalid", true).join();
            assertEquals("error", next(listener).path("type").asText());
            assertEquals(1008, listener.closed.get(5, TimeUnit.SECONDS));
            released();
        }
    }
}

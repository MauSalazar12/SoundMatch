import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/** Java 17 source launcher: java scripts/StreamPcm.java [ws-url] [origin] [optional-20s-f32le-file] */
public class StreamPcm implements WebSocket.Listener {
    final BlockingQueue<String> messages = new ArrayBlockingQueue<>(64);
    final CompletableFuture<Integer> closed = new CompletableFuture<>();
    final StringBuilder partial = new StringBuilder();
    @Override public void onOpen(WebSocket ws) { ws.request(1); }
    @Override public CompletionStage<?> onText(WebSocket ws, CharSequence text, boolean last) {
        partial.append(text);
        if (last) { messages.add(partial.toString()); partial.setLength(0); }
        ws.request(1);
        return null;
    }
    @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
        closed.complete(code); return null;
    }
    @Override public void onError(WebSocket ws, Throwable error) { closed.completeExceptionally(error); }
    String next() throws Exception {
        for (int i = 0; i < 120; i++) {
            var message = messages.poll(1, TimeUnit.SECONDS);
            if (message != null) {
                System.out.println(message);
                if (has(message, "type", "error")) throw new IllegalStateException("Backend returned an error");
                return message;
            }
            if (closed.isDone()) throw new IllegalStateException("Socket closed: " + closed.get());
        }
        throw new TimeoutException("No response within 120 seconds");
    }
    static boolean has(String json, String key, String value) {
        return Pattern.compile("\"" + key + "\"\\s*:\\s*\"" + value + "\"").matcher(json).find();
    }
    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "ws://localhost:8080/ws/listen";
        String origin = args.length > 1 ? args[1] : "http://localhost:5173";
        byte[] audio;
        if (args.length > 2) {
            Path path = Path.of(args[2]);
            if (Files.size(path) != 3840000) throw new IllegalArgumentException("Expected exactly 20s mono float32 LE at 48000 Hz");
            audio = Files.readAllBytes(path);
        } else {
            var data = ByteBuffer.allocate(3840000).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 960000; i++) data.putFloat((float) (.1 * Math.sin(2 * Math.PI * 440 * i / 48000)));
            audio = data.array();
        }
        var listener = new StreamPcm();
        var ws = HttpClient.newHttpClient().newWebSocketBuilder().header("Origin", origin)
                .buildAsync(URI.create(url), listener).get(10, TimeUnit.SECONDS);
        try {
            ws.sendText("""
                {"type":"start","sampleRate":48000,"channels":1,"format":"pcm_f32le","windowSeconds":10,"hopSeconds":5}
                """, true).join();
            if (!has(listener.next(), "type", "ready")) throw new IllegalStateException("Expected ready");
            for (int block = 0; block < 200; block++) {
                ws.sendBinary(ByteBuffer.wrap(audio, block * 19200, 19200), true).get(5, TimeUnit.SECONDS);
                Thread.sleep(100);
            }
            while (true) {
                String message = listener.next();
                if (has(message, "type", "recommendations") &&
                        Pattern.compile("\"sequence\"\\s*:\\s*3(?:[,}])").matcher(message).find()) break;
            }
            ws.sendText("{\"type\":\"stop\"}", true).join();
            if (listener.closed.get(5, TimeUnit.SECONDS) != 1000) throw new IllegalStateException("Unexpected close status");
            System.out.println("Completed: final window received, normal stop acknowledged.");
        } finally { ws.abort(); }
    }
}

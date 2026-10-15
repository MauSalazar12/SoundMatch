package com.soundmatch.client;

import com.soundmatch.config.AiEngineProperties;
import com.soundmatch.config.HttpClientConfig;
import com.soundmatch.exception.ApiException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;

class AiEngineHttpTest {
    @Test void actualSocketTimeoutIsTranslated() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/songs/2", exchange -> {
            try { Thread.sleep(300); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try {
            var properties = new AiEngineProperties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    Duration.ofSeconds(1), Duration.ofMillis(50), 1000);
            var rest = HttpClientConfig.build(RestClient.builder(), properties, properties.readTimeout());
            var client = new AiEngineClient(rest, rest);
            assertEquals(504, assertThrows(ApiException.class, () -> client.song(2)).status());
        } finally {
            server.stop(0);
        }
    }
}

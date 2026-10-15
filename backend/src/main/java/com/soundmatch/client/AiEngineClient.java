package com.soundmatch.client;

import static com.soundmatch.dto.ApiDtos.*;
import com.soundmatch.exception.ApiException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class AiEngineClient {
    private static final Logger log = LoggerFactory.getLogger(AiEngineClient.class);
    private final RestClient rest;
    private final RestClient healthRest;

    public AiEngineClient(@Qualifier("aiRestClient") RestClient rest,
                          @Qualifier("aiHealthRestClient") RestClient healthRest) {
        this.rest = rest;
        this.healthRest = healthRest;
    }

    public SongPage songs(int page, int size, String title, String artist, String genre) {
        return call("songs", () -> rest.get().uri(builder -> {
            builder.path("/api/v1/songs").queryParam("page", page).queryParam("size", size);
            if (title != null) builder.queryParam("title", "{title}");
            if (artist != null) builder.queryParam("artist", "{artist}");
            if (genre != null) builder.queryParam("genre", "{genre}");
            var values = new java.util.HashMap<String, String>();
            if (title != null) values.put("title", title);
            if (artist != null) values.put("artist", artist);
            if (genre != null) values.put("genre", genre);
            return builder.build(values);
        }).retrieve().body(SongPage.class));
    }

    public Song song(long id) {
        return call("song", () -> rest.get().uri("/api/v1/songs/{id}", id).retrieve().body(Song.class));
    }

    public Recommendations recommendations(long id, int limit) {
        return call("recommendations", () -> rest.get()
                .uri("/api/v1/recommendations/{id}?limit={limit}", id, limit)
                .retrieve().body(Recommendations.class));
    }

    public Recommendations audio(byte[] data, int limit) {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", new ByteArrayResource(data) {
            @Override public String getFilename() { return "audio.upload"; }
        });
        return call("audio", () -> rest.post().uri("/api/v1/recommendations/audio?limit={limit}", limit)
                .contentType(MediaType.MULTIPART_FORM_DATA).body(body).retrieve().body(Recommendations.class));
    }

    public EngineHealth health() {
        return call("health", () -> healthRest.get().uri("/health").retrieve().body(EngineHealth.class));
    }

    private <T> T call(String operation, Supplier<T> action) {
        long start = System.nanoTime();
        try {
            T result = action.get();
            if (result == null) throw ApiException.upstream(503);
            return result;
        } catch (RestClientResponseException exception) {
            // Never reflect upstream stack traces, HTML, paths or credentials.
            throw ApiException.upstream(exception.getStatusCode().value());
        } catch (ResourceAccessException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                    throw ApiException.upstream(504);
                }
            }
            throw ApiException.upstream(503);
        } catch (RestClientException exception) {
            throw ApiException.upstream(503);
        } finally {
            log.atInfo().addKeyValue("operation", operation)
                    .addKeyValue("duration_ms", (System.nanoTime() - start) / 1_000_000).log("ai_engine_request");
        }
    }
}

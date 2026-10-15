package com.soundmatch.client;

import com.soundmatch.exception.ApiException;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AiEngineClientTest {
    MockRestServiceServer server;
    AiEngineClient client;

    @BeforeEach void setup() {
        var builder = RestClient.builder().baseUrl("http://engine");
        server = MockRestServiceServer.bindTo(builder).build();
        var rest = builder.build();
        client = new AiEngineClient(rest, rest);
    }

    @Test void deserializesSnakeCaseDtos() {
        server.expect(requestTo("http://engine/api/v1/recommendations/2?limit=5"))
                .andRespond(withSuccess("""
                  {"reference_song_id":2,"strategy":"segment_0_best_match","recommendations":[
                   {"song_id":5,"title":"This World","artist":"AWOL","genre":"Hip-Hop",
                    "similarity":0.91,"segment_index":1,"start_seconds":10,"end_seconds":20}]}
                  """, MediaType.APPLICATION_JSON));
        var response = client.recommendations(2, 5);
        assertEquals(2, response.referenceSongId());
        assertEquals(5, response.recommendations().get(0).songId());
        assertEquals(.91, response.recommendations().get(0).similarity());
        server.verify();
    }

    @ParameterizedTest @ValueSource(ints={400,404,413,415,500,503,504})
    void translatesUpstreamStatusAndHidesBody(int status) {
        server.expect(requestTo("http://engine/api/v1/songs/2"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).body("secret stack trace"));
        var error = assertThrows(ApiException.class, () -> client.song(2));
        assertEquals(status, error.status());
        assertFalse(error.getMessage().contains("secret"));
        server.verify();
    }

    @Test void unavailableMapsTo503() {
        server.expect(anything()).andRespond(withException(new IOException("connection refused")));
        assertEquals(503, assertThrows(ApiException.class, () -> client.song(2)).status());
        server.verify();
    }

    @Test void timeoutMapsTo504WithoutRetry() {
        server.expect(anything()).andRespond(withException(new HttpTimeoutException("timed out")));
        assertEquals(504, assertThrows(ApiException.class, () -> client.audio(new byte[]{1}, 5)).status());
        server.verify();
    }

    @Test void forwardsMultipart() {
        server.expect(requestTo("http://engine/api/v1/recommendations/audio?limit=5"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"file\"")))
                .andRespond(withSuccess("{\"strategy\":\"mean_query_best_match\",\"recommendations\":[]}", MediaType.APPLICATION_JSON));
        assertNotNull(client.audio(new byte[]{1,2,3}, 5));
        server.verify();
    }

    @Test void encodesFilterValues() {
        server.expect(requestTo("http://engine/api/v1/songs?page=0&size=20&title=A%2BB%20%26%20C"))
                .andRespond(withSuccess("{\"items\":[],\"page\":0,\"size\":20,\"total\":0}", MediaType.APPLICATION_JSON));
        assertEquals(0, client.songs(0, 20, "A+B & C", null, null).total());
        server.verify();
    }
}

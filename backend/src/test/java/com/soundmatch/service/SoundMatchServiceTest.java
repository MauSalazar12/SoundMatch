package com.soundmatch.service;

import com.soundmatch.client.AiEngineClient;
import com.soundmatch.config.AiEngineProperties;
import com.soundmatch.exception.ApiException;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SoundMatchServiceTest {
    @Test void rejectsEmptyAndOversizedFilesBeforeForwarding() {
        var client = mock(AiEngineClient.class);
        var service = new SoundMatchService(client, new AiEngineProperties(URI.create("http://localhost:8000"),
                Duration.ofSeconds(1), Duration.ofSeconds(2), 10));
        var empty = new MockMultipartFile("file", new byte[0]);
        var large = new MockMultipartFile("file", new byte[11]);
        assertEquals(400, assertThrows(ApiException.class, () -> service.audio(empty, 5)).status());
        assertEquals(413, assertThrows(ApiException.class, () -> service.audio(large, 5)).status());
        verifyNoInteractions(client);
    }
}

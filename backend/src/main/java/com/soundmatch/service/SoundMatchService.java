package com.soundmatch.service;

import static com.soundmatch.dto.ApiDtos.*;
import com.soundmatch.client.AiEngineClient;
import com.soundmatch.config.AiEngineProperties;
import com.soundmatch.exception.ApiException;
import java.io.IOException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class SoundMatchService {
    private final AiEngineClient client;
    private final AiEngineProperties properties;

    public SoundMatchService(AiEngineClient client, AiEngineProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public SongPage songs(int page, int size, String title, String artist, String genre) {
        return client.songs(page, size, title, artist, genre);
    }
    public Song song(long id) { return client.song(id); }
    public Recommendations recommendations(long id, int limit) { return client.recommendations(id, limit); }

    public Recommendations audio(MultipartFile file, int limit) {
        if (file.isEmpty()) throw new ApiException(400, "INVALID_AUDIO", "Archivo vacío");
        if (file.getSize() > properties.maxUploadBytes()) throw ApiException.upstream(413);
        try {
            return client.audio(file.getBytes(), limit);
        } catch (IOException exception) {
            throw new ApiException(400, "INVALID_AUDIO", "No se pudo leer el archivo");
        }
    }
}

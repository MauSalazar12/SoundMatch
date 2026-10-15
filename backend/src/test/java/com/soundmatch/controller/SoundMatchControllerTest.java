package com.soundmatch.controller;

import static com.soundmatch.dto.ApiDtos.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.soundmatch.exception.ApiException;
import com.soundmatch.service.SoundMatchService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;

@WebMvcTest(SoundMatchController.class)
class SoundMatchControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SoundMatchService service;

    @Test void returnsPublicMetadata() throws Exception {
        var song = new Song(2, "Food", "AWOL", "Hip-Hop", 30.0, "processed");
        when(service.song(2)).thenReturn(song);
        when(service.songs(0, 20, null, null, null)).thenReturn(new SongPage(List.of(song), 0, 20, 1));
        mvc.perform(get("/api/v1/songs/2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.duration_seconds").value(30.0))
                .andExpect(jsonPath("$.embedding").doesNotExist());
        mvc.perform(get("/api/v1/songs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].title").value("Food"));
    }

    @ParameterizedTest
    @ValueSource(strings={"/api/v1/songs/0", "/api/v1/songs/no", "/api/v1/songs?page=-1",
            "/api/v1/songs?size=101", "/api/v1/recommendations/2?limit=0", "/api/v1/recommendations/2?limit=101"})
    void rejectsInvalidParameters(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAMETERS"))
                .andExpect(jsonPath("$.request_id").isString());
        verifyNoInteractions(service);
    }

    @ParameterizedTest @ValueSource(ints={404, 503, 504})
    void translatesServiceErrors(int status) throws Exception {
        when(service.recommendations(2, 5)).thenThrow(ApiException.upstream(status));
        mvc.perform(get("/api/v1/recommendations/2")).andExpect(status().is(status))
                .andExpect(jsonPath("$.message").isString()).andExpect(jsonPath("$.request_id").isString())
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test void acceptsMultipartAndRequiresFile() throws Exception {
        when(service.audio(any(), eq(5))).thenReturn(new Recommendations(null, "mean_query_best_match", List.of()));
        mvc.perform(multipart("/api/v1/recommendations/audio").file(new MockMultipartFile("file", "sample.wav", "audio/wav", new byte[]{1,2})))
                .andExpect(status().isOk()).andExpect(jsonPath("$.strategy").value("mean_query_best_match"));
        mvc.perform(multipart("/api/v1/recommendations/audio")).andExpect(status().isBadRequest());
    }

    @Test void hidesUnexpectedException() throws Exception {
        when(service.song(2)).thenThrow(new RuntimeException("secret password"));
        mvc.perform(get("/api/v1/songs/2")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Error interno"));
    }
}

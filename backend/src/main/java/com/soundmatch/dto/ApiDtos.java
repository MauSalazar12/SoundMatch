package com.soundmatch.dto;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.util.List;

public final class ApiDtos {
    private ApiDtos() {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Song(long id, String title, String artist, String genre,
                       Double durationSeconds, String status) {}

    public record SongPage(List<Song> items, int page, int size, long total) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Recommendation(long songId, String title, String artist, String genre,
                                 double similarity, int segmentIndex, double startSeconds, double endSeconds) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Recommendations(Long referenceSongId, String strategy, List<Recommendation> recommendations) {}

    public record EngineHealth(String status, String http, String model, String database) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ApiError(String code, String message, String requestId) {}
}

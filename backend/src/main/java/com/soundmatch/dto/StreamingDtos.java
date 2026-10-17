package com.soundmatch.dto;

import java.util.List;

public final class StreamingDtos {
    private StreamingDtos() {}
    public record Ready(String type, String sessionId, int sampleRate, int windowSeconds, int hopSeconds) {}
    public record Processing(String type, long sequence, double windowStartSeconds, double windowEndSeconds) {}
    public record Result(String type, String sessionId, long sequence, double windowStartSeconds,
                         double windowEndSeconds, long processingMs, List<ApiDtos.Recommendation> recommendations) {}
    public record Error(String type, String code, String message) {}
}

package com.soundmatch.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("soundmatch.streaming")
public record StreamingProperties(
        @DefaultValue("http://localhost:5173") List<String> allowedOrigins,
        @DefaultValue({"44100", "48000"}) List<Integer> sampleRates,
        @DefaultValue("32") int maxSessions,
        @DefaultValue("30m") Duration maxDuration,
        @DefaultValue("30s") Duration idleTimeout,
        @DefaultValue("65536") int maxBinaryBytes,
        @DefaultValue("10") int windowSeconds,
        @DefaultValue("5") int hopSeconds,
        @DefaultValue("1.2") double audioRateLimit,
        @DefaultValue("2") double burstSeconds,
        @DefaultValue("1") int workers,
        @DefaultValue("8") int queueCapacity,
        @DefaultValue("5") int recommendationLimit,
        @DefaultValue("5000") int sendTimeoutMs,
        @DefaultValue("262144") int sendBufferBytes,
        @DefaultValue("") String internalToken) {
    public StreamingProperties {
        allowedOrigins = List.copyOf(allowedOrigins);
        sampleRates = List.copyOf(sampleRates);
        if (allowedOrigins.isEmpty() || allowedOrigins.stream().anyMatch(s -> s.contains("*") ||
                !(s.startsWith("http://") || s.startsWith("https://"))))
            throw new IllegalArgumentException("Explicit HTTP(S) WebSocket origins required");
        if (sampleRates.isEmpty() || !List.of(44100, 48000).containsAll(sampleRates)
                || maxSessions < 1 || maxDuration.isNegative() || maxDuration.isZero()
                || idleTimeout.isNegative() || idleTimeout.isZero()
                || maxBinaryBytes < 4 || maxBinaryBytes > 192000 || maxBinaryBytes % 4 != 0
                || windowSeconds != 10 || hopSeconds < 1 || hopSeconds > windowSeconds
                || !Double.isFinite(audioRateLimit) || audioRateLimit < 1 || audioRateLimit > 4
                || !Double.isFinite(burstSeconds) || burstSeconds < 1 || burstSeconds > 10
                || workers < 1 || queueCapacity < 1 || recommendationLimit < 1 || recommendationLimit > 100
                || sendTimeoutMs < 1 || sendBufferBytes < 1024)
            throw new IllegalArgumentException("Invalid streaming limits (CLAP uses 10-second windows)");
    }
}

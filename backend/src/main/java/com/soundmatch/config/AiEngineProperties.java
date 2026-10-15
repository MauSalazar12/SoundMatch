package com.soundmatch.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "soundmatch.ai-engine")
public record AiEngineProperties(URI baseUrl, Duration connectTimeout, Duration readTimeout, long maxUploadBytes) {
    public AiEngineProperties {
        if (baseUrl == null || !("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
                || baseUrl.getHost() == null || baseUrl.getUserInfo() != null) {
            throw new IllegalArgumentException("AI_ENGINE_URL must be an HTTP(S) URL without credentials");
        }
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout == null || readTimeout.isNegative() || readTimeout.isZero() || maxUploadBytes < 1) {
            throw new IllegalArgumentException("Timeouts and upload limit must be positive");
        }
    }
}

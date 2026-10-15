package com.soundmatch.config;

import com.soundmatch.client.AiEngineClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("aiEngine")
public class AiEngineHealthIndicator implements HealthIndicator {
    private final AiEngineClient client;
    public AiEngineHealthIndicator(AiEngineClient client) { this.client = client; }

    @Override public Health health() {
        try {
            var result = client.health();
            return ("ready".equals(result.status()) ? Health.up() : Health.down())
                    .withDetail("model", result.model()).withDetail("database", result.database()).build();
        } catch (RuntimeException exception) {
            return Health.down().withDetail("ai_engine", "unavailable").build();
        }
    }
}

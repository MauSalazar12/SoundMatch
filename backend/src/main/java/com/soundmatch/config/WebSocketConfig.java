package com.soundmatch.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import com.soundmatch.websocket.AudioWebSocketHandler;

@Configuration
@EnableWebSocket
@EnableScheduling
public class WebSocketConfig implements WebSocketConfigurer {

    private final AudioWebSocketHandler handler;
    private final StreamingProperties properties;

    public WebSocketConfig(AudioWebSocketHandler handler, StreamingProperties properties) {
        this.handler = handler;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(
        WebSocketHandlerRegistry registry
    ) {
        registry
            .addHandler(handler, "/ws/listen")
            .setAllowedOrigins(properties.allowedOrigins().toArray(String[]::new));
    }

    @Bean
    ServletServerContainerFactoryBean webSocketContainer() {
        var container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(properties.maxBinaryBytes());
        container.setMaxTextMessageBufferSize(2048);
        container.setAsyncSendTimeout((long) properties.sendTimeoutMs());
        container.setMaxSessionIdleTimeout(properties.idleTimeout().toMillis());
        return container;
    }
}

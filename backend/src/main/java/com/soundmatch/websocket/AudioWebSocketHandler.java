package com.soundmatch.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.soundmatch.config.StreamingProperties;
import com.soundmatch.exception.ApiException;
import com.soundmatch.service.AudioStreamingService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.adapter.standard.StandardWebSocketSession;

@Component
public class AudioWebSocketHandler extends AbstractWebSocketHandler {
    private final AudioSessionManager sessions;
    private final AudioStreamingService streaming;
    private final ObjectMapper mapper;
    private final StreamingProperties config;
    public AudioWebSocketHandler(AudioSessionManager sessions, AudioStreamingService streaming,
                                 ObjectMapper mapper, StreamingProperties config) {
        this.sessions = sessions; this.streaming = streaming; this.mapper = mapper; this.config = config;
    }
    @Override public void afterConnectionEstablished(WebSocketSession socket) {
        socket.setBinaryMessageSizeLimit(config.maxBinaryBytes());
        socket.setTextMessageSizeLimit(2048);
        // Tomcat's blocking sends otherwise default to 20 seconds. Bound slow consumers too.
        if (socket instanceof StandardWebSocketSession standard) {
            standard.getNativeSession().getUserProperties().put(
                    "org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT", (long) config.sendTimeoutMs());
        }
        sessions.open(socket);
    }
    @Override protected void handleTextMessage(WebSocketSession socket, TextMessage message) {
        var session = sessions.get(socket.getId());
        if (session == null) return;
        try {
            if (message.getPayloadLength() > 2048) throw new ApiException(400, "MESSAGE_TOO_LARGE", "Control JSON demasiado grande");
            com.fasterxml.jackson.databind.JsonNode control = mapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(message.getPayload());
            if (control == null || !control.isObject()) throw new ApiException(400, "INVALID_PARAMETERS", "Control JSON invalido");
            switch (control.path("type").asText()) {
                case "start" -> session.start(control);
                case "stop" -> sessions.close(socket.getId(), CloseStatus.NORMAL);
                default -> throw new ApiException(400, "INVALID_PARAMETERS", "Tipo de mensaje desconocido");
            }
        } catch (JsonProcessingException exception) {
            fail(socket, session, "INVALID_PARAMETERS", "JSON invalido");
        } catch (ApiException exception) { fail(socket, session, exception.code(), exception.getMessage()); }
    }
    @Override protected void handleBinaryMessage(WebSocketSession socket, BinaryMessage message) {
        var session = sessions.get(socket.getId());
        if (session == null) return;
        try { session.receive(message.getPayload(), window -> streaming.submit(session, window)); }
        catch (ApiException exception) { fail(socket, session, exception.code(), exception.getMessage()); }
    }
    private void fail(WebSocketSession socket, AudioSession session, String code, String message) {
        session.error(code, message);
        sessions.close(socket.getId(), CloseStatus.POLICY_VIOLATION);
    }
    @Override public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) { sessions.close(socket.getId(), status); }
    @Override public void handleTransportError(WebSocketSession socket, Throwable exception) {
        var session = sessions.get(socket.getId());
        if (session != null) session.error("CONNECTION_ERROR", "Error de transporte WebSocket");
        sessions.close(socket.getId(), CloseStatus.SERVER_ERROR);
    }
}

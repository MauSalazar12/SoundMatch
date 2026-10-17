package com.soundmatch.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soundmatch.config.StreamingProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

@Component
public class AudioSessionManager {
    private static final Logger log = LoggerFactory.getLogger(AudioSessionManager.class);
    private final ConcurrentHashMap<String, AudioSession> sessions = new ConcurrentHashMap<>();
    private final StreamingProperties config;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;
    public AudioSessionManager(StreamingProperties config, ObjectMapper mapper, MeterRegistry metrics) {
        this.config = config; this.mapper = mapper; this.metrics = metrics;
        metrics.gauge("soundmatch.streaming.sessions.active", sessions, ConcurrentHashMap::size);
    }
    public synchronized AudioSession open(WebSocketSession socket) {
        var session = new AudioSession(socket, mapper, config, metrics);
        if (sessions.size() >= config.maxSessions()) {
            session.error("OVERLOADED", "Limite de sesiones alcanzado");
            session.close(CloseStatus.SERVICE_OVERLOAD);
            return null;
        }
        sessions.put(socket.getId(), session);
        log.atInfo().addKeyValue("session_id", session.id()).addKeyValue("active_sessions", sessions.size()).log("stream_open");
        return session;
    }
    public AudioSession get(String id) { return sessions.get(id); }
    public int size() { return sessions.size(); }
    public void close(String id, CloseStatus status) {
        var session = sessions.remove(id);
        if (session != null) {
            session.close(status);
            log.atInfo().addKeyValue("session_id", session.id()).addKeyValue("active_sessions", sessions.size()).log("stream_close");
        }
    }
    @Scheduled(fixedDelay = 1000)
    public void cleanup() {
        long now = System.nanoTime();
        sessions.forEach((id, session) -> {
            synchronized (session) {
                if (session.closed() || session.expired(now)) {
                    if (!session.closed()) session.error("SESSION_EXPIRED", "Tiempo de sesion o inactividad agotado");
                    close(id, CloseStatus.SESSION_NOT_RELIABLE);
                }
            }
        });
    }
    @PreDestroy public void shutdown() { sessions.keySet().forEach(id -> close(id, CloseStatus.GOING_AWAY)); }
}

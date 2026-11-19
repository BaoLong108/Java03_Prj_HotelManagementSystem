package com.hotelpms.support;

import static com.hotelpms.support.SupportTypes.Actor;

import com.hotelpms.auth.security.DatabaseJwtAuthenticationConverter;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Session;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

@Component
class SupportSockets {
    private static final Logger log = LoggerFactory.getLogger(SupportSockets.class);
    record Connection(Session session, String token, Actor actor, Instant expires, Outbox outbox) {}
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final JwtDecoder decoder;
    private final DatabaseJwtAuthenticationConverter converter;
    private final ObjectMapper json;

    SupportSockets(JwtDecoder decoder, DatabaseJwtAuthenticationConverter converter, ObjectMapper json) {
        this.decoder = decoder;
        this.converter = converter;
        this.json = json;
    }

    Actor verify(String token) {
        if (token == null || token.length() > 4096) throw new IllegalArgumentException("Invalid token");
        Actor actor = Actor.from(converter.convert(decoder.decode(token)));
        if (actor == null || !(actor.customer() || actor.staff())) throw new IllegalArgumentException("Invalid role");
        return actor;
    }

    synchronized Connection connect(Session session, String token) {
        Actor actor = verify(token);
        if (connections.size() >= 180 || connections.values().stream().filter(c -> c.actor().id() == actor.id()).count() >= 6) {
            throw new IllegalArgumentException("Session limit");
        }
        var connection = new Connection(session, token, actor, decoder.decode(token).getExpiresAt(),
                new Outbox(session, () -> disconnect(session)));
        connections.put(session.getId(), connection);
        session.setMaxIdleTimeout(65_000);
        return connection;
    }

    void disconnect(Session session) {
        var removed = connections.remove(session.getId());
        if (removed == null) return;
        removed.outbox().discard();
        if (removed.actor().staff()) presence();
    }

    boolean staffOnline(long id) {
        return connections.values().stream().anyMatch(c -> c.actor().id() == id && c.actor().staff() && live(c));
    }

    long onlineStaff() {
        return connections.values().stream().filter(c -> c.actor().staff() && live(c)).map(c -> c.actor().id()).distinct().count();
    }

    void send(Session session, Object payload) {
        var connection = connections.get(session.getId());
        if (connection != null) connection.outbox().add(json.writeValueAsString(payload));
    }

    @TransactionalEventListener
    public void changed(SupportChat.Changed event) {
        connections.values().forEach(connection -> {
            Actor actor = connection.actor();
            if (actor.id() == event.customer() && actor.customer()
                    || actor.staff() && (event.inbox() || Long.valueOf(actor.id()).equals(event.staff()))) {
                if (live(connection)) send(connection.session(), Map.of("type", "changed"));
            }
        });
    }

    void presence() {
        connections.values().forEach(connection -> {
            if (live(connection)) send(connection.session(), Map.of("type", "presence"));
        });
    }

    @Scheduled(fixedDelay = 15_000)
    void validateConnectedActors() {
        connections.values().forEach(connection -> {
            try {
                if (!live(connection) || !connection.actor().equals(verify(connection.token()))) {
                    terminate(connection.session(), CloseReason.CloseCodes.VIOLATED_POLICY, "Session expired");
                }
            } catch (RuntimeException e) {
                terminate(connection.session(), CloseReason.CloseCodes.VIOLATED_POLICY, "Session expired");
            }
        });
    }

    private static boolean live(Connection c) { return c.session().isOpen() && c.expires().isAfter(Instant.now()); }

    void terminate(Session session, CloseReason.CloseCode code, String reason) {
        disconnect(session);
        close(session, code, reason);
    }

    static void close(Session session, CloseReason.CloseCode code, String reason) {
        try { session.close(new CloseReason(code, reason)); }
        catch (IOException e) { log.debug("Could not close support socket transport ({})", e.getClass().getSimpleName()); }
    }

    // Tomcat accepts one asynchronous write at a time per session.
    static final class Outbox {
        private static final int MAX_PENDING = 32;
        private final Session session;
        private final Runnable onFailure;
        private final ArrayDeque<String> queued = new ArrayDeque<>();
        private boolean writing;
        private boolean stopped;

        Outbox(Session session, Runnable onFailure) {
            this.session = session;
            this.onFailure = onFailure;
        }

        void add(String frame) {
            if (!session.isOpen()) return;
            boolean overflow;
            synchronized (this) {
                if (stopped) return;
                overflow = queued.size() >= MAX_PENDING;
                if (!overflow) {
                    queued.addLast(frame);
                    if (writing) return;
                    writing = true;
                }
            }
            if (overflow) {
                stop(CloseReason.CloseCodes.TRY_AGAIN_LATER, "Refresh missed updates after reconnect");
            } else next();
        }

        private void next() {
            String frame;
            synchronized (this) {
                if (stopped) return;
                frame = queued.pollFirst();
                writing = frame != null && session.isOpen();
                if (!writing) return;
            }
            try {
                session.getAsyncRemote().sendText(frame, result -> {
                    if (result.isOK()) ForkJoinPool.commonPool().execute(this::next);
                    else failed(result.getException());
                });
            } catch (IllegalStateException e) {
                failed(e);
            }
        }

        synchronized void discard() {
            stopped = true;
            queued.clear();
        }

        private void failed(Throwable cause) {
            log.debug("Support socket write failed ({})", cause == null ? "unknown" : cause.getClass().getSimpleName());
            stop(CloseReason.CloseCodes.UNEXPECTED_CONDITION, "Delivery interrupted");
        }

        private void stop(CloseReason.CloseCode code, String reason) {
            synchronized (this) {
                if (stopped) return;
                discard();
            }
            onFailure.run();
            close(session, code, reason);
        }
    }
}

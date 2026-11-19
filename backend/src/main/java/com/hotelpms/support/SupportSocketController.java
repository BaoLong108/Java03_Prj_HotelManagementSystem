package com.hotelpms.support;

import static com.hotelpms.support.SupportTypes.*;

import com.hotelpms.shared.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
class SupportSocketController {
    private final SupportSockets sockets;
    private final SupportChat chat;
    private final SupportRateLimit limits;
    private final ObjectMapper json;
    private final List<String> origins;

    SupportSocketController(SupportSockets sockets, SupportChat chat, SupportRateLimit limits,
            ObjectMapper json, @Value("${app.cors.allowed-origins}") List<String> origins) {
        this.sockets = sockets;
        this.chat = chat;
        this.limits = limits;
        this.json = json;
        this.origins = origins;
    }

    @GetMapping("/api/v1/support/socket")
    void upgrade(HttpServletRequest request, HttpServletResponse response) throws Exception {
        if (!origins.contains(request.getHeader("Origin"))) throw new ApiException(
                HttpStatus.FORBIDDEN, "FORBIDDEN_ORIGIN", "Không cho phép kết nối từ trang này.");
        limits.allow("socket:" + request.getRemoteAddr(), 120);
        if (!"websocket".equalsIgnoreCase(request.getHeader("Upgrade"))) throw new ApiException(
                HttpStatus.BAD_REQUEST, "WEBSOCKET_REQUIRED", "Cần sử dụng kết nối WebSocket.");
        var config = ServerEndpointConfig.Builder.create(Socket.class, request.getServletPath())
                .configurator(new ServerEndpointConfig.Configurator() {
                    @Override public <T> T getEndpointInstance(Class<T> endpoint) { return endpoint.cast(new Socket()); }
                }).build();
        var container = (ServerContainer) request.getServletContext().getAttribute(ServerContainer.class.getName());
        container.upgradeHttpToWebSocket(request, response, config, Map.of());
    }

    public final class Socket extends Endpoint {
        private SupportSockets.Connection connection;

        @Override
        public void onOpen(Session session, EndpointConfig config) {
            session.setMaxTextMessageBufferSize(8192);
            session.setMaxBinaryMessageBufferSize(1024);
            session.setMaxIdleTimeout(5_000);
            session.getAsyncRemote().setSendTimeout(5_000);
            session.addMessageHandler(String.class, text -> receive(session, text));
        }

        private void receive(Session session, String text) {
            String clientId = "";
            try {
                var input = json.readTree(text);
                if (connection == null) {
                    if (!"auth".equals(input.path("type").asString())) throw new IllegalArgumentException("Auth required");
                    connection = sockets.connect(session, input.path("token").asString());
                    sockets.send(session, Map.of("type", "ready"));
                    if (connection.actor().staff()) sockets.presence();
                    return;
                }
                if ("ping".equals(input.path("type").asString())) {
                    sockets.send(session, Map.of("type", "pong"));
                    return;
                }
                if (!"send".equals(input.path("type").asString())) {
                    sockets.terminate(session, CloseReason.CloseCodes.VIOLATED_POLICY, "Unsupported operation");
                    return;
                }
                clientId = UUID.fromString(input.path("clientId").asString()).toString();
                String content = input.path("content").asString();
                if (content.isBlank() || content.length() > 1200) throw new ApiException(HttpStatus.BAD_REQUEST,
                        "VALIDATION_ERROR", "Tin nhắn phải có từ 1 đến 1.200 ký tự.");
                Actor actor = sockets.verify(connection.token());
                if (!connection.actor().equals(actor)) {
                    sockets.terminate(session, CloseReason.CloseCodes.VIOLATED_POLICY, "Session changed");
                    return;
                }
                var message = chat.send(input.path("conversationId").asLong(0), actor, input.path("staff").asBoolean(false),
                        new Send(UUID.fromString(clientId), content), false);
                sockets.send(session, Map.of("type", "ack", "clientId", clientId, "message", message));
            } catch (ApiException e) {
                sockets.send(session, Map.of("type", "error", "clientId", clientId, "message", e.getMessage()));
            } catch (RuntimeException e) {
                sockets.terminate(session, CloseReason.CloseCodes.VIOLATED_POLICY, "Invalid support request");
            }
        }

        @Override public void onClose(Session session, CloseReason reason) { sockets.disconnect(session); }
        @Override public void onError(Session session, Throwable cause) {
            sockets.terminate(session, CloseReason.CloseCodes.UNEXPECTED_CONDITION, "Connection interrupted");
        }
    }
}

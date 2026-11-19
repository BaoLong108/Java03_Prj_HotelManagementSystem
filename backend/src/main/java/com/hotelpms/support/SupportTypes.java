package com.hotelpms.support;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.Authentication;

final class SupportTypes {
    enum Status { AI_ACTIVE, WAITING_STAFF, STAFF_ACTIVE, CLOSED }
    enum Sender { CUSTOMER, AI, STAFF }
    record Actor(long id, boolean customer, boolean staff) {
        static Actor from(Authentication auth) {
            if (!(auth instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwt)) return null;
            try {
                return new Actor(Long.parseLong(jwt.getName()), role(auth, "CUSTOMER"), role(auth, "STAFF") || role(auth, "ADMIN"));
            } catch (NumberFormatException e) { return null; }
        }

        private static boolean role(Authentication auth, String role) {
            return auth.getAuthorities().stream().anyMatch(value -> value.getAuthority().equals("ROLE_" + role));
        }
    }
    record Conversation(long id, Status status, String customerName, boolean assignedToMe, boolean staffOnline,
                        long unread, String preview, Instant updatedAt) {}
    record Message(long id, Sender sender, String content, Instant createdAt, tools.jackson.databind.JsonNode details) {}
    record Send(@NotNull UUID clientId, @NotBlank @Size(max = 1200) String content) {}
    record Faq(long id, String category, String question, String answer, boolean active, Instant updatedAt) {}
    record FaqInput(@NotBlank @Size(max = 60) String category, @NotBlank @Size(max = 240) String question,
                    @NotBlank @Size(max = 1600) String answer, boolean active) {}
    private SupportTypes() {}
}

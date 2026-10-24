package com.hotelpms.auth.security;

import com.hotelpms.auth.entity.RoleCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Reusable decisions for the current JWT/DB authority model, without guessing future HTTP routes. */
@Component("authorizationPolicy")
public class AuthorizationPolicy {
    public boolean isCurrentUser(Authentication authentication, Long userId) {
        return positive(userId) && userId.equals(currentUserId(authentication));
    }

    /** ownerUserId must come from the persisted resource, not from a request's claimed owner. */
    public boolean isCustomerOwner(Authentication authentication, Long ownerUserId) {
        return hasRole(authentication, RoleCode.CUSTOMER) && isCurrentUser(authentication, ownerUserId);
    }

    /** Staff/admin operational reads do not grant permission to edit/pay a customer's booking. */
    public boolean canReadBooking(Authentication authentication, Long ownerUserId) {
        return positive(ownerUserId) && (isCustomerOwner(authentication, ownerUserId)
                || hasRole(authentication, RoleCode.STAFF) || hasRole(authentication, RoleCode.ADMIN));
    }

    public boolean canManageRooms(Authentication authentication) {
        return hasRole(authentication, RoleCode.ADMIN);
    }

    /** The room service must still validate intervals, locks and confirmed bookings. */
    public boolean canManageRoomBlocks(Authentication authentication) {
        return hasRole(authentication, RoleCode.STAFF) || hasRole(authentication, RoleCode.ADMIN);
    }

    private boolean hasRole(Authentication authentication, RoleCode role) {
        return currentUserId(authentication) != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> ("ROLE_" + role.name()).equals(authority.getAuthority()));
    }

    private Long currentUserId(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated()) return null;
        String subject = jwt.getToken().getSubject();
        if (subject == null || !subject.matches("[1-9][0-9]*")) return null;
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private boolean positive(Long id) { return id != null && id > 0; }
}

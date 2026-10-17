package com.hotelpms.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.hotelpms.auth.entity.RoleCode;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class AuthorizationPolicyTest {
    private final AuthorizationPolicy policy = new AuthorizationPolicy();

    @ParameterizedTest
    @CsvSource({"CUSTOMER,false,false", "STAFF,false,true", "ADMIN,true,true"})
    void managementPermissionsUseTheExistingRoles(RoleCode role, boolean rooms, boolean blocks) {
        var authentication = token("11", "ROLE_" + role.name());
        assertThat(policy.canManageRooms(authentication)).isEqualTo(rooms);
        assertThat(policy.canManageRoomBlocks(authentication)).isEqualTo(blocks);
    }

    @Test
    void customerCanReadOnlyTheirOwnResources() {
        var customer = token("11", "ROLE_CUSTOMER");
        assertThat(policy.isCustomerOwner(customer, 11L)).isTrue();
        assertThat(policy.canReadBooking(customer, 11L)).isTrue();
        assertThat(policy.isCustomerOwner(customer, 12L)).isFalse();
        assertThat(policy.canReadBooking(customer, 12L)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_STAFF", "ROLE_ADMIN"})
    void operationalReadIsSeparateFromCustomerOwnership(String role) {
        var authentication = token("11", role);
        assertThat(policy.canReadBooking(authentication, 12L)).isTrue();
        assertThat(policy.isCustomerOwner(authentication, 11L)).isFalse();
        assertThat(policy.isCustomerOwner(authentication, 12L)).isFalse();
        assertThat(policy.isCurrentUser(authentication, 11L)).isTrue();
        assertThat(policy.isCurrentUser(authentication, 12L)).isFalse();
    }

    @Test
    void accountWithMultipleRolesRetainsOnlyExplicitPermissions() {
        var authentication = token("11", "ROLE_CUSTOMER", "ROLE_STAFF");
        assertThat(policy.isCustomerOwner(authentication, 11L)).isTrue();
        assertThat(policy.isCustomerOwner(authentication, 12L)).isFalse();
        assertThat(policy.canManageRoomBlocks(authentication)).isTrue();
        assertThat(policy.canManageRooms(authentication)).isFalse();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void missingOrInvalidOwnershipIsDeniedEvenForAdmin(Long ownerId) {
        var authentication = token("11", "ROLE_ADMIN", "ROLE_CUSTOMER");
        assertThat(policy.isCurrentUser(authentication, ownerId)).isFalse();
        assertThat(policy.isCustomerOwner(authentication, ownerId)).isFalse();
        assertThat(policy.canReadBooking(authentication, ownerId)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "-1", "01", "12x", "9223372036854775808"})
    void malformedPrincipalsCannotGainAnyPermission(String subject) {
        var authentication = token(subject, "ROLE_ADMIN", "ROLE_CUSTOMER");
        assertThat(policy.isCurrentUser(authentication, 11L)).isFalse();
        assertThat(policy.isCustomerOwner(authentication, 11L)).isFalse();
        assertThat(policy.canReadBooking(authentication, 11L)).isFalse();
        assertThat(policy.canManageRooms(authentication)).isFalse();
        assertThat(policy.canManageRoomBlocks(authentication)).isFalse();
    }

    @Test
    void guestNullAndNonJwtAuthenticationAreDenied() {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (var authentication : List.of(
                new AnonymousAuthenticationToken("guest-key", "anonymousUser", authorities),
                UsernamePasswordAuthenticationToken.authenticated("11", "", authorities))) {
            assertThat(policy.isCurrentUser(authentication, 11L)).isFalse();
            assertThat(policy.canManageRooms(authentication)).isFalse();
            assertThat(policy.canReadBooking(authentication, 11L)).isFalse();
        }
        assertThat(policy.isCurrentUser(null, 11L)).isFalse();
        assertThat(policy.canManageRooms(null)).isFalse();
    }

    @Test
    void unvalidatedJwtAndUnprefixedOrUnsupportedAuthoritiesAreDenied() {
        var unvalidated = token("11", "ROLE_ADMIN");
        unvalidated.setAuthenticated(false);
        assertThat(policy.canManageRooms(unvalidated)).isFalse();
        for (String role : List.of("ADMIN", "ROLE_RECEPTIONIST", "ROLE_HOUSEKEEPING")) {
            assertThat(policy.canManageRooms(token("11", role))).isFalse();
            assertThat(policy.canManageRoomBlocks(token("11", role))).isFalse();
        }
    }

    @Test
    void roleClaimsAndAuthenticationDisplayNameDoNotOverrideTrustedSubjectAndAuthorities() {
        Jwt jwt = Jwt.withTokenValue("unit-test").header("alg", "HS256").subject("11")
                .claim("roles", List.of("ADMIN")).build();
        var authentication = new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")), "12");
        assertThat(policy.canManageRooms(authentication)).isFalse();
        assertThat(policy.isCustomerOwner(authentication, 11L)).isTrue();
        assertThat(policy.isCustomerOwner(authentication, 12L)).isFalse();
    }

    private JwtAuthenticationToken token(String subject, String... authorities) {
        Jwt jwt = Jwt.withTokenValue("unit-test").header("alg", "HS256").claim("sub", subject).build();
        return new JwtAuthenticationToken(jwt,
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
    }
}

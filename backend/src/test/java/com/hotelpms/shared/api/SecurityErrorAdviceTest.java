package com.hotelpms.shared.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hotelpms.auth.controller.AuthController;
import com.hotelpms.auth.service.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Isolates MVC exception resolution on the existing controller; filter/JWT tests live in RbacIntegrationTest. */
class SecurityErrorAdviceTest {
    private AuthService auth;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        auth = mock(AuthService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(auth))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new ApiExceptionHandler()).build();
        Jwt principal = Jwt.withTokenValue("advice-test-only").header("alg", "HS256").subject("11").build();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(principal));
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void methodAccessDeniedUses403RatherThanTheGeneric500Handler() throws Exception {
        when(auth.me(11L)).thenThrow(new AccessDeniedException("internal-policy-details"));
        var response = mvc.perform(get("/api/v1/auth/me")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").isString()).andExpect(jsonPath("$.details").isMap())
                .andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("internal-policy-details", "Exception");
    }

    @Test
    void methodAuthenticationFailureUses401AndTheSameSafeErrorEnvelope() throws Exception {
        when(auth.me(11L)).thenThrow(new BadCredentialsException("internal-credential-details"));
        var response = mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.details").isMap()).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("internal-credential-details", "Exception");
    }
}

package com.hotelpms.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hotelpms.auth.entity.RoleCode;
import com.hotelpms.auth.entity.User;
import com.hotelpms.auth.repository.RoleRepository;
import com.hotelpms.auth.repository.UserRepository;
import com.hotelpms.auth.service.AuthService;
import com.hotelpms.auth.service.JwtService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(RbacIntegrationTest.PolicyTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners = RbacIntegrationTest.PostgresCleanupListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class RbacIntegrationTest {
    private static final String PASSWORD = "RbacIntegration123!";
    private static EmbeddedPostgres postgres;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) throws IOException {
        String externalUrl = System.getenv("TEST_DB_URL");
        if (externalUrl == null || externalUrl.isBlank()) {
            postgres = EmbeddedPostgres.builder().setPort(0).start();
            properties.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
            properties.add("spring.datasource.username", () -> "postgres");
            properties.add("spring.datasource.password", () -> "postgres");
        } else {
            properties.add("spring.datasource.url", () -> externalUrl);
            properties.add("spring.datasource.username", () -> System.getenv("TEST_DB_USER"));
            properties.add("spring.datasource.password", () -> System.getenv("TEST_DB_PASSWORD"));
        }
        properties.add("app.jwt.secret", () -> "rbac-integration-signing-key-at-least-thirty-two-characters");
        properties.add("app.jwt.access-token-ttl-seconds", () -> "3600");
    }

    public static class PostgresCleanupListener extends AbstractTestExecutionListener {
        @Override
        public int getOrder() { return 1000; }

        @Override
        public void afterTestClass(TestContext context) throws IOException {
            // Match the existing suite: close Hikari/Spring before closing the test-only database.
            if (postgres != null) postgres.close();
        }
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private PasswordEncoder passwords;
    @Autowired private JwtService tokens;
    @Autowired private JwtEncoder encoder;
    @Autowired private JwtDecoder decoder;
    @Autowired private DatabaseJwtAuthenticationConverter converter;
    @Autowired private AuthService auth;
    @Autowired private PolicyProbe probe;
    @Autowired private JdbcTemplate jdbc;
    private Account customer;
    private Account otherCustomer;
    private Account staff;
    private Account admin;

    record Account(Long id, String email, String token) { }

    @BeforeAll
    void createAccounts() throws Exception {
        customer = registerCustomer();
        otherCustomer = registerCustomer();
        staff = account(RoleCode.STAFF);
        admin = account(RoleCode.ADMIN);
    }

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void guestCanUseTheRealPublicHealthEndpoint() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void registrationAndLoginStayPublicAndKeepTheExistingDto() throws Exception {
        Account registered = registerCustomer();
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", registered.email(), "password", PASSWORD))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.id").value(registered.id()))
                .andExpect(jsonPath("$.user.roles[0]").value("CUSTOMER"));
    }

    @Test
    void missingTokenIs401WithTheStandardErrorAndBearerChallenge() throws Exception {
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.details").isMap());
    }

    Stream<Account> authenticatedAccounts() { return Stream.of(customer, staff, admin); }

    @ParameterizedTest
    @MethodSource("authenticatedAccounts")
    void everyExistingRoleCanReadItsOwnAccountUsingARealJwt(Account account) throws Exception {
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + account.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(account.id()))
                .andExpect(jsonPath("$.email").value(account.email()));
    }

    @Test
    void clientSuppliedUserIdCannotRedirectMeToAnotherCustomer() throws Exception {
        mvc.perform(get("/api/v1/auth/me").param("userId", otherCustomer.id().toString())
                        .header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(customer.id()));
    }

    @ParameterizedTest
    @MethodSource("authenticatedAccounts")
    void actualAuthServiceAlsoChecksOwnershipForInternalCalls(Account account) {
        assertThat(as(account, () -> auth.me(account.id())).id()).isEqualTo(account.id());
        assertThatThrownBy(() -> as(account, () -> auth.me(otherCustomer.id())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void callingProtectedServiceWithoutSecurityContextIsRejected() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> auth.me(customer.id()))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void customerCannotInvokeStaffOrAdminPoliciesOrReadAnotherCustomersBooking() {
        assertThat(as(customer, () -> probe.customerResource(customer.id()))).isEqualTo("allowed");
        assertThatThrownBy(() -> as(customer, probe::manageRooms)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> as(customer, probe::manageBlocks)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> as(customer, () -> probe.readBooking(otherCustomer.id())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void staffCanManageBlocksAndReadOperationalBookingsButCannotManageRoomInventory() {
        assertThat(as(staff, probe::manageBlocks)).isEqualTo("allowed");
        assertThat(as(staff, () -> probe.readBooking(otherCustomer.id()))).isEqualTo("allowed");
        assertThatThrownBy(() -> as(staff, probe::manageRooms)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> as(staff, () -> probe.customerResource(staff.id())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void adminPermissionsDoNotBypassCustomerOwnershipOrMissingResourceOwners() {
        assertThat(as(admin, probe::manageRooms)).isEqualTo("allowed");
        assertThat(as(admin, probe::manageBlocks)).isEqualTo("allowed");
        assertThat(as(admin, () -> probe.readBooking(otherCustomer.id()))).isEqualTo("allowed");
        assertThatThrownBy(() -> as(admin, () -> probe.customerResource(customer.id())))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> as(admin, () -> probe.readBooking(null)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void signedAdminRoleClaimCannotElevateADatabaseCustomer() throws Exception {
        String claimedAdmin = sign(customer.id(), Instant.now().minusSeconds(1), Instant.now().plusSeconds(600),
                Set.of("ADMIN"));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + claimedAdmin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));
        Account forgedRoleAccount = new Account(customer.id(), customer.email(), claimedAdmin);
        assertThatThrownBy(() -> as(forgedRoleAccount, probe::manageRooms))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void roleRemovalTakesEffectForPreviouslyIssuedTokens() throws Exception {
        Account changed = account(RoleCode.STAFF);
        assertThat(as(changed, probe::manageBlocks)).isEqualTo("allowed");
        jdbc.update("DELETE FROM user_roles WHERE user_id = ?", changed.id());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + changed.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roles").isEmpty());
        assertThatThrownBy(() -> as(changed, probe::manageBlocks)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void disabledAccountCannotUseAnExistingAdminJwt() throws Exception {
        Account disabled = account(RoleCode.ADMIN);
        jdbc.update("UPDATE app_users SET is_active = FALSE WHERE id = ?", disabled.id());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + disabled.token()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    Stream<String> invalidTokens() {
        String[] parts = customer.token().split("\\.");
        String signature = (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
        return Stream.of("not-a-jwt", parts[0] + "." + parts[1] + "." + signature,
                sign(customer.id(), Instant.now().minusSeconds(120), Instant.now().minusSeconds(60),
                        Set.of("CUSTOMER")));
    }

    @ParameterizedTest
    @MethodSource("invalidTokens")
    void invalidTamperedAndExpiredJwtsAre401(String token) throws Exception {
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void unknownBusinessRoutesStayClosedIncludingForAdmin() throws Exception {
        mvc.perform(get("/api/v1/admin/rooms")).andExpect(status().isUnauthorized());
        for (Account account : new Account[] {customer, admin}) {
            mvc.perform(get("/api/v1/admin/rooms").header("Authorization", "Bearer " + account.token()))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    private Account registerCustomer() throws Exception {
        String email = UUID.randomUUID() + "@rbac.example.test";
        String response = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD,
                                "fullName", "RBAC Customer"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var body = json.readTree(response);
        return new Account(body.path("user").path("id").asLong(), email, body.path("accessToken").asText());
    }

    private Account account(RoleCode roleCode) {
        String email = UUID.randomUUID() + "@rbac.example.test";
        User user = users.saveAndFlush(new User(email, passwords.encode(PASSWORD), "RBAC " + roleCode,
                Set.of(roles.findByCode(roleCode).orElseThrow())));
        return new Account(user.getId(), email, tokens.issue(user).accessToken());
    }

    private String sign(Long userId, Instant issued, Instant expiry, Set<String> claimedRoles) {
        var claims = JwtClaimsSet.builder().issuer("hotel-pms-api").subject(userId.toString())
                .issuedAt(issued).expiresAt(expiry).claim("roles", claimedRoles).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                claims)).getTokenValue();
    }

    private <T> T as(Account account, Supplier<T> action) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(converter.convert(decoder.decode(account.token())));
        SecurityContextHolder.setContext(context);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PolicyTestConfiguration {
        @Bean
        PolicyProbe policyProbe() { return new PolicyProbe(); }
    }

    /** Test-only service proxies exercise future policies; no test controller/route enters production. */
    public static class PolicyProbe {
        @PreAuthorize("isAuthenticated() and @authorizationPolicy.canManageRooms(authentication)")
        public String manageRooms() { return "allowed"; }

        @PreAuthorize("isAuthenticated() and @authorizationPolicy.canManageRoomBlocks(authentication)")
        public String manageBlocks() { return "allowed"; }

        @PreAuthorize("isAuthenticated() and @authorizationPolicy.canReadBooking(authentication, #p0)")
        public String readBooking(Long persistedOwnerId) { return "allowed"; }

        @PreAuthorize("isAuthenticated() and @authorizationPolicy.isCustomerOwner(authentication, #p0)")
        public String customerResource(Long persistedOwnerId) { return "allowed"; }
    }
}

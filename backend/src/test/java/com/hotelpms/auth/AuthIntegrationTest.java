package com.hotelpms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.hotelpms.auth.config.DemoDataSeeder;
import com.hotelpms.auth.entity.RoleCode;
import com.hotelpms.auth.entity.User;
import com.hotelpms.auth.repository.CustomerProfileRepository;
import com.hotelpms.auth.repository.RoleRepository;
import com.hotelpms.auth.repository.UserRepository;
import com.hotelpms.auth.security.DatabaseJwtAuthenticationConverter;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners = AuthIntegrationTest.PostgresCleanupListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class AuthIntegrationTest {
    private static final String PASSWORD = "TestDemo123!";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
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
        properties.add("app.jwt.secret", () -> "integration-test-signing-key-at-least-thirty-two-characters");
        properties.add("app.jwt.access-token-ttl-seconds", () -> "3600");
        properties.add("app.demo-seed.enabled", () -> "true");
        properties.add("app.demo-seed.password", () -> PASSWORD);
    }

    public static class PostgresCleanupListener extends AbstractTestExecutionListener {
        @Override
        public int getOrder() { return 1000; }

        @Override
        public void afterTestClass(TestContext context) throws IOException {
            // Runs after DirtiesContext closes Tomcat/Hikari (listeners unwind in reverse order).
            if (postgres != null) postgres.close();
        }
    }

    @Value("${local.server.port}") private int port;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private CustomerProfileRepository profiles;
    @Autowired private PasswordEncoder passwords;
    @Autowired private JwtDecoder decoder;
    @Autowired private JwtEncoder encoder;
    @Autowired private DatabaseJwtAuthenticationConverter converter;
    @Autowired private DemoDataSeeder seeder;

    @Test
    void postgresMigrationAndApplicationHealthAreReady() throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'", Integer.class))
                .isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version = '1'", Boolean.class))
                .isTrue();
        assertThat(roles.findAll()).extracting(role -> role.getCode())
                .containsExactlyInAnyOrder(RoleCode.CUSTOMER, RoleCode.STAFF, RoleCode.ADMIN);
        HttpResponse<String> health = get("/actuator/health", null);
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(tree(health).path("status").asText()).isEqualTo("UP");
    }

    @Test
    void developmentSeedIsHashedAndIdempotent() {
        long before = users.count();
        seeder.run(new DefaultApplicationArguments());
        assertThat(users.count()).isEqualTo(before);
        for (String kind : List.of("customer", "staff", "admin")) {
            User user = users.findByEmailIgnoreCase(kind + "@hotel-pms.test").orElseThrow();
            assertThat(user.getPasswordHash()).startsWith("$2a$12$").isNotEqualTo(PASSWORD);
            assertThat(passwords.matches(PASSWORD, user.getPasswordHash())).isTrue();
            assertThat(user.getRoles()).extracting(role -> role.getCode())
                    .containsExactly(RoleCode.valueOf(kind.toUpperCase(java.util.Locale.ROOT)));
            assertThat(profiles.existsById(user.getId())).isEqualTo(kind.equals("customer"));
        }
    }

    @Test
    void registerCreatesCustomerProfileAndSafeTokenWithoutAcceptingElevatedRoles() throws Exception {
        String email = email();
        HttpResponse<String> result = post("/api/v1/auth/register", Map.of(
                "email", email.toUpperCase(java.util.Locale.ROOT), "password", PASSWORD,
                "fullName", " Nguyễn Ngọc Bảo Long ", "phoneNumber", " 0901234567 ",
                "roles", List.of("ADMIN", "STAFF")));
        assertThat(result.statusCode()).isEqualTo(201);
        JsonNode body = tree(result);
        User user = users.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(body.path("user").path("id").asLong()).isEqualTo(user.getId());
        assertThat(body.path("user").path("email").asText()).isEqualTo(email);
        assertThat(body.path("user").path("fullName").asText()).isEqualTo("Nguyễn Ngọc Bảo Long");
        assertThat(body.path("user").path("roles").toString()).isEqualTo("[\"CUSTOMER\"]");
        assertThat(body.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.path("expiresIn").asLong()).isEqualTo(3600);
        assertThat(result.body()).doesNotContain("password", "passwordHash", PASSWORD);
        assertThat(result.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(passwords.matches(PASSWORD, user.getPasswordHash())).isTrue();
        var profile = profiles.findById(user.getId()).orElseThrow();
        assertThat(profile.getPhoneNumber()).isEqualTo("0901234567");
        assertThat(profile.getEligibleSpendTotal()).isZero();
        assertThat(profile.getPointsBalance()).isZero();
        assertThat(profile.getTierCode().name()).isEqualTo("STANDARD");
        var jwt = decoder.decode(body.path("accessToken").asText());
        assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
        assertThat(jwt.getClaims().keySet()).containsExactlyInAnyOrder("sub", "iss", "iat", "exp", "roles");
    }

    @Test
    void duplicateEmailIgnoresCaseAndDoesNotLeaveOrphanData() throws Exception {
        String email = email();
        assertThat(register(email).statusCode()).isEqualTo(201);
        long userCount = users.count();
        long profileCount = profiles.count();
        HttpResponse<String> duplicate = register(email.toUpperCase(java.util.Locale.ROOT));
        assertError(duplicate, 409, "EMAIL_ALREADY_EXISTS");
        assertThat(users.count()).isEqualTo(userCount);
        assertThat(profiles.count()).isEqualTo(profileCount);
    }

    @Test
    void concurrentRegistrationOnlyCreatesOneAccount() throws Exception {
        String email = email();
        String body = json.writeValueAsString(registration(email, PASSWORD));
        var first = HTTP.sendAsync(postRequest("/api/v1/auth/register", body), HttpResponse.BodyHandlers.ofString());
        var second = HTTP.sendAsync(postRequest("/api/v1/auth/register", body), HttpResponse.BodyHandlers.ofString());
        CompletableFuture.allOf(first, second).join();
        assertThat(List.of(first.join().statusCode(), second.join().statusCode()))
                .containsExactlyInAnyOrder(201, 409);
        HttpResponse<String> conflict = first.join().statusCode() == 409 ? first.join() : second.join();
        assertError(conflict, 409, "EMAIL_ALREADY_EXISTS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_users WHERE email = ?", Integer.class, email)).isOne();
        assertThat(profiles.existsById(users.findByEmailIgnoreCase(email).orElseThrow().getId())).isTrue();
    }

    static Stream<Map<String, String>> invalidRegistrations() {
        return Stream.of(
                Map.of("email", "invalid", "password", PASSWORD, "fullName", "Test"),
                Map.of("email", "valid@example.test", "password", "short", "fullName", "Test"),
                Map.of("email", "valid@example.test", "password", " ".repeat(10), "fullName", "Test"),
                Map.of("email", "valid@example.test", "password", "á".repeat(37), "fullName", "Test"),
                Map.of("email", "valid@example.test", "password", "x".repeat(73), "fullName", "Test"),
                Map.of("email", "valid@example.test", "password", PASSWORD, "fullName", " "),
                Map.of("email", "valid@example.test", "password", PASSWORD, "fullName", "Test", "phoneNumber", "1".repeat(31)),
                Map.of("email", "valid@example.test", "fullName", "Test"));
    }

    @ParameterizedTest
    @MethodSource("invalidRegistrations")
    void invalidRegistrationReturnsFieldErrorsWithoutWrites(Map<String, String> request) throws Exception {
        long before = users.count();
        HttpResponse<String> result = post("/api/v1/auth/register", request);
        assertError(result, 400, "VALIDATION_ERROR");
        assertThat(tree(result).path("details").isEmpty()).isFalse();
        assertThat(users.count()).isEqualTo(before);
        assertThat(result.body()).doesNotContain(PASSWORD);
    }

    @Test
    void passwordsAtBcryptByteLimitWorkAndLongUnicodeLoginDoesNotCrash() throws Exception {
        for (String password : List.of("x".repeat(72), "á".repeat(36))) {
            String email = email();
            assertThat(post("/api/v1/auth/register", registration(email, password)).statusCode()).isEqualTo(201);
            assertThat(post("/api/v1/auth/login", Map.of("email", email, "password", password)).statusCode()).isEqualTo(200);
        }
        assertError(post("/api/v1/auth/login", Map.of(
                "email", "customer@hotel-pms.test", "password", "á".repeat(37))), 401, "INVALID_CREDENTIALS");
    }

    @Test
    void loginReturnsTokenThatAccessesOnlyTheCurrentUser() throws Exception {
        String email = email();
        JsonNode registered = tree(register(email));
        HttpResponse<String> login = post("/api/v1/auth/login", Map.of(
                "email", email.toUpperCase(java.util.Locale.ROOT), "password", PASSWORD));
        assertThat(login.statusCode()).isEqualTo(200);
        HttpResponse<String> me = get("/api/v1/auth/me", tree(login).path("accessToken").asText());
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(tree(me)).isEqualTo(registered.path("user"));
        assertThat(me.body()).doesNotContain("password", "passwordHash");
    }

    @Test
    void unknownIncorrectAndInactiveCredentialsReturnTheSameError() throws Exception {
        String email = email();
        assertThat(register(email).statusCode()).isEqualTo(201);
        var wrongPassword = post("/api/v1/auth/login", Map.of("email", email, "password", "wrong-password"));
        var missingUser = post("/api/v1/auth/login", Map.of("email", email(), "password", PASSWORD));
        User user = users.findByEmailIgnoreCase(email).orElseThrow();
        user.setActive(false);
        users.saveAndFlush(user);
        var inactive = post("/api/v1/auth/login", Map.of("email", email, "password", PASSWORD));
        for (var result : List.of(wrongPassword, missingUser, inactive)) {
            assertError(result, 401, "INVALID_CREDENTIALS");
            assertThat(result.body()).isEqualTo(wrongPassword.body());
        }
    }

    @Test
    void missingMalformedTamperedExpiredOrInvalidClaimsAreUnauthorized() throws Exception {
        JsonNode registration = tree(register(email()));
        String subject = registration.path("user").path("id").asText();
        String valid = registration.path("accessToken").asText();
        Instant now = Instant.now();
        String changedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"" + subject + "\",\"roles\":[\"ADMIN\"]}").getBytes(StandardCharsets.UTF_8));
        String[] parts = valid.split("\\.");
        String tampered = parts[0] + "." + changedPayload + "." + parts[2];
        String unsigned = "eyJhbGciOiJub25lIn0." + changedPayload + ".";
        List<String> invalid = List.of("not-a-token", tampered, unsigned,
                sign(subject, "hotel-pms-api", now.minusSeconds(60), now.minusSeconds(1)),
                sign(subject, "wrong-issuer", now, now.plusSeconds(60)),
                sign(subject, "hotel-pms-api", now, null),
                sign(subject, "hotel-pms-api", now.plusSeconds(60), now.plusSeconds(120)),
                sign("not-a-user-id", "hotel-pms-api", now, now.plusSeconds(60)),
                sign("9223372036854775808", "hotel-pms-api", now, now.plusSeconds(60)));
        assertError(get("/api/v1/auth/me", null), 401, "UNAUTHORIZED");
        for (String token : invalid) assertError(get("/api/v1/auth/me", token), 401, "UNAUTHORIZED");
    }

    @Test
    void disablingAccountAlsoRejectsExistingToken() throws Exception {
        String email = email();
        String token = tree(register(email)).path("accessToken").asText();
        assertThat(get("/api/v1/auth/me", token).statusCode()).isEqualTo(200);
        User user = users.findByEmailIgnoreCase(email).orElseThrow();
        user.setActive(false);
        users.saveAndFlush(user);
        assertError(get("/api/v1/auth/me", token), 401, "UNAUTHORIZED");
    }

    @Test
    void multipleRolesAreSupportedAndAuthoritiesComeFromTheDatabase() throws Exception {
        String email = email();
        JsonNode registration = tree(register(email));
        String oldToken = registration.path("accessToken").asText();
        User user = users.findByEmailIgnoreCase(email).orElseThrow();
        user.addRole(roles.findByCode(RoleCode.STAFF).orElseThrow());
        user.addRole(roles.findByCode(RoleCode.STAFF).orElseThrow());
        users.saveAndFlush(user);
        var authentication = converter.convert(decoder.decode(oldToken));
        assertThat(authentication.getAuthorities()).extracting("authority")
                .containsExactlyInAnyOrder("ROLE_CUSTOMER", "ROLE_STAFF");
        var login = tree(post("/api/v1/auth/login", Map.of("email", email, "password", PASSWORD)));
        assertThat(decoder.decode(login.path("accessToken").asText()).getClaimAsStringList("roles"))
                .containsExactlyInAnyOrder("CUSTOMER", "STAFF");
    }

    @Test
    void unrelatedEndpointsRemainDeniedUntilTheirRbacRulesAreImplemented() throws Exception {
        String token = tree(register(email())).path("accessToken").asText();
        assertError(get("/api/v1/admin/rooms", token), 403, "FORBIDDEN");
    }

    @Test
    void corsPreflightAllowsConfiguredFrontendOrigin() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
                .contains("http://localhost:5173");
    }

    @Test
    void malformedJsonDoesNotExposeInternalException() throws Exception {
        var response = HTTP.send(postRequest("/api/v1/auth/register", "{"), HttpResponse.BodyHandlers.ofString());
        assertError(response, 400, "MALFORMED_JSON");
        assertThat(response.body()).doesNotContain("Exception", "stackTrace");
    }

    private String sign(String subject, String issuer, Instant issuedAt, Instant expiresAt) {
        var builder = JwtClaimsSet.builder().subject(subject).issuer(issuer).issuedAt(issuedAt);
        if (expiresAt != null) builder.expiresAt(expiresAt);
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                builder.build())).getTokenValue();
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(status);
        JsonNode body = tree(response);
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.has("message")).isTrue();
        assertThat(body.has("details")).isTrue();
    }

    private JsonNode tree(HttpResponse<String> response) { return json.readTree(response.body()); }
    private static String email() { return UUID.randomUUID() + "@example.test"; }
    private static Map<String, String> registration(String email, String password) {
        return Map.of("email", email, "password", password, "fullName", "Test Customer");
    }
    private HttpResponse<String> register(String email) throws Exception {
        return post("/api/v1/auth/register", registration(email, PASSWORD));
    }
    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }
    private HttpRequest postRequest(String path, String body) {
        return HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }
    private HttpResponse<String> post(String path, Object body) throws Exception {
        return HTTP.send(postRequest(path, json.writeValueAsString(body)), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> get(String path, String token) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30)).GET();
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}

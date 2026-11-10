package com.hotelpms.report;

import static org.assertj.core.api.Assertions.assertThat;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.demo-seed.enabled=true", "app.demo-seed.password=ReportTest123!", "app.booking.scheduler.enabled=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners = ReportIntegrationTest.Cleanup.class, mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class ReportIntegrationTest {
    static EmbeddedPostgres postgres;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) throws IOException {
        String url = System.getenv("TEST_DB_URL");
        if (url == null || url.isBlank()) {
            postgres = EmbeddedPostgres.builder().setPort(0).start();
            p.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
            p.add("spring.datasource.username", () -> "postgres"); p.add("spring.datasource.password", () -> "postgres");
        } else {
            p.add("spring.datasource.url", () -> url); p.add("spring.datasource.username", () -> System.getenv("TEST_DB_USER"));
            p.add("spring.datasource.password", () -> System.getenv("TEST_DB_PASSWORD"));
        }
        p.add("app.jwt.secret", () -> "report-integration-test-secret-at-least-thirty-two-characters");
    }
    public static class Cleanup extends AbstractTestExecutionListener {
        @Override public int getOrder() { return 1000; }
        @Override public void afterTestClass(TestContext context) throws IOException { if (postgres != null) postgres.close(); }
    }
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    final HttpClient http = HttpClient.newHttpClient();
    HttpResponse<String> request(String method, String path, String token, Object body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) b.header("Authorization", "Bearer " + token);
        b.header("Content-Type", "application/json");
        return http.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode ok(String method, String path, String token, Object body) throws Exception {
        var response = request(method, path, token, body);
        assertThat(response.statusCode()).withFailMessage(response.body()).isBetween(200, 299);
        return json.readTree(response.body());
    }
    String login(String role) throws Exception { return ok("POST", "/api/v1/auth/login", null, Map.of("email", role + "@hotel-pms.test", "password", "ReportTest123!")).path("accessToken").asText(); }

    @Test void dailyUsesHotelDayHalfOpenBoundsFiltersAndMaskedContacts() throws Exception {
        String admin = login("admin"), staff = login("staff");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String customer = ok("POST", "/api/v1/auth/register", null, Map.of("email", "report" + suffix + "@example.test", "password", "ReportTest123!", "fullName", "Private Customer Name", "phoneNumber", "0901234502")).path("accessToken").asText();
        long type = ok("POST", "/api/v1/admin/rooms/types", admin, Map.of("code", "R" + suffix, "name", "Report test", "maxOccupancy", 2, "basePricePerNight", 100000)).path("id").asLong();
        ok("POST", "/api/v1/admin/rooms/physical", admin, Map.of("roomTypeId", type, "roomNumber", "R" + suffix, "floorNumber", 1));
        String code = ok("POST", "/api/v1/bookings", customer, Map.of("roomTypeId", type, "guestCount", 2, "startAt", "2032-06-01T17:00:00Z", "endAt", "2032-06-03T17:00:00Z")).path("bookingCode").asText();
        String base = "/api/v1/staff/bookings/daily?roomTypeId=" + type;
        assertThat(ok("GET", base + "&date=2032-06-01", staff, null).path("items").size()).isZero();
        JsonNode found = ok("GET", base + "&date=2032-06-02&status=PENDING_PAYMENT", staff, null);
        assertThat(found.path("items").size()).isEqualTo(1);
        assertThat(found.path("items").get(0).path("bookingCode").asText()).isEqualTo(code);
        assertThat(found.path("items").get(0).path("customer").path("phoneMasked").asText()).isEqualTo("*******502");
        assertThat(found.toString()).doesNotContain("0901234502", "Private Customer", "customerId", "report" + suffix + "@");
        assertThat(found.path("items").get(0).path("payableAmount").isString()).isTrue();
        assertThat(ok("GET", base + "&date=2032-06-03", staff, null).path("items").size()).isEqualTo(1);
        assertThat(ok("GET", base + "&date=2032-06-04", staff, null).path("items").size()).isZero();
        assertThat(ok("GET", base + "&date=2032-06-02&status=CANCELLED", admin, null).path("items").size()).isZero();
        assertThat(request("GET", base + "&date=2032-06-02", customer, null).statusCode()).isEqualTo(403);
        assertThat(request("GET", base + "&date=2032-06-02", null, null).statusCode()).isEqualTo(401);
    }
    @Test void invalidFiltersAre400WithStableCode() throws Exception {
        String staff = login("staff");
        for (String query : new String[]{"", "?date=2026-02-30", "?date=2026-11-02&roomTypeId=-1", "?date=2026-11-02&roomTypeId=abc", "?date=2026-11-02&status=UNKNOWN"}) {
            var response = request("GET", "/api/v1/staff/bookings/daily" + query, staff, null);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("INVALID_FILTER");
        }
    }
}
